package com.periut.retroconvert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a self-contained babric jar from a set of ornithe (calamus gen2)
 * jars: each input is reverse-converted to babric intermediaries, runtime
 * dependencies that the babric environment doesn't provide (joptsimple) are
 * downloaded and wrapped as library mods, and everything is nested under
 * META-INF/jars of one umbrella mod whose fabric.mod.json lists them all.
 *
 * The umbrella's identity (id, name, version, authors, icon) and per-submodule
 * icon overrides are driven by a {@code bundle.json} manifest sitting in the
 * source folder; see {@link #bundleFromDir}.
 */
public final class Bundler {
	/** Name of the manifest file expected inside a bundle source folder. */
	public static final String MANIFEST = "bundle.json";

	/** A maven dependency the babric runtime lacks; downloaded and JiJ'd as a library mod. */
	private static final class MavenDep {
		final String url;
		final String fileName;
		final String modId;
		final String version;

		MavenDep(String url, String fileName, String modId, String version) {
			this.url = url;
			this.fileName = fileName;
			this.modId = modId;
			this.version = version;
		}
	}

	// joptsimple is referenced by osl-entrypoints (LaunchUtils) but provided by
	// the launcher classpath under ornithe, not by babric — so we ship it.
	private static final MavenDep[] RUNTIME_DEPS = {
			new MavenDep("https://repo1.maven.org/maven2/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar",
					"jopt-simple-5.0.4.jar", "jopt-simple", "5.0.4"),
	};

	/** an icon file (name + bytes) to inject into a nested mod or the umbrella */
	private static final class Icon {
		final String name;
		final byte[] data;

		Icon(String name, byte[] data) {
			this.name = name;
			this.data = data;
		}
	}

	/** resolved umbrella identity + per-input icon overrides */
	private static final class Config {
		String id;
		String name;
		String version = "1.0.0";
		String description = "Self-contained babric bundle assembled by RetroConvert.";
		List<String> authors = new ArrayList<String>();
		Icon bundleIcon; // null = no icon on the umbrella
		final Map<String, Icon> iconByInput = new LinkedHashMap<String, Icon>(); // input filename -> icon
	}

	private final Converter.Log log;

	public Bundler(Converter.Log log) {
		this.log = log;
	}

	// ---------- manifest-driven entry point ----------

	/**
	 * Builds a bundle from a folder containing a {@link #MANIFEST}, the input
	 * jars, and any icon images. The output jar is written next to the folder as
	 * {@code <id>-<version>.jar}. The folder is read-only — nothing in it is
	 * modified.
	 */
	public Path bundleFromDir(Path dir) throws IOException {
		Path manifestPath = dir.resolve(MANIFEST);
		if (!Files.isRegularFile(manifestPath)) {
			throw new IOException("no " + MANIFEST + " found in " + dir);
		}
		Object parsed = MiniJson.parse(new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
		if (!(parsed instanceof Map)) {
			throw new IOException(MANIFEST + " must be a JSON object");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> manifest = (Map<String, Object>) parsed;

		List<Path> inputs = new ArrayList<Path>();
		DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar");
		try {
			for (Path p : stream) {
				if (Files.isRegularFile(p)) {
					inputs.add(p);
				}
			}
		} finally {
			stream.close();
		}
		if (inputs.isEmpty()) {
			throw new IOException("no input jars found in " + dir);
		}

		Config config = new Config();
		config.id = requireString(manifest, "id");
		config.name = manifest.get("name") != null ? String.valueOf(manifest.get("name")) : config.id;
		config.version = resolveField(requireString(manifest, "version"), "version", inputs);
		if (manifest.get("description") != null) {
			config.description = resolveField(String.valueOf(manifest.get("description")), "description", inputs);
		}
		if (manifest.get("authors") instanceof List) {
			for (Object a : (List<?>) manifest.get("authors")) {
				config.authors.add(String.valueOf(a));
			}
		}
		if (manifest.get("icon") != null) {
			String iconName = String.valueOf(manifest.get("icon"));
			config.bundleIcon = new Icon(iconName, readImage(dir, iconName));
		}
		// "icons": { "osl_icon.png": ["a.jar", "b.jar", ...] }
		if (manifest.get("icons") instanceof Map) {
			for (Map.Entry<?, ?> group : ((Map<?, ?>) manifest.get("icons")).entrySet()) {
				String iconName = String.valueOf(group.getKey());
				Icon icon = new Icon(iconName, readImage(dir, iconName));
				if (group.getValue() instanceof List) {
					for (Object jar : (List<?>) group.getValue()) {
						config.iconByInput.put(String.valueOf(jar), icon);
					}
				}
			}
		}

		Path outJar = dir.toAbsolutePath().getParent().resolve(config.id + "-" + config.version + ".jar");
		assemble(inputs, outJar, config);
		return outJar;
	}

	// ---------- simple entry point (no manifest) ----------

	/** Reverse-converts {@code inputs} and nests them under one umbrella named after {@code outJar}. */
	public void bundle(List<Path> inputs, Path outJar) throws IOException {
		Config config = new Config();
		config.id = sanitizeId(stripJar(outJar.getFileName().toString()));
		config.name = config.id;
		assemble(inputs, outJar, config);
	}

	// ---------- bundle into an existing mod (the mod IS the umbrella) ----------

	/**
	 * Turns an existing mod jar into a self-contained babric bundle: the umbrella's own
	 * bytecode is reverse-converted to babric, each {@code extraInput} is reverse-converted
	 * (if it's an ornithe jar) and nested under {@code META-INF/jars/}, optional runtime
	 * libraries (joptsimple) are downloaded and JiJ'd the same way, and every newly nested
	 * jar is appended to the umbrella's own {@code fabric.mod.json} {@code jars} list. Any
	 * jars the umbrella already nests (e.g. via Loom {@code include}) are preserved.
	 *
	 * <p>Unlike {@link #assemble}, no umbrella is synthesized — the umbrella's identity
	 * (id/version/name) is whatever the input mod already declares.
	 *
	 * @return the bundled jar's bytes
	 */
	public byte[] bundleInto(byte[] umbrellaJar, List<Path> extraInputs, boolean includeRuntimeDeps)
			throws IOException {
		JarConverter converter = newReverseConverter();

		// 1. Reverse-convert the umbrella mod itself (its classes/resources and any nested
		//    jars) to babric. A neutral/already-babric umbrella is left as-is.
		Map<String, byte[]> umbrella = JarConverter.readEntries(new ByteArrayInputStream(umbrellaJar));
		if (JarConverter.detect(umbrella) == JarConverter.Kind.ORNITHE) {
			log.info("Bundler: reverse-converting umbrella mod to babric intermediaries");
			umbrella = JarConverter.readEntries(new ByteArrayInputStream(converter.convert(umbrella)));
		}
		byte[] fmjBytes = umbrella.get("fabric.mod.json");
		if (fmjBytes == null) {
			throw new IOException("umbrella jar has no fabric.mod.json");
		}
		Object parsedFmj = MiniJson.parse(new String(fmjBytes, StandardCharsets.UTF_8));
		if (!(parsedFmj instanceof Map)) {
			throw new IOException("umbrella fabric.mod.json is not a JSON object");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> fmj = (Map<String, Object>) parsedFmj;
		String umbrellaId = String.valueOf(fmj.get("id"));

		// Names already occupied under META-INF/jars/ (jars the umbrella JiJ's itself).
		Map<String, byte[]> nested = new LinkedHashMap<String, byte[]>();
		List<String> reserved = new ArrayList<String>();
		for (String name : umbrella.keySet()) {
			if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
				reserved.add(name.substring("META-INF/jars/".length()));
			}
		}

		// 2. Reverse-convert + nest each extra input.
		for (Path input : extraInputs) {
			String inputName = input.getFileName().toString();
			byte[] bytes = Files.readAllBytes(input);
			Map<String, byte[]> entries = JarConverter.readEntries(new ByteArrayInputStream(bytes));
			JarConverter.Kind kind = JarConverter.detect(entries);
			byte[] outBytes;
			String outName;
			if (kind == JarConverter.Kind.ORNITHE) {
				log.info("Bundler: reverse-converting " + inputName + " to babric intermediaries");
				outBytes = converter.convert(entries);
				outName = reverseName(inputName);
			} else {
				log.info("Bundler: including " + inputName + " unchanged ("
						+ kind.name().toLowerCase(Locale.ROOT) + ")");
				outBytes = bytes;
				outName = inputName;
			}
			outBytes = patchNested(outBytes, null, umbrellaId);
			nested.put(uniqueAmong(nested, reserved, outName), outBytes);
		}

		// 3. Runtime libraries the babric environment lacks (joptsimple).
		if (includeRuntimeDeps) {
			for (MavenDep dep : RUNTIME_DEPS) {
				log.info("Bundler: downloading runtime dependency " + dep.fileName);
				byte[] lib = wrapAsLibraryMod(download(dep.url), dep, umbrellaId);
				nested.put(uniqueAmong(nested, reserved, dep.fileName), lib);
			}
		}

		// 4. Append the new nested jars to the umbrella's own fabric.mod.json jars list,
		//    preserving any entries already there.
		List<Object> jars = fmj.get("jars") instanceof List
				? new ArrayList<Object>((List<?>) fmj.get("jars")) : new ArrayList<Object>();
		for (String name : nested.keySet()) {
			Map<String, Object> ref = new LinkedHashMap<String, Object>();
			ref.put("file", "META-INF/jars/" + name);
			jars.add(ref);
		}
		fmj.put("jars", jars);
		umbrella.put("fabric.mod.json", MiniJson.write(fmj).getBytes(StandardCharsets.UTF_8));

		// 5. Write the umbrella's entries (minus stale signatures) plus the new nested jars.
		ByteArrayOutputStream result = new ByteArrayOutputStream(umbrellaJar.length + (1 << 16));
		ZipOutputStream zip = new ZipOutputStream(result);
		for (Map.Entry<String, byte[]> e : umbrella.entrySet()) {
			if (isSignatureFile(e.getKey())) {
				continue;
			}
			writeEntry(zip, e.getKey(), e.getValue());
		}
		for (Map.Entry<String, byte[]> e : nested.entrySet()) {
			writeEntry(zip, "META-INF/jars/" + e.getKey(), e.getValue());
		}
		zip.close();
		log.info("Bundler: bundled " + nested.size() + " jars into " + umbrellaId);
		return result.toByteArray();
	}

	// ---------- core assembly ----------

	/** Loads the bundled babric&lt;-&gt;calamus table inverted, ready to convert ornithe -&gt; babric. */
	private static JarConverter newReverseConverter() throws IOException {
		InputStream mappingsIn = Converter.class.getResourceAsStream("/retroconvert/babric-to-calamus-b1.7.3.tsv");
		if (mappingsIn == null) {
			throw new IOException("bundled mapping resource missing");
		}
		try {
			return new JarConverter(TokenMap.load(mappingsIn, true), true);
		} finally {
			mappingsIn.close();
		}
	}

	private void assemble(List<Path> inputs, Path outJar, Config config) throws IOException {
		JarConverter converter = newReverseConverter();

		// nested jar name -> bytes, insertion order preserved for a stable bundle
		Map<String, byte[]> nested = new LinkedHashMap<String, byte[]>();

		for (Path input : inputs) {
			String inputName = input.getFileName().toString();
			byte[] bytes = Files.readAllBytes(input);
			Map<String, byte[]> entries = JarConverter.readEntries(new ByteArrayInputStream(bytes));
			JarConverter.Kind kind = JarConverter.detect(entries);
			byte[] outBytes;
			String outName;
			if (kind == JarConverter.Kind.ORNITHE) {
				log.info("Bundler: reverse-converting " + inputName + " to babric intermediaries");
				outBytes = converter.convert(entries);
				outName = reverseName(inputName);
			} else {
				// neutral / already-babric: ship as-is (e.g. osl-entrypoints carries
				// no intermediary tokens, only the joptsimple reference)
				log.info("Bundler: including " + inputName + " unchanged ("
						+ kind.name().toLowerCase(Locale.ROOT) + ")");
				outBytes = bytes;
				outName = inputName;
			}
			Icon icon = config.iconByInput.get(inputName);
			// nest every submodule under the umbrella in ModMenu; the explicit
			// parent overrides ModMenu's hardcoded "osl-* -> osl" grouping
			outBytes = patchNested(outBytes, icon, config.id);
			nested.put(uniqueName(nested, outName), outBytes);
		}

		for (MavenDep dep : RUNTIME_DEPS) {
			log.info("Bundler: downloading runtime dependency " + dep.fileName);
			byte[] lib = wrapAsLibraryMod(download(dep.url), dep, config.id);
			nested.put(uniqueName(nested, dep.fileName), lib);
		}

		byte[] umbrella = buildUmbrella(config, nested);
		Files.write(outJar, umbrella);
		log.info("Bundler: wrote " + outJar + " (" + config.name + " " + config.version + ") with "
				+ nested.size() + " nested jars");
	}

	/**
	 * Patches a nested mod's fabric.mod.json: optionally sets its {@code icon}
	 * (embedding the image at the jar root) and sets {@code custom.modmenu.parent}
	 * so ModMenu nests it under the umbrella mod. No-op for non-mod jars.
	 */
	private static byte[] patchNested(byte[] jar, Icon icon, String parentId) throws IOException {
		Map<String, byte[]> entries = JarConverter.readEntries(new ByteArrayInputStream(jar));
		byte[] fmjBytes = entries.get("fabric.mod.json");
		if (fmjBytes == null) {
			return jar; // not a fabric mod
		}
		Object parsed = MiniJson.parse(new String(fmjBytes, StandardCharsets.UTF_8));
		if (!(parsed instanceof Map)) {
			return jar;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> fmj = (Map<String, Object>) parsed;
		if (icon != null) {
			fmj.put("icon", icon.name);
			entries.put(icon.name, icon.data);
		}
		setModMenuParent(fmj, parentId);
		entries.put("fabric.mod.json", MiniJson.write(fmj).getBytes(StandardCharsets.UTF_8));

		ByteArrayOutputStream result = new ByteArrayOutputStream(jar.length + 512);
		ZipOutputStream zip = new ZipOutputStream(result);
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			if (isSignatureFile(e.getKey())) {
				continue;
			}
			writeEntry(zip, e.getKey(), e.getValue());
		}
		zip.close();
		return result.toByteArray();
	}

	/** Sets custom.modmenu.parent, preserving any existing custom/modmenu data. */
	@SuppressWarnings("unchecked")
	private static void setModMenuParent(Map<String, Object> fmj, String parentId) {
		if (parentId.equals(fmj.get("id"))) {
			return; // a mod can't be its own parent
		}
		Object customObj = fmj.get("custom");
		Map<String, Object> custom = customObj instanceof Map
				? (Map<String, Object>) customObj : new LinkedHashMap<String, Object>();
		Object modmenuObj = custom.get("modmenu");
		Map<String, Object> modmenu = modmenuObj instanceof Map
				? (Map<String, Object>) modmenuObj : new LinkedHashMap<String, Object>();
		modmenu.put("parent", parentId);
		custom.put("modmenu", modmenu);
		fmj.put("custom", custom);
	}

	/** Wraps a plain library jar as a minimal fabric library mod so Fabric will load it via JiJ. */
	private static byte[] wrapAsLibraryMod(byte[] jar, MavenDep dep, String parentId) throws IOException {
		Map<String, byte[]> entries = JarConverter.readEntries(new ByteArrayInputStream(jar));
		if (entries.containsKey("fabric.mod.json")) {
			return patchNested(jar, null, parentId); // already a fabric mod; just nest it
		}
		Map<String, Object> fmj = new LinkedHashMap<String, Object>();
		fmj.put("schemaVersion", 1L);
		fmj.put("id", dep.modId);
		fmj.put("version", dep.version);
		fmj.put("name", dep.modId);
		Map<String, Object> depends = new LinkedHashMap<String, Object>();
		depends.put("fabricloader", ">=0.17.0");
		fmj.put("depends", depends);
		setModMenuParent(fmj, parentId);

		ByteArrayOutputStream result = new ByteArrayOutputStream(jar.length + 256);
		ZipOutputStream zip = new ZipOutputStream(result);
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			if (isSignatureFile(e.getKey())) {
				continue;
			}
			writeEntry(zip, e.getKey(), e.getValue());
		}
		writeEntry(zip, "fabric.mod.json", MiniJson.write(fmj).getBytes(StandardCharsets.UTF_8));
		zip.close();
		return result.toByteArray();
	}

	/** Builds the umbrella mod jar: a fabric.mod.json listing every nested jar under META-INF/jars/. */
	private static byte[] buildUmbrella(Config config, Map<String, byte[]> nested) throws IOException {
		Map<String, Object> fmj = new LinkedHashMap<String, Object>();
		fmj.put("schemaVersion", 1L);
		fmj.put("id", config.id);
		fmj.put("version", config.version);
		fmj.put("name", config.name);
		fmj.put("description", config.description);
		if (!config.authors.isEmpty()) {
			fmj.put("authors", new ArrayList<Object>(config.authors));
		}
		if (config.bundleIcon != null) {
			fmj.put("icon", config.bundleIcon.name);
		}
		Map<String, Object> depends = new LinkedHashMap<String, Object>();
		depends.put("fabricloader", ">=0.17.0");
		fmj.put("depends", depends);
		List<Object> jars = new ArrayList<Object>();
		for (String name : nested.keySet()) {
			Map<String, Object> ref = new LinkedHashMap<String, Object>();
			ref.put("file", "META-INF/jars/" + name);
			jars.add(ref);
		}
		fmj.put("jars", jars);

		ByteArrayOutputStream result = new ByteArrayOutputStream(1 << 16);
		ZipOutputStream zip = new ZipOutputStream(result);
		writeEntry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n".getBytes(StandardCharsets.UTF_8));
		writeEntry(zip, "fabric.mod.json", MiniJson.write(fmj).getBytes(StandardCharsets.UTF_8));
		if (config.bundleIcon != null) {
			writeEntry(zip, config.bundleIcon.name, config.bundleIcon.data);
		}
		for (Map.Entry<String, byte[]> e : nested.entrySet()) {
			writeEntry(zip, "META-INF/jars/" + e.getKey(), e.getValue());
		}
		zip.close();
		return result.toByteArray();
	}

	// ---------- helpers ----------

	/**
	 * Resolves a manifest field value: a literal, or a glob like {@code retroapi*.jar}
	 * that lifts {@code key} (e.g. "version", "description") from the matching input
	 * jar's fabric.mod.json.
	 */
	private static String resolveField(String spec, String key, List<Path> inputs) throws IOException {
		boolean glob = spec.indexOf('*') >= 0 || spec.endsWith(".jar");
		if (!glob) {
			return spec;
		}
		String regex = globToRegex(spec);
		for (Path input : inputs) {
			if (input.getFileName().toString().matches(regex)) {
				Map<String, byte[]> entries = JarConverter.readEntries(
						new ByteArrayInputStream(Files.readAllBytes(input)));
				byte[] fmj = entries.get("fabric.mod.json");
				if (fmj != null) {
					Object parsed = MiniJson.parse(new String(fmj, StandardCharsets.UTF_8));
					if (parsed instanceof Map && ((Map<?, ?>) parsed).get(key) != null) {
						return String.valueOf(((Map<?, ?>) parsed).get(key));
					}
				}
			}
		}
		throw new IOException("spec '" + spec + "' matched no input jar with a readable '" + key + "'");
	}

	private static String globToRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*') {
				sb.append(".*");
			} else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
				sb.append('\\').append(c);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static byte[] readImage(Path dir, String name) throws IOException {
		Path img = dir.resolve(name);
		if (!Files.isRegularFile(img)) {
			throw new IOException("icon '" + name + "' not found in " + dir);
		}
		return Files.readAllBytes(img);
	}

	private static String requireString(Map<String, Object> manifest, String key) throws IOException {
		Object v = manifest.get(key);
		if (v == null || String.valueOf(v).isEmpty()) {
			throw new IOException(MANIFEST + " is missing required field '" + key + "'");
		}
		return String.valueOf(v);
	}

	private static void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
		if (data == null) { // directory entry
			zip.putNextEntry(new ZipEntry(name.endsWith("/") ? name : name + "/"));
			zip.closeEntry();
			return;
		}
		zip.putNextEntry(new ZipEntry(name));
		zip.write(data);
		zip.closeEntry();
	}

	private static boolean isSignatureFile(String name) {
		if (!name.startsWith("META-INF/")) {
			return false;
		}
		return name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA") || name.endsWith(".EC");
	}

	/** retroapi-1.1.0.jar -> retroapi-1.1.0-babric.jar */
	private static String reverseName(String fileName) {
		String stem = stripJar(fileName);
		if (stem.endsWith("-ornithe")) {
			stem = stem.substring(0, stem.length() - "-ornithe".length());
		}
		return stem + "-babric.jar";
	}

	private static String stripJar(String fileName) {
		return fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
	}

	private static String sanitizeId(String s) {
		String id = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-").replaceAll("-+", "-");
		id = id.replaceAll("^-|-$", "");
		return id.isEmpty() ? "retroconvert-bundle" : id;
	}

	private static String uniqueName(Map<String, byte[]> taken, String name) {
		if (!taken.containsKey(name)) {
			return name;
		}
		String stem = stripJar(name);
		int i = 1;
		while (taken.containsKey(stem + "_" + i + ".jar")) {
			i++;
		}
		return stem + "_" + i + ".jar";
	}

	/** Like {@link #uniqueName} but also avoids names already nested in the umbrella. */
	private static String uniqueAmong(Map<String, byte[]> taken, List<String> reserved, String name) {
		if (!taken.containsKey(name) && !reserved.contains(name)) {
			return name;
		}
		String stem = stripJar(name);
		int i = 1;
		String candidate;
		do {
			candidate = stem + "_" + i + ".jar";
			i++;
		} while (taken.containsKey(candidate) || reserved.contains(candidate));
		return candidate;
	}

	private static byte[] download(String url) throws IOException {
		java.net.HttpURLConnection connection =
				(java.net.HttpURLConnection) new java.net.URL(url).openConnection();
		connection.setConnectTimeout(10_000);
		connection.setReadTimeout(60_000);
		connection.setRequestProperty("User-Agent", "RetroConvert");
		try {
			int code = connection.getResponseCode();
			if (code != 200) {
				throw new IOException("HTTP " + code + " for " + url);
			}
			InputStream in = connection.getInputStream();
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
				byte[] buffer = new byte[16384];
				int read;
				while ((read = in.read(buffer)) > 0) {
					out.write(buffer, 0, read);
				}
				return out.toByteArray();
			} finally {
				in.close();
			}
		} finally {
			connection.disconnect();
		}
	}
}

package com.periut.retroconvert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

/**
 * Detects which toolchain a mod jar was built for and rewrites babric jars to
 * ornithe (calamus gen2) intermediaries: class bytecode (incl. mixin
 * annotations and string constants), accesswideners, refmaps / json resources,
 * and nested META-INF/jars recursively.
 */
public final class JarConverter {
	public enum Kind {
		/** contains calamus tokens — already an ornithe mod */
		ORNITHE,
		/** contains babric tokens only — convert it */
		BABRIC,
		/** references neither intermediary — loader-agnostic, leave alone */
		NEUTRAL
	}

	// A babric mod can never contain calamus-shaped tokens, while hand-ported
	// ornithe mods are known to keep stale babric-looking strings (e.g. a
	// reflection name candidate "field_220"). Calamus tokens therefore win.
	private static final Pattern ORNITHE_TOKENS = Pattern.compile(
			"net/minecraft/unmapped/C_\\d{8}|\\bm_\\d{8}\\b|\\bf_\\d{8}\\b");
	private static final Pattern BABRIC_TOKENS = Pattern.compile(
			"net/minecraft/class_\\d+|\\bmethod_\\d+\\b|\\bfield_\\d+\\b"
					+ "|com[./]matthewperiut[./]accessoryapi");

	private final TokenMap map;

	public JarConverter(TokenMap map) {
		this.map = map;
	}

	/** Reads every entry of a jar (from a stream) into memory, preserving order. */
	public static Map<String, byte[]> readEntries(InputStream in) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
		ZipInputStream zip = new ZipInputStream(in);
		ZipEntry entry;
		byte[] buffer = new byte[8192];
		while ((entry = zip.getNextEntry()) != null) {
			if (entry.isDirectory()) {
				entries.put(entry.getName(), null);
				continue;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, (int) Math.max(0, entry.getSize())));
			int read;
			while ((read = zip.read(buffer)) > 0) {
				out.write(buffer, 0, read);
			}
			entries.put(entry.getName(), out.toByteArray());
		}
		return entries;
	}

	/**
	 * Detects the toolchain of a jar, recursing into nested META-INF/jars
	 * (umbrella mods like StationAPI keep ALL their classes in nested jars).
	 *
	 * Ornithe wins over babric within a single jar scope (hand-ported mods keep
	 * stale babric-looking strings), but a babric verdict from any nested jar
	 * bubbles up: the outer jar then needs conversion, during which each nested
	 * jar is re-checked individually so already-ornithe ones stay untouched.
	 */
	public static Kind detect(Map<String, byte[]> entries) {
		boolean babric = false;
		boolean ornithe = false;
		Kind scope = detectScope(entries);
		if (scope == Kind.ORNITHE) {
			ornithe = true;
		} else if (scope == Kind.BABRIC) {
			babric = true;
		}
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String name = e.getKey();
			if (e.getValue() == null || !name.startsWith("META-INF/jars/") || !name.endsWith(".jar")) {
				continue;
			}
			try {
				Kind nested = detect(readEntries(new ByteArrayInputStream(e.getValue())));
				if (nested == Kind.BABRIC) {
					babric = true;
				} else if (nested == Kind.ORNITHE) {
					ornithe = true;
				}
			} catch (IOException unreadable) {
				// not a readable jar; ignored for detection
			}
		}
		return babric ? Kind.BABRIC : (ornithe ? Kind.ORNITHE : Kind.NEUTRAL);
	}

	/** Token detection over one jar's own files, nested jars excluded. */
	private static Kind detectScope(Map<String, byte[]> entries) {
		boolean babric = false;
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String name = e.getKey();
			byte[] data = e.getValue();
			if (data == null || !isScannable(name)) {
				continue;
			}
			String text = new String(data, StandardCharsets.ISO_8859_1);
			if (ORNITHE_TOKENS.matcher(text).find()) {
				return Kind.ORNITHE;
			}
			if (!babric && BABRIC_TOKENS.matcher(text).find()) {
				babric = true;
			}
		}
		return babric ? Kind.BABRIC : Kind.NEUTRAL;
	}

	private static boolean isScannable(String name) {
		return name.endsWith(".class") || name.endsWith(".json") || name.endsWith(".accesswidener")
				|| name.equals("fabric.mod.json");
	}

	/** Converts a whole babric jar (as an entry map) to ornithe, recursing into nested jars. */
	public byte[] convert(Map<String, byte[]> entries) throws IOException {
		// jar-internal class hierarchy, so owner-aware library renames resolve
		// through mod classes that extend/implement library types
		Map<String, RetroRemapper.ClassMeta> hierarchy = new HashMap<String, RetroRemapper.ClassMeta>();
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			if (e.getKey().endsWith(".class") && e.getValue() != null) {
				try {
					ClassReader reader = new ClassReader(e.getValue());
					hierarchy.put(reader.getClassName(),
							new RetroRemapper.ClassMeta(reader.getSuperName(), reader.getInterfaces()));
				} catch (RuntimeException ignored) {
					// not a parsable class file; copied through verbatim below
				}
			}
		}
		RetroRemapper remapper = new RetroRemapper(map, hierarchy);

		boolean signed = false;
		for (String name : entries.keySet()) {
			if (isSignatureFile(name)) {
				signed = true;
				break;
			}
		}

		ByteArrayOutputStream result = new ByteArrayOutputStream(1 << 16);
		ZipOutputStream zip = new ZipOutputStream(result);
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String name = e.getKey();
			byte[] data = e.getValue();
			if (isSignatureFile(name)) {
				continue; // contents change; signatures must go
			}
			// the accessoryapi package moved, so its class/resource paths move too
			String outName = name.startsWith(TokenMap.OLD_API_SLASH + "/")
					? TokenMap.NEW_API_SLASH + name.substring(TokenMap.OLD_API_SLASH.length())
					: name;
			byte[] outData;
			if (data == null) {
				zip.putNextEntry(new ZipEntry(outName.endsWith("/") ? outName : outName + "/"));
				zip.closeEntry();
				continue;
			} else if (name.endsWith(".class")) {
				outData = convertClass(data, remapper);
			} else if (name.endsWith(".json") || name.endsWith(".accesswidener")) {
				// covers fabric.mod.json, mixin configs, refmaps and accesswideners:
				// babric tokens are unambiguous, so plain token remapping is safe
				String text = new String(data, StandardCharsets.UTF_8);
				outData = map.remapString(text).getBytes(StandardCharsets.UTF_8);
			} else if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
				outData = convertNestedJar(data);
			} else if (signed && name.equals("META-INF/MANIFEST.MF")) {
				outData = stripManifestDigests(data);
			} else {
				outData = data;
			}
			zip.putNextEntry(new ZipEntry(outName));
			zip.write(outData);
			zip.closeEntry();
		}
		zip.close();
		return result.toByteArray();
	}

	private byte[] convertClass(byte[] data, RetroRemapper remapper) {
		try {
			ClassReader reader = new ClassReader(data);
			ClassWriter writer = new ClassWriter(0);
			reader.accept(new ClassRemapper(writer, remapper), ClassReader.EXPAND_FRAMES);
			return writer.toByteArray();
		} catch (RuntimeException ex) {
			// unparsable "class" entry: leave untouched rather than corrupt it
			return data;
		}
	}

	/** Nested jars are full mods of their own; convert them the same way if needed. */
	private byte[] convertNestedJar(byte[] data) throws IOException {
		Map<String, byte[]> nested = readEntries(new ByteArrayInputStream(data));
		if (detect(nested) != Kind.BABRIC) {
			return data;
		}
		return convert(nested);
	}

	private static boolean isSignatureFile(String name) {
		if (!name.startsWith("META-INF/")) {
			return false;
		}
		return name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA") || name.endsWith(".EC");
	}

	/** Keeps only the main section of the manifest; per-entry digests are stale once we rewrite contents. */
	private static byte[] stripManifestDigests(byte[] data) {
		String text = new String(data, StandardCharsets.UTF_8);
		int cut = text.indexOf("\r\n\r\n");
		if (cut < 0) {
			cut = text.indexOf("\n\n");
			return cut < 0 ? data : text.substring(0, cut + 2).getBytes(StandardCharsets.UTF_8);
		}
		return text.substring(0, cut + 4).getBytes(StandardCharsets.UTF_8);
	}
}

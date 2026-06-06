package com.periut.retroconvert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans the mods folder, converts every newly seen babric jar to ornithe
 * intermediaries, moves the original into config/retroconvert/originals/, and
 * records everything in config/retroconvert/cache.json.
 *
 * Two-phase design: all conversion work (bytecode rewriting, downloads) is
 * STAGED to temp files first; the mods folder is only mutated in a final
 * commit pass. The loader's grip on the jars (a hard lock on Windows) is
 * released only if a move actually fails — and since releasing it closes the
 * mod classloader, nothing after that point may lazy-load classes through it
 * (log4j included; the host's Log impl must be failsafe).
 */
public final class Converter {
	public static final class Result {
		public int scanned;
		public int converted;
		public int alreadyOrnithe;
		public int neutral;
		public int skippedKnown;
		public int failed;
		public final List<String> convertedNames = new ArrayList<String>();
		public final List<String> pendingSwaps = new ArrayList<String>();
	}

	public interface Log {
		void info(String message);

		void warn(String message, Throwable t);
	}

	/** a staged mods-folder mutation, applied during the final commit pass */
	private static final class Planned {
		final Path original;
		final Path backupTarget;
		/** null for archive-only (superseded by an already-installed official build) */
		final Path tmpPath;
		final Path outPath;
		/** registry keys to roll back if this swap hard-fails */
		final String[] hashes;
		final String label;

		Planned(Path original, Path backupTarget, Path tmpPath, Path outPath, String[] hashes, String label) {
			this.original = original;
			this.backupTarget = backupTarget;
			this.tmpPath = tmpPath;
			this.outPath = outPath;
			this.hashes = hashes;
			this.label = label;
		}
	}

	private final Path modsDir;
	private final Path registryPath;
	private final Path backupDir;
	private final Log log;
	private LockReleaser lockReleaser;
	private final List<Planned> planned = new ArrayList<Planned>();
	private final java.util.Set<Path> plannedTargets = new java.util.HashSet<Path>();

	/**
	 * Asks the host to release file locks on the mods folder (fabric loader's
	 * open jar handles). Only set in mod mode — the agent and the CLI run
	 * before anything could hold a lock.
	 */
	public interface LockReleaser {
		boolean releaseLocks();
	}

	/** @param configBaseDir usually {@code config/retroconvert}; holds cache.json and originals/ */
	public Converter(Path modsDir, Path configBaseDir, Log log) {
		this.modsDir = modsDir;
		this.registryPath = configBaseDir.resolve("cache.json");
		this.backupDir = configBaseDir.resolve("originals");
		this.log = log;
	}

	public void setLockReleaser(LockReleaser lockReleaser) {
		this.lockReleaser = lockReleaser;
	}

	public Result run() throws IOException {
		Result result = new Result();
		TokenMap tokenMap;
		InputStream mappingsIn = Converter.class.getResourceAsStream("/retroconvert/babric-to-calamus-b1.7.3.tsv");
		if (mappingsIn == null) {
			throw new IOException("bundled mapping resource missing");
		}
		try {
			tokenMap = TokenMap.load(mappingsIn);
		} finally {
			mappingsIn.close();
		}
		JarConverter converter = new JarConverter(tokenMap);
		Registry registry = Registry.load(registryPath);

		List<Path> jars = new ArrayList<Path>();
		if (Files.isDirectory(modsDir)) {
			DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar");
			try {
				for (Path path : stream) {
					if (Files.isRegularFile(path)) {
						jars.add(path);
					}
				}
			} finally {
				stream.close();
			}
		}

		// clear leftovers from an interrupted previous run
		cleanStaleTemp();

		for (Path jar : jars) {
			result.scanned++;
			try {
				processJar(jar, converter, registry, result);
			} catch (Exception ex) {
				result.failed++;
				log.warn("RetroConvert failed to process " + jar.getFileName(), ex);
			}
		}

		// all staging done; now mutate the mods folder in one go
		commit(registry, result);

		if (registry.isDirty()) {
			registry.save(registryPath);
		}
		return result;
	}

	/**
	 * Applies every staged swap. Moves are tried plainly first (on POSIX they
	 * just work even while the loader holds the jars); only an actual failure
	 * (Windows lock) makes the host release the loader's grip, after which the
	 * moves are retried. A helper process is the last resort.
	 */
	private void commit(Registry registry, Result result) {
		boolean releaseAttempted = false;
		for (Planned swap : planned) {
			try {
				Files.createDirectories(backupDir);
				boolean moved = quietMove(swap.original, swap.backupTarget);
				if (!moved && lockReleaser != null && !releaseAttempted) {
					releaseAttempted = true;
					lockReleaser.releaseLocks();
					moved = quietMove(swap.original, swap.backupTarget);
				}
				if (moved) {
					if (swap.tmpPath != null) {
						Files.move(swap.tmpPath, swap.outPath);
					}
					continue;
				}
				// still held (antivirus, exotic loader): copy the backup now and
				// let a helper process finish once this JVM exits
				Files.copy(swap.original, swap.backupTarget, StandardCopyOption.REPLACE_EXISTING);
				boolean spawned = swap.tmpPath != null
						? SwapHelper.spawn(swap.original, swap.tmpPath, swap.outPath, log)
						: SwapHelper.spawnDelete(swap.original, log);
				if (spawned) {
					result.pendingSwaps.add(swap.original.getFileName().toString());
				} else {
					rollback(swap, registry, result);
				}
			} catch (IOException ex) {
				log.warn("RetroConvert: could not commit swap for " + swap.original.getFileName(), ex);
				rollback(swap, registry, result);
			}
		}
		planned.clear();
		plannedTargets.clear();
	}

	private void rollback(Planned swap, Registry registry, Result result) {
		try {
			if (swap.tmpPath != null) {
				Files.deleteIfExists(swap.tmpPath);
			}
			Files.deleteIfExists(swap.backupTarget);
		} catch (IOException ignored) {
		}
		for (String hash : swap.hashes) {
			registry.remove(hash);
		}
		result.converted--;
		result.failed++;
		result.convertedNames.remove(swap.label);
		log.warn("RetroConvert: left " + swap.original.getFileName() + " untouched (could not replace it)", null);
	}

	private static boolean quietMove(Path from, Path to) {
		try {
			Files.move(from, to);
			return true;
		} catch (IOException locked) {
			return false;
		}
	}

	private void processJar(Path jar, JarConverter converter, Registry registry, Result result) throws IOException {
		String fileName = jar.getFileName().toString();
		// Never touch RetroConvert itself: its own constant pool contains babric
		// token literals (regexes, the accessoryapi package constant) and would
		// both false-positive the detector and get corrupted by a conversion.
		if (fileName.toLowerCase(java.util.Locale.ROOT).contains("retroconvert")) {
			result.skippedKnown++;
			return;
		}
		byte[] jarBytes = Files.readAllBytes(jar);
		String hash = Registry.sha256(jarBytes);

		Registry.Entry known = registry.get(hash);
		if (known != null) {
			// Upgrade path: this jar is one of OUR earlier bytecode conversions,
			// but the mod now has an official Ornithe build (rules can ship after
			// a mod was already converted). Swap it for the real thing.
			if (Registry.STATUS_OUTPUT.equals(known.status)
					&& findReplacement(fileName, jarBytes.length) != null) {
				log.info("RetroConvert: " + fileName + " is an old bytecode conversion with an official Ornithe build available; upgrading");
				if (tryReplace(jar, fileName, hash, findReplacement(fileName, jarBytes.length), registry, result)) {
					return;
				}
			}
			result.skippedKnown++;
			if (!fileName.equals(known.file)) { // renamed by the user; keep registry readable
				known.file = fileName;
				registry.put(hash, known);
			}
			return;
		}

		Map<String, byte[]> entries = JarConverter.readEntries(new java.io.ByteArrayInputStream(jarBytes));
		if (!entries.containsKey("fabric.mod.json")) {
			result.neutral++;
			registry.put(hash, new Registry.Entry(fileName, "not-a-fabric-mod", null, System.currentTimeMillis()));
			return;
		}
		if ("retroconvert".equals(modId(entries.get("fabric.mod.json")))) { // renamed copy of ourselves
			result.skippedKnown++;
			return;
		}

		JarConverter.Kind kind = JarConverter.detect(entries);
		switch (kind) {
		case ORNITHE:
			result.alreadyOrnithe++;
			registry.put(hash, new Registry.Entry(fileName, Registry.STATUS_ORNITHE, null, System.currentTimeMillis()));
			return;
		case NEUTRAL:
			result.neutral++;
			registry.put(hash, new Registry.Entry(fileName, Registry.STATUS_NEUTRAL, null, System.currentTimeMillis()));
			return;
		case BABRIC:
		default:
			break;
		}

		// Some mods changed too much between toolchains for a bytecode rewrite to
		// be enough; for those, swap in the official Ornithe build instead.
		Replacement replacement = findReplacement(fileName, jarBytes.length);
		if (replacement != null && tryReplace(jar, fileName, hash, replacement, registry, result)) {
			return;
		}

		log.info("RetroConvert: converting babric mod " + fileName + " to ornithe intermediaries");
		byte[] convertedBytes = converter.convert(entries);
		String outName = outputName(fileName);
		Path outPath = uniqueTarget(modsDir, outName);
		Path tmpPath = modsDir.resolve(outPath.getFileName() + ".retroconvert.tmp");
		Files.write(tmpPath, convertedBytes);

		String outHash = Registry.sha256(convertedBytes);
		long now = System.currentTimeMillis();
		registry.put(hash, new Registry.Entry(fileName, Registry.STATUS_CONVERTED, outPath.getFileName().toString(), now));
		registry.put(outHash, new Registry.Entry(outPath.getFileName().toString(), Registry.STATUS_OUTPUT, fileName, now));
		result.converted++;
		String label = fileName + " -> " + outPath.getFileName();
		result.convertedNames.add(label);
		planned.add(new Planned(jar, uniqueTarget(backupDir, fileName), tmpPath, outPath,
				new String[] { hash, outHash }, label));
	}

	// ---------- official-build replacements ----------

	private static final class Replacement {
		final String namePrefix;
		final long minSize;
		final String url;
		final String fileName;

		Replacement(String namePrefix, long minSize, String url, String fileName) {
			this.namePrefix = namePrefix;
			this.minSize = minSize;
			this.url = url;
			this.fileName = fileName;
		}
	}

	// Name prefix and size are pre-filters only — the babric detection above is
	// the real gate (the official gen2 StationAPI is itself >10 MiB).
	private static final Replacement[] REPLACEMENTS = {
			new Replacement("StationAPI-2.0.0-alpha.", 10L * 1024 * 1024,
					"https://matthewperiut.github.io/repository/net/modificationstation/StationAPI/2.0.0-alpha.6.2+gen2/StationAPI-2.0.0-alpha.6.2+gen2.jar",
					"StationAPI-2.0.0-alpha.6.2+gen2.jar"),
			new Replacement("GlassConfigAPI-3.", 2_621_440L /* 2.5 MiB */,
					"https://matthewperiut.github.io/repository/net/glasslauncher/mods/GlassConfigAPI/3.3.0+gen2/GlassConfigAPI-3.3.0+gen2.jar",
					"GlassConfigAPI-3.3.0+gen2.jar"),
	};

	private static Replacement findReplacement(String fileName, long size) {
		for (Replacement candidate : REPLACEMENTS) {
			if (fileName.startsWith(candidate.namePrefix) && size > candidate.minSize) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Swaps a babric jar for its official Ornithe build. Returns false if the
	 * download failed — the caller then falls back to bytecode conversion,
	 * which beats leaving a guaranteed-crash babric jar in place.
	 */
	private boolean tryReplace(Path jar, String fileName, String hash, Replacement replacement,
			Registry registry, Result result) {
		try {
			long now = System.currentTimeMillis();
			Path outPath = modsDir.resolve(replacement.fileName);
			if (Files.exists(outPath) || plannedTargets.contains(outPath)) {
				// official build already installed; just archive the babric jar so
				// the loader doesn't see two mods with the same id
				log.info("RetroConvert: " + fileName + " is superseded by the already-installed "
						+ replacement.fileName + "; archiving it");
				registry.put(hash, new Registry.Entry(fileName, Registry.STATUS_REPLACED, replacement.fileName, now));
				result.converted++;
				String label = fileName + " (superseded by installed " + replacement.fileName + ")";
				result.convertedNames.add(label);
				planned.add(new Planned(jar, uniqueTarget(backupDir, fileName), null, null,
						new String[] { hash }, label));
				return true;
			}

			log.info("RetroConvert: " + fileName + " changed too much between toolchains; downloading the official "
					+ replacement.fileName);
			byte[] data = download(replacement.url);
			String outHash = Registry.sha256(data);
			if (registry.get(outHash) != null) {
				// the same official build is already present under another name
				log.info("RetroConvert: official build already installed under another name; archiving " + fileName);
				registry.put(hash, new Registry.Entry(fileName, Registry.STATUS_REPLACED, replacement.fileName, now));
				result.converted++;
				String label = fileName + " (superseded by installed official build)";
				result.convertedNames.add(label);
				planned.add(new Planned(jar, uniqueTarget(backupDir, fileName), null, null,
						new String[] { hash }, label));
				return true;
			}

			Path tmpPath = modsDir.resolve(replacement.fileName + ".retroconvert.tmp");
			Files.write(tmpPath, data);

			registry.put(hash, new Registry.Entry(fileName, Registry.STATUS_REPLACED, replacement.fileName, now));
			registry.put(outHash, new Registry.Entry(replacement.fileName, Registry.STATUS_OFFICIAL, fileName, now));
			result.converted++;
			String label = fileName + " -> " + replacement.fileName + " (official Ornithe build)";
			result.convertedNames.add(label);
			plannedTargets.add(outPath);
			planned.add(new Planned(jar, uniqueTarget(backupDir, fileName), tmpPath, outPath,
					new String[] { hash, outHash }, label));
			return true;
		} catch (IOException ex) {
			log.warn("RetroConvert: could not fetch the official build for " + fileName
					+ "; falling back to bytecode conversion", ex);
			return false;
		}
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
			java.io.InputStream in = connection.getInputStream();
			try {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
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

	private static String modId(byte[] fabricModJson) {
		try {
			Object root = MiniJson.parse(new String(fabricModJson, java.nio.charset.StandardCharsets.UTF_8));
			if (root instanceof Map) {
				Object id = ((Map<?, ?>) root).get("id");
				return id == null ? null : String.valueOf(id);
			}
		} catch (RuntimeException malformed) {
			// fall through; the loader will complain about it, not us
		}
		return null;
	}

	/** retrocommands-0.5.10-babric.jar -> retrocommands-0.5.10-ornithe.jar */
	private static String outputName(String fileName) {
		String stem = fileName.substring(0, fileName.length() - ".jar".length());
		if (stem.endsWith("-babric")) {
			stem = stem.substring(0, stem.length() - "-babric".length());
		}
		return stem + "-ornithe.jar";
	}

	/** picks a name not taken on disk NOR by an already-staged swap */
	private Path uniqueTarget(Path dir, String name) {
		Path path = dir.resolve(name);
		int counter = 1;
		while (Files.exists(path) || plannedTargets.contains(path)) {
			String stem = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
			path = dir.resolve(stem + "_" + counter++ + ".jar");
		}
		plannedTargets.add(path);
		return path;
	}

	private void cleanStaleTemp() {
		if (!Files.isDirectory(modsDir)) {
			return;
		}
		try {
			DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.retroconvert.tmp");
			try {
				for (Path path : stream) {
					Files.deleteIfExists(path);
				}
			} finally {
				stream.close();
			}
		} catch (IOException ignored) {
		}
	}
}

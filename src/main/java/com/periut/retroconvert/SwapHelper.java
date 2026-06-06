package com.periut.retroconvert;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Finishes a jar swap that could not happen in-process because the running
 * loader holds the original jar open (Windows file locking). Spawned as a
 * detached JVM; waits for the parent to exit (releasing the lock), deletes the
 * original (already copied to the backup folder) and moves the converted jar
 * into place. On POSIX systems the in-process move succeeds and this class is
 * never used.
 */
public final class SwapHelper {
	/**
	 * 3 args: delete the locked original, then move tmp into its place.
	 * 1 arg: just delete the locked original (it was superseded, not converted).
	 */
	public static void main(String[] args) throws Exception {
		Path original = Paths.get(args[0]);
		Path tmp = args.length >= 3 ? Paths.get(args[1]) : null;
		Path target = args.length >= 3 ? Paths.get(args[2]) : null;
		long deadline = System.currentTimeMillis() + 120_000L;
		while (System.currentTimeMillis() < deadline) {
			try {
				if (Files.deleteIfExists(original)) {
					if (tmp != null) {
						Files.move(tmp, target);
					}
					return;
				}
			} catch (IOException stillLocked) {
				// parent JVM not gone yet
			}
			Thread.sleep(500);
		}
		// timed out: drop the temp file so the next launch isn't confused
		if (tmp != null) {
			Files.deleteIfExists(tmp);
		}
	}

	/** Spawns the helper JVM. Returns false if that isn't possible (then the caller must undo). */
	public static boolean spawn(Path original, Path tmp, Path target, Converter.Log log) {
		return spawn(log, "finish the swap",
				original.toAbsolutePath().toString(),
				tmp.toAbsolutePath().toString(),
				target.toAbsolutePath().toString());
	}

	/** Delete-only variant for originals superseded by a downloaded official build. */
	public static boolean spawnDelete(Path original, Converter.Log log) {
		return spawn(log, "remove the superseded jar", original.toAbsolutePath().toString());
	}

	private static boolean spawn(Converter.Log log, String purpose, String... helperArgs) {
		try {
			String classpath = codeSource();
			if (classpath == null) {
				return false;
			}
			String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator
					+ (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
			List<String> command = new ArrayList<String>();
			command.add(javaBin);
			command.add("-cp");
			command.add(classpath);
			command.add(SwapHelper.class.getName());
			for (String arg : helperArgs) {
				command.add(arg);
			}
			new ProcessBuilder(command)
					.redirectOutput(ProcessBuilder.Redirect.INHERIT)
					.redirectError(ProcessBuilder.Redirect.INHERIT)
					.start();
			log.info("RetroConvert: " + helperArgs[0] + " is locked; a helper will " + purpose + " when the game closes");
			return true;
		} catch (Exception ex) {
			log.warn("RetroConvert: could not spawn swap helper", ex);
			return false;
		}
	}

	private static String codeSource() {
		try {
			java.security.CodeSource source = SwapHelper.class.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				return null;
			}
			return new File(source.getLocation().toURI()).getAbsolutePath();
		} catch (URISyntaxException e) {
			return null;
		}
	}
}

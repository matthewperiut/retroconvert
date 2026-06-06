package com.periut.retroconvert;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Headless entry point: run the exact same conversion pipeline against any
 * mods directory without launching the game.
 *
 *   java -cp ... com.periut.retroconvert.ConverterCli <modsDir> [configBaseDir]
 *
 * configBaseDir (default: <modsDir>/../config/retroconvert, falling back to
 * <modsDir>/retroconvert) receives cache.json and originals/.
 */
public final class ConverterCli {
	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: ConverterCli <modsDir> [configBaseDir]");
			System.exit(2);
		}
		Path modsDir = Paths.get(args[0]);
		Path configBase;
		if (args.length > 1) {
			configBase = Paths.get(args[1]);
		} else if (modsDir.toAbsolutePath().getParent() != null) {
			configBase = modsDir.toAbsolutePath().getParent().resolve("config").resolve("retroconvert");
		} else {
			configBase = modsDir.resolve("retroconvert");
		}

		Converter.Result result = new Converter(modsDir, configBase, new Converter.Log() {
			@Override
			public void info(String message) {
				System.out.println("[info] " + message);
			}

			@Override
			public void warn(String message, Throwable t) {
				System.out.println("[warn] " + message);
				if (t != null) {
					t.printStackTrace(System.out);
				}
			}
		}).run();

		System.out.println("scanned=" + result.scanned
				+ " converted=" + result.converted
				+ " ornithe=" + result.alreadyOrnithe
				+ " neutral=" + result.neutral
				+ " known=" + result.skippedKnown
				+ " failed=" + result.failed);
		for (String name : result.convertedNames) {
			System.out.println("converted: " + name);
		}
		if (result.failed > 0) {
			System.exit(1);
		}
	}
}

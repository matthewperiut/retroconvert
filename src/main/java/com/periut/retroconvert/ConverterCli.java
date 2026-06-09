package com.periut.retroconvert;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Headless entry point: run the exact same conversion pipeline against any
 * mods directory without launching the game.
 *
 *   java -cp ... com.periut.retroconvert.ConverterCli [--reverse] <modsDir> [configBaseDir]
 *
 * configBaseDir (default: <modsDir>/../config/retroconvert, falling back to
 * <modsDir>/retroconvert) receives cache.json and originals/.
 *
 * --reverse (-r) flips the direction to ornithe -> babric. It does NOT move the
 * accessoryapi package back; com.matthewperiut.accessoryapi is left in place.
 */
public final class ConverterCli {
	public static void main(String[] args) throws Exception {
		boolean reverse = false;
		boolean bundle = false;
		boolean bundleDir = false;
		java.util.List<String> positional = new java.util.ArrayList<String>();
		for (String arg : args) {
			if ("--reverse".equals(arg) || "-r".equals(arg)) {
				reverse = true;
			} else if ("--bundle".equals(arg)) {
				bundle = true;
			} else if ("--bundle-dir".equals(arg)) {
				bundleDir = true;
			} else {
				positional.add(arg);
			}
		}

		Converter.Log stdoutLog = new Converter.Log() {
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
		};

		if (bundleDir) {
			if (positional.size() != 1) {
				System.err.println("usage: ConverterCli --bundle-dir <folder containing " + Bundler.MANIFEST + ">");
				System.exit(2);
			}
			Path out = new Bundler(stdoutLog).bundleFromDir(Paths.get(positional.get(0)));
			System.out.println("bundle: " + out);
			return;
		}

		if (bundle) {
			if (positional.size() < 2) {
				System.err.println("usage: ConverterCli --bundle <out.jar> <input.jar> [input.jar ...]");
				System.exit(2);
			}
			Path outJar = Paths.get(positional.get(0));
			java.util.List<Path> inputs = new java.util.ArrayList<Path>();
			for (String s : positional.subList(1, positional.size())) {
				inputs.add(Paths.get(s));
			}
			new Bundler(stdoutLog).bundle(inputs, outJar);
			return;
		}

		if (positional.isEmpty()) {
			System.err.println("usage: ConverterCli [--reverse] <modsDir> [configBaseDir]");
			System.err.println("       ConverterCli --bundle <out.jar> <input.jar> [input.jar ...]");
			System.err.println("       ConverterCli --bundle-dir <folder containing " + Bundler.MANIFEST + ">");
			System.exit(2);
		}
		Path modsDir = Paths.get(positional.get(0));
		Path configBase;
		if (positional.size() > 1) {
			configBase = Paths.get(positional.get(1));
		} else if (modsDir.toAbsolutePath().getParent() != null) {
			configBase = modsDir.toAbsolutePath().getParent().resolve("config").resolve("retroconvert");
		} else {
			configBase = modsDir.resolve("retroconvert");
		}

		Converter.Result result = new Converter(modsDir, configBase, stdoutLog, reverse).run();

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

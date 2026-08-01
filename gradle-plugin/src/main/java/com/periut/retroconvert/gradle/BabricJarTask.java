package com.periut.retroconvert.gradle;

import com.periut.retroconvert.Bundler;
import com.periut.retroconvert.Converter;
import com.periut.retroconvert.JarConverter;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reverse-converts a remapped ornithe (calamus gen2) mod jar into a
 * babric-intermediary jar so the same mod can be shipped to babric users.
 *
 * <p>If {@link #getBundleInputs() bundle inputs} are configured, the jar is instead
 * turned into a self-contained babric bundle: those dependency jars (and optionally
 * joptsimple) are reverse-converted and nested under {@code META-INF/jars/} and added
 * to the mod's own {@code fabric.mod.json}. Both paths reuse the shared engine via
 * {@link Conversions}/{@link Bundler}.
 */
@CacheableTask
public abstract class BabricJarTask extends DefaultTask {
	// NAME_ONLY: the conversion depends on the jar bytes and (for nested bundle entries)
	// the file names, never on where the jars live on disk.
	@InputFile
	@PathSensitive(PathSensitivity.NAME_ONLY)
	public abstract RegularFileProperty getInputJar();

	/** Dependency jars to reverse-convert and nest into the bundle. Empty = plain reverse-convert. */
	@InputFiles
	@PathSensitive(PathSensitivity.NAME_ONLY)
	public abstract ConfigurableFileCollection getBundleInputs();

	/** When bundling, also download + JiJ runtime libraries the babric env lacks (joptsimple). */
	@Input
	public abstract Property<Boolean> getBundleRuntimeLibraries();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@TaskAction
	public void convert() throws IOException {
		File in = getInputJar().get().getAsFile();
		File out = getOutputJar().get().getAsFile();
		byte[] bytes = Files.readAllBytes(in.toPath());

		Set<File> extras = getBundleInputs().getFiles();
		byte[] result;
		if (extras.isEmpty()) {
			result = reverseConvertOnly(bytes, in.getName());
		} else {
			List<Path> inputs = new ArrayList<>();
			for (File f : extras) {
				inputs.add(f.toPath());
			}
			Bundler bundler = new Bundler(gradleLog());
			result = bundler.bundleInto(bytes, inputs, getBundleRuntimeLibraries().getOrElse(true));
			getLogger().lifecycle("retroconvert: bundled {} dependency jar(s) into {}",
					inputs.size(), out.getName());
		}

		warnOnCalamusResidue(result, out.getName());

		Files.createDirectories(out.toPath().getParent());
		Files.write(out.toPath(), result);
		getLogger().lifecycle("retroconvert: wrote {}", out.getName());
	}

	/**
	 * A finished babric jar should contain no calamus token that b1.7.3 mappings
	 * cover. One that survives is a mapping gap, and it fails far away from here —
	 * a leftover token in a mixin annotation only surfaces as an
	 * InvalidInjectionException at game launch. Warn (never fail) with the actual
	 * tokens, so the next gap is one grep away.
	 *
	 * <p>Tokens outside those mappings are a different animal and must not be
	 * warned about: ornithe's multi-version libraries (OSL and friends, versioned
	 * {@code +mcb1.0-mc13w39b}) are compiled against the newest version of their
	 * range, so they carry references to members that simply do not exist in
	 * b1.7.3. Calamus numbers each version separately, so there is nothing to
	 * convert them to — and the code paths touching them are exactly as dead
	 * under ornithe as under babric.
	 */
	private void warnOnCalamusResidue(byte[] jarBytes, String name) throws IOException {
		Set<String> leftovers = Conversions.findCalamusTokens(jarBytes, 10);
		if (leftovers.isEmpty()) {
			return;
		}
		Set<String> gaps = Conversions.mappable(leftovers);
		if (!gaps.isEmpty()) {
			getLogger().warn("retroconvert: {} still contains calamus tokens after reverse conversion "
					+ "(mapping gap, these will fail at runtime): {}", name, gaps);
		}
		Set<String> foreign = new LinkedHashSet<>(leftovers);
		foreign.removeAll(gaps);
		if (!foreign.isEmpty()) {
			getLogger().info("retroconvert: {} keeps {} calamus token(s) with no b1.7.3 equivalent "
					+ "(other-version references from a multi-version library, dead here either way): {}",
					name, foreign.size(), foreign);
		}
	}

	private byte[] reverseConvertOnly(byte[] bytes, String name) throws IOException {
		JarConverter.Kind kind = Conversions.detect(bytes);
		if (kind == JarConverter.Kind.ORNITHE) {
			return Conversions.convert(bytes, true); // ornithe -> babric
		}
		getLogger().lifecycle("retroconvert: {} detected as {} (not ornithe); copying through unchanged",
				name, kind);
		return bytes;
	}

	/** Bridges the engine's logging interface to this task's Gradle logger. */
	private Converter.Log gradleLog() {
		return new Converter.Log() {
			@Override
			public void info(String message) {
				getLogger().lifecycle(message);
			}

			@Override
			public void warn(String message, Throwable t) {
				getLogger().warn(message, t);
			}
		};
	}
}

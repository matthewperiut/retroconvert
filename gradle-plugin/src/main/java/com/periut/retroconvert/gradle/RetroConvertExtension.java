package com.periut.retroconvert.gradle;

import com.periut.retroconvert.JarConverter;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The {@code retroconvert { }} project extension.
 *
 * <p>Besides configuration ({@link #setTargetIntermediary}), it exposes
 * {@link #localize(Object)} — a helper you wrap around a dependency notation so a
 * mod published under one intermediary can be consumed in a workspace using the
 * other. It resolves the artifact, detects its intermediary, and (only if needed)
 * converts it to the local one before handing it to {@code modImplementation},
 * {@code modRuntimeOnly}, {@code modCompileOnly}, etc.
 */
public class RetroConvertExtension {
	private final Project project;
	private String targetIntermediary;
	private final java.util.List<Object> bundleDependencies = new java.util.ArrayList<Object>();
	private boolean bundleRuntimeLibraries = true;

	public RetroConvertExtension(Project project) {
		this.project = project;
	}

	/**
	 * Adds a dependency to nest into the {@code -babric} jar, turning it into a
	 * self-contained babric bundle. Each notation is resolved, reverse-converted to
	 * babric, and JiJ'd under {@code META-INF/jars/}. Call once per dependency.
	 */
	public void bundle(Object notation) {
		bundleDependencies.add(notation);
	}

	public java.util.List<Object> getBundleDependencies() {
		return bundleDependencies;
	}

	/** Whether the bundle should also include runtime libraries the babric env lacks (joptsimple). Default true. */
	public boolean isBundleRuntimeLibraries() {
		return bundleRuntimeLibraries;
	}

	public void setBundleRuntimeLibraries(boolean bundleRuntimeLibraries) {
		this.bundleRuntimeLibraries = bundleRuntimeLibraries;
	}

	/** Force the local intermediary: {@code "babric"} or {@code "ornithe"}. */
	public void setTargetIntermediary(String targetIntermediary) {
		this.targetIntermediary = targetIntermediary;
	}

	/**
	 * The intermediary this workspace compiles against. Explicit setting wins;
	 * otherwise inferred from the loom flavour applied (babric-loom-extension =&gt;
	 * babric, anything else =&gt; ornithe gen2).
	 */
	public String getTargetIntermediary() {
		if (targetIntermediary != null) {
			return targetIntermediary;
		}
		if (project.getPlugins().hasPlugin("babric-loom-extension")) {
			return "babric";
		}
		return "ornithe";
	}

	/**
	 * Resolves {@code notation} (any Gradle dependency notation), converts the jar
	 * to the local {@linkplain #getTargetIntermediary() target intermediary} if it
	 * isn't already, and returns it as a {@link FileCollection} ready to feed to a
	 * {@code mod*} configuration.
	 */
	public FileCollection localize(Object notation) {
		Configuration cfg = project.getConfigurations()
				.detachedConfiguration(project.getDependencies().create(notation));
		cfg.setTransitive(false);
		Set<File> files = cfg.resolve();
		if (files.size() != 1) {
			throw new GradleException("retroconvert.localize expected exactly one artifact for '"
					+ notation + "', but resolved " + files.size());
		}
		File src = files.iterator().next();
		String target = getTargetIntermediary();
		boolean babricTarget = "babric".equalsIgnoreCase(target);
		JarConverter.Kind want = babricTarget ? JarConverter.Kind.BABRIC : JarConverter.Kind.ORNITHE;

		// Cache key from the resolved artifact's immutable identity (path/size/mtime) plus
		// the target. Resolved module-cache files never change in place, so this lets a
		// repeat build skip the read + ASM rewrite entirely and just reuse the output.
		String tag = shortHash(src.getAbsolutePath() + "|" + src.length() + "|"
				+ src.lastModified() + "|" + target);
		String name = src.getName();
		String base = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
		// Kept in the Gradle user home (not under build/) so it survives `clean` and is
		// shared across projects; the tag keys on the source's absolute path so there
		// are no cross-artifact collisions.
		File outDir = new File(project.getGradle().getGradleUserHomeDir(), "caches/retroconvert/localized");
		File out = new File(outDir, base + "-" + target + "-" + tag + ".jar");

		if (out.isFile()) {
			project.getLogger().info("retroconvert: localize cache hit for {} -> {}", name, out.getName());
			return project.files(out);
		}

		try {
			byte[] bytes = Files.readAllBytes(src.toPath());
			JarConverter.Kind kind = Conversions.detect(bytes);
			byte[] result;
			if (kind == want || kind == JarConverter.Kind.NEUTRAL) {
				// Already in the local intermediary (or carries no tokens at all): pass
				// through, but still cache a copy so future builds are a pure cache hit.
				result = bytes;
				project.getLogger().info("retroconvert: {} already {} (no conversion needed)", name, kind);
			} else {
				// babric target <- ornithe source needs the reverse direction; the
				// ornithe target <- babric source is the forward direction.
				result = Conversions.convert(bytes, babricTarget);
				project.getLogger().lifecycle("retroconvert: localized {} ({} -> {})", name, kind, target);
			}

			Files.createDirectories(outDir.toPath());
			// Write to a unique temp sibling then move into place, so a cancelled build (or
			// a concurrent localize of the same key under --parallel) never leaves a
			// half-written jar that a later run would treat as a cache hit.
			File tmp = new File(outDir, out.getName() + "." + System.nanoTime() + ".tmp");
			Files.write(tmp.toPath(), result);
			Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
			return project.files(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** First 12 hex chars of the SHA-256 of {@code s} — a short, stable cache tag. */
	private static String shortHash(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
			String hex = String.format("%064x", new BigInteger(1, digest));
			return hex.substring(0, 12);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e); // SHA-256 is guaranteed present
		}
	}
}

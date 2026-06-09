package com.periut.retroconvert.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

import java.io.File;

/**
 * Adds a babric variant to an ornithe (calamus gen2) mod build.
 *
 * <p>For an ornithe project it registers {@code babricJar}, which reverse-converts
 * the loom {@code remapJar} output into a {@code *-babric.jar}, wires it into
 * {@code build}/{@code assemble}, and (if {@code maven-publish} is applied)
 * attaches it to every publication under the {@code babric} classifier.
 *
 * <p>It also installs the {@code retroconvert { }} extension whose
 * {@link RetroConvertExtension#localize(Object)} converts dependencies across
 * intermediaries on the way in.
 */
public class RetroConvertPlugin implements Plugin<Project> {
	@Override
	public void apply(Project project) {
		RetroConvertExtension ext = project.getExtensions()
				.create("retroconvert", RetroConvertExtension.class, project);

		TaskProvider<BabricJarTask> babricJar = project.getTasks()
				.register("babricJar", BabricJarTask.class, task -> {
					task.setGroup("retroconvert");
					task.setDescription("Reverse-convert the remapped ornithe mod jar into a babric-intermediary jar.");
					task.getBundleRuntimeLibraries().convention(true);
				});

		project.afterEvaluate(p -> {
			// The babric variant only makes sense for an ornithe workspace; a babric
			// project is already babric. Skip the wiring there (the task stays available
			// to run manually if someone really wants it).
			if (!"ornithe".equalsIgnoreCase(ext.getTargetIntermediary())) {
				return;
			}
			if (p.getTasks().findByName("remapJar") == null) {
				p.getLogger().warn("retroconvert: no 'remapJar' task found; babricJar not wired into the build. "
						+ "Apply this plugin to a loom/ploceus mod project.");
				return;
			}
			TaskProvider<AbstractArchiveTask> remap = p.getTasks().named("remapJar", AbstractArchiveTask.class);
			babricJar.configure(task -> {
				task.dependsOn(remap);
				task.getInputJar().set(remap.flatMap(AbstractArchiveTask::getArchiveFile));
				// <libs>/<name>-babric.jar, mirroring remapJar's own naming.
				task.getOutputJar().fileProvider(remap.flatMap(AbstractArchiveTask::getArchiveFile).map(rf -> {
					File f = rf.getAsFile();
					String n = f.getName();
					String stem = n.endsWith(".jar") ? n.substring(0, n.length() - 4) : n;
					return new File(f.getParentFile(), stem + "-babric.jar");
				}));
				task.getBundleRuntimeLibraries().set(ext.isBundleRuntimeLibraries());
			});

			// Each configured bundle dependency is resolved (non-transitively) and nested
			// into the -babric jar. Resolution is deferred to task execution.
			for (Object notation : ext.getBundleDependencies()) {
				Configuration dep = p.getConfigurations()
						.detachedConfiguration(p.getDependencies().create(notation));
				dep.setTransitive(false);
				babricJar.configure(task -> task.getBundleInputs().from(dep));
			}
			p.getTasks().named("assemble").configure(t -> t.dependsOn(babricJar));
			p.getTasks().named("build").configure(t -> t.dependsOn(babricJar));

			p.getPluginManager().withPlugin("maven-publish", applied -> {
				PublishingExtension publishing = p.getExtensions().getByType(PublishingExtension.class);
				publishing.getPublications().withType(MavenPublication.class).configureEach(pub ->
						pub.artifact(babricJar.flatMap(BabricJarTask::getOutputJar), art -> {
							art.setClassifier("babric");
							art.setExtension("jar");
							art.builtBy(babricJar);
						}));
			});
		});
	}
}

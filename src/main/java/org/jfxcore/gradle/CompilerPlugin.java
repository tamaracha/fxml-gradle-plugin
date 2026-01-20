// Copyright (c) 2023, 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.*;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.jfxcore.gradle.compiler.CompilerService;
import org.jfxcore.gradle.tasks.ProcessFxmlTask;
import org.jfxcore.gradle.tasks.FxmlSourceInfo;

import javax.inject.Inject;
import java.io.File;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class CompilerPlugin implements Plugin<Project> {
    @Inject
    protected abstract ProjectLayout getLayout();
    @Inject
    protected abstract ObjectFactory getObjects();

    @Override
    public void apply(Project project) {
        project.getGradle().getSharedServices().registerIfAbsent(CompilerService.NAME, CompilerService.class);
        CompilerService.register(project);

        // React to the Java plugin being applied
        project.getPluginManager().withPlugin("java", javaPlugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            // For each source set, add the corresponding generated sources directory, so it can be
            // picked up by the Java compiler.
            sourceSets.configureEach(sourceSet -> configureTasksForSourceSet(project, sourceSet));
        });
    }

    private void configureTasksForSourceSet(Project project, SourceSet sourceSet) {
        // Define and configure a dedicated source directory set for FXML markup files
        final SourceDirectorySet fxml = project.getObjects().sourceDirectorySet("FXML", "FXML Markup Sources");
        // Add it to the source set, so it is accessible via script DSL
        sourceSet.getExtensions().add("fxml", fxml);
        // Define source directories manually. Using `getAllSource` runs into circular dependencies, because we add generated sources to the java source.
        final Directory sourceBase = project.getLayout().getProjectDirectory().dir("src").dir(sourceSet.getName());
        fxml.srcDir(sourceBase.dir("java"));
        // Add plugin-specific source directories if these plugins are applied
        project.getPluginManager().withPlugin("groovy", plugin -> fxml.srcDir(sourceBase.dir("groovy")));
        project.getPluginManager().withPlugin("scala", plugin -> fxml.srcDir(sourceBase.dir("scala")));
        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", plugin -> fxml.srcDir(sourceBase.dir("kotlin")));
        // Watch only files with the .fxml or .fxmlx extensions
        final String[] includes = new String[] { "**/*.fxml", "**/*.fxmlx" };
        fxml.include(includes);
        // This is where the generated sources go, and this is configurable by users.
        fxml.getDestinationDirectory().convention(project.getLayout().getBuildDirectory().dir("generated/sources/fxml/java/" + sourceSet.getName()));
        // Actually the same as destinationDir, but it depends on the connected tasks, so Java compilation tasks depend on FXML processing tasks
        sourceSet.getJava().srcDir(fxml.getClassesDirectory());

        // Provides filtered source files structured as needed by the compiler
        final Provider<Set<FxmlSourceInfo>> sourceTrees = getSourceTrees(fxml.getSourceDirectories(), includes);
        // Configure the FXML processing task with source set conventions
        final TaskProvider<ProcessFxmlTask> processFxmlTask = project.getTasks().register(sourceSet.getTaskName("process", "fxml"), ProcessFxmlTask.class, task -> {
                task.getCompilationId().convention(UUID.randomUUID());
                task.getSourceTrees().convention(sourceTrees);
                    task.getSearchPath().from(sourceSet.getOutput());
                    task.getSearchPath().from(sourceSet.getCompileClasspath());
            task.getGeneratedSourcesDir().convention(fxml.getDestinationDirectory());
                task.getClassesDir().convention(sourceSet.getJava().getDestinationDirectory());
            });
        // Tell the source directory set on which task its outputs depend on
        fxml.compiledBy(processFxmlTask, ProcessFxmlTask::getGeneratedSourcesDir);

        // Run the FXML compiler at the end of compileJava's action list. This is important for
        // incremental compilation: Gradle will fingerprint the compiled class files after the
        // last task action is executed, i.e. after the FXML compiler has rewritten the bytecode.
        final RunCompilerAction action = project.getObjects().newInstance(RunCompilerAction.class, project.getLogger());
        action.getCompilationId().convention(processFxmlTask.flatMap(ProcessFxmlTask::getCompilationId));
        action.getSourceTrees().convention(sourceTrees);
        action.getGenSrcDir().convention(fxml.getClassesDirectory());
        action.getClassesDir().convention(sourceSet.getJava().getClassesDirectory());
        action.getSearchPath().from(processFxmlTask.map(ProcessFxmlTask::getSearchPath));
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), task -> task.doLast(action));
    }

    /** Returns a set of managed objects, each of them containing a source root directory and corresponding FXML markup files */
    private Provider<Set<FxmlSourceInfo>> getSourceTrees(FileCollection dirs, String[] includes) {
        return dirs.getElements().map(elements -> {
            return elements.stream()
                    .map(FileSystemLocation::getAsFile)
                    .filter(File::exists)
                    .map(d -> {
                        final Directory dir = getLayout().getProjectDirectory().dir(d.getAbsolutePath());
                        final Set<File> files = dir.getAsFileTree()
                                .matching(p -> p.include(includes))
                                .getFiles();
                        if (files.isEmpty()) return null;
                        final FxmlSourceInfo config = getObjects().newInstance(FxmlSourceInfo.class);
                        config.getDir().set(d);
                        config.getFiles().set(files);
                        return config;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        });
    }
}

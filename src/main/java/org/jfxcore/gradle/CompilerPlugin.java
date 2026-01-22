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
import org.jfxcore.gradle.tasks.SourceTree;

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

        // For each source set, add the corresponding generated sources directory, so it can be
        // picked up by the Java compiler.
        project.getPluginManager().withPlugin("java", javaPlugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.configureEach(sourceSet -> configureTasksForSourceSet(project, sourceSet));
        });
    }

    private void configureTasksForSourceSet(Project project, SourceSet sourceSet) {
        final Directory sourceBase = project.getLayout().getProjectDirectory().dir("src").dir(sourceSet.getName());
        final String[] includes = new String[] { "**/*.fxml", "**/*.fxmlx" };
        final TaskProvider<ProcessFxmlTask> processFxmlTask = project.getTasks().register(sourceSet.getTaskName("process", "fxml"), ProcessFxmlTask.class);
        final SourceDirectorySet java = sourceSet.getJava();
        final SourceDirectorySet fxml = project.getObjects().sourceDirectorySet("FXML", "FXML Markup Sources");
        sourceSet.getExtensions().add("fxml", fxml);
        for (String target : new String[] { "java", "kotlin", "scala", "groovy" }) {
            fxml.srcDir(sourceBase.dir(target));
        }
        fxml.include(includes);
        fxml.getDestinationDirectory().convention(project.getLayout().getBuildDirectory().dir("generated/sources/fxml/java/" + sourceSet.getName()));
        fxml.compiledBy(processFxmlTask, ProcessFxmlTask::getGeneratedSourcesDir);
        java.srcDir(fxml.getClassesDirectory());

        final Provider<Set<SourceTree>> sourceTrees = getSourceTrees(fxml.getSourceDirectories(), includes);
        processFxmlTask.configure(task -> {
                task.getCompilationId().convention(UUID.randomUUID());
                task.getSourceTrees().convention(sourceTrees);
                    task.getSearchPath().from(sourceSet.getOutput());
                    task.getSearchPath().from(sourceSet.getCompileClasspath());
            task.getGeneratedSourcesDir().convention(fxml.getDestinationDirectory());
                task.getClassesDir().convention(java.getDestinationDirectory());
            });

        // Run the FXML compiler at the end of compileJava's action list. This is important for
        // incremental compilation: Gradle will fingerprint the compiled class files after the
        // last task action is executed, i.e. after the FXML compiler has rewritten the bytecode.
        final RunCompilerAction action = project.getObjects().newInstance(RunCompilerAction.class, project.getLogger());
        action.getCompilationId().convention(processFxmlTask.flatMap(ProcessFxmlTask::getCompilationId));
        action.getSourceTrees().convention(sourceTrees);
        action.getGenSrcDir().convention(fxml.getClassesDirectory());
        action.getClassesDir().convention(java.getClassesDirectory());
        action.getSearchPath().from(processFxmlTask.map(ProcessFxmlTask::getSearchPath));
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), task -> task.doLast(action));
    }

    private Provider<Set<SourceTree>> getSourceTrees(FileCollection dirs, String[] includes) {
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
                        final SourceTree config = getObjects().newInstance(SourceTree.class);
                        config.getDir().set(d);
                        config.getFiles().set(files);
                        return config;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        });
    }
}

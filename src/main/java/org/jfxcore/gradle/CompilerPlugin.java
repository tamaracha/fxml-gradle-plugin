// Copyright (c) 2023, 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.jfxcore.gradle.compiler.CompilerService;
import org.jfxcore.gradle.tasks.FxmlSourceInfo;
import org.jfxcore.gradle.tasks.ProcessFxmlTask;

import java.util.UUID;

public class CompilerPlugin implements Plugin<Project> {

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
        FileCollection srcDirs = sourceSet.getAllSource().getSourceDirectories();
        Provider<Directory> genSrcDir = project.getLayout().getBuildDirectory().dir("generated/sources/fxml/java/" + sourceSet.getName());
        Provider<Directory> classesDir = sourceSet.getJava().getClassesDirectory();

        Provider<ProcessFxmlTask> processFxmlTask = project.getTasks().register(
            sourceSet.getTaskName(ProcessFxmlTask.VERB, ProcessFxmlTask.TARGET),
            ProcessFxmlTask.class, task -> {
                task.getCompilationId().set(UUID.randomUUID());
                    task.getSearchPath().from(sourceSet.getOutput());
                    task.getSearchPath().from(sourceSet.getCompileClasspath());
                    task.getCompileClasspath().from(sourceSet.getCompileClasspath());
                    task.getFxmlSourceInfo().set(project.provider(() ->
                            PathHelper.getFxmlFilesPerSourceDirectory(srcDirs.getFiles(), genSrcDir.get().getAsFile()).entrySet().stream()
                                    .map(entry -> {
                                        FxmlSourceInfo sourceInfo = project.getObjects().newInstance(FxmlSourceInfo.class);
                                        sourceInfo.getSourceDir().set(entry.getKey());
                                        sourceInfo.getFxmlFiles().setFrom(project.files(entry.getValue()));
                                        return sourceInfo;
                                    }).toList()
                    ));
                task.getClassesDir().set(classesDir);
                task.getGeneratedSourcesDir().set(genSrcDir);
            });
        sourceSet.getJava().srcDir(processFxmlTask.flatMap(ProcessFxmlTask::getGeneratedSourcesDir));

        // Run the FXML compiler at the end of compileJava's action list. This is important for
        // incremental compilation: Gradle will fingerprint the compiled class files after the
        // last task action is executed, i.e. after the FXML compiler has rewritten the bytecode.
        final var action = project.getObjects().newInstance(RunCompilerAction.class, project.getLogger());
        action.getCompilationId().set(processFxmlTask.flatMap(ProcessFxmlTask::getCompilationId));
        action.getGenSrcDir().set(processFxmlTask.flatMap(ProcessFxmlTask::getGeneratedSourcesDir));
        action.getSrcDirs().from(srcDirs);
        action.getClassesDir().set(processFxmlTask.flatMap(ProcessFxmlTask::getClassesDir));
        action.getSearchPath().from(processFxmlTask.map(ProcessFxmlTask::getSearchPath));
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), task -> task.doLast(action));
    }
}

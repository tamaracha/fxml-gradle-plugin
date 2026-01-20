// Copyright (c) 2023, 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.gradle.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.*;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.*;
import org.jfxcore.gradle.compiler.Compiler;
import org.jfxcore.gradle.compiler.CompilerService;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public abstract class ProcessFxmlTask extends DefaultTask {
    @ServiceReference(CompilerService.NAME)
    protected abstract Property<CompilerService> getCompilerService();

    @Input
    public abstract Property<UUID> getCompilationId();

    @Internal
    public abstract ConfigurableFileCollection getSearchPath();

    @Nested
    public abstract SetProperty<FxmlSourceInfo> getSourceTrees();

    @InputFiles
    public abstract ConfigurableFileCollection getCompileClasspath();

    @OutputDirectory
    public abstract DirectoryProperty getClassesDir();

    @OutputDirectory
    public abstract DirectoryProperty getGeneratedSourcesDir();

    @TaskAction
    public void process() {
        UUID compilationId = getCompilationId().get();
        FileCollection searchPath = getSearchPath();
        File classesDir = getClassesDir().get().getAsFile();
        File genSrcDir = getGeneratedSourcesDir().get().getAsFile();
        CompilerService service = getCompilerService().get();
        Compiler compiler = service.newCompiler(compilationId, searchPath, classesDir, genSrcDir, getLogger());

        try {
            // Invoke the addFiles and processFiles stages for the source set.
            // This will generate .java source files that are placed in the generated source directory.
            Map<File, List<File>> info = getSourceTrees().get().stream().collect(Collectors.toMap(
                    x -> x.getDir().get(),
                    x -> x.getFiles().get().stream().toList()
            ));
            compiler.addFiles(info);

            compiler.processFiles();

            // Delete all .class files that may have been created by a previous compiler run.
            // This is necessary because the FXML compiler needs a 'clean slate' to work with.
            compiler.getCompilationUnits().getMarkupClassFiles().forEach(File::delete);
        } catch (Throwable ex) {
            compiler.close();
            compiler.getExceptionHelper().handleException(ex, getLogger());
            throw new GradleException("Internal compiler error", ex);
        }

        setDidWork(true);
    }
}

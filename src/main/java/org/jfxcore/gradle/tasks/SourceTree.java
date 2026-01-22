package org.jfxcore.gradle.tasks;

import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;

import java.io.File;

public interface SourceTree {
    @Input
    Property<File> getDir();
    @InputFiles
    SetProperty<File> getFiles();
}

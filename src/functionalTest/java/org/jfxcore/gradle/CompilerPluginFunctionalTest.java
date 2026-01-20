// Copyright (c) 2023, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license that can be found in the LICENSE file.

package org.jfxcore.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerPluginFunctionalTest {

    @Test
    void nonModularProject() {
        var runner = createRunner("non-modular", "8.7");
        var result = runner.build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
    }

    @Test
    void modularProject() {
        var runner = createRunner("modular", "8.7");
        var result = runner.build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
    }

    private GradleRunner createRunner(String project, String gradleVersion) {
        return GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(new File("test-project"))
                .withGradleVersion(gradleVersion)
                .withArguments(project + ":clean", project + ":build", "--stacktrace")
                .forwardOutput();
    }
}

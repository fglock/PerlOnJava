package org.perlonjava.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.core.Configuration;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ForkOpenStateLauncherClasspathTest {
    @Test
    void embeddedRuntimeRelaunchUsesTheLaunchersBuiltArtifact() {
        String launcher = System.getenv("PERLONJAVA_EXECUTABLE");
        assertTrue(launcher != null && !launcher.isEmpty(),
                "the unit-test task supplies the repository launcher");
        File launcherDirectory = new File(launcher).getAbsoluteFile().getParentFile();
        File expectedJar = new File(launcherDirectory,
                "target/perlonjava-" + Configuration.version + ".jar");
        assertTrue(expectedJar.isFile(),
                "shadowJar dependency publishes the child runtime artifact");

        List<String> command = ForkOpenState.currentJavaCommand(
                null, new String[] {"worker.org.gradle.process.internal.worker.GradleWorkerMain"});
        int classpath = command.indexOf("-cp");

        assertTrue(classpath >= 0, "reconstructed child command has a classpath");
        assertEquals(expectedJar.getAbsolutePath(), command.get(classpath + 1));
        assertEquals("org.perlonjava.app.cli.Main", command.get(classpath + 2));
    }
}

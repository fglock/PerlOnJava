package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SystemOperatorAbsoluteJperlBatchTest {

    @Test
    void quotedBatchArgumentUsesArgvTransport(@TempDir Path directory) throws Exception {
        Path launcher = Files.createFile(directory.resolve("external-tool.bat"));
        String program = "print \"out\\n\"; warn \"err\\n\"";

        List<String> command = SystemOperator.buildWindowsBatchCommand(
                List.of(launcher.toString(), "-e", program), directory.toFile());

        assertTrue(command.contains(WindowsBatchArgvLauncher.class.getName()));
        assertFalse(command.contains("cmd.exe"));
    }

    @Test
    void resolvedJperlBatchNameUsesDirectJavaArgvWithoutLauncherIdentity() {
        File currentDirectory = new File("build/a188 work tree").getAbsoluteFile();
        String program = "print \"out\\n\"; warn \"err\\n\"";

        List<String> command = SystemOperator.buildWindowsResolvedCommand(
                List.of("jperl.bat", "-e", program), currentDirectory, null);
        int main = command.indexOf("org.perlonjava.app.cli.Main");

        assertTrue(main > 0, "direct command contains the PerlOnJava CLI main class");
        assertEquals(List.of("-e", program),
                command.subList(main + 1, command.size()));
        assertFalse(command.contains("cmd.exe"));
    }

    @Test
    void windowsBackslashJperlPathUsesDirectJavaArgvOnEveryBuildHost() {
        File currentDirectory = new File("build/a188 work tree").getAbsoluteFile();
        String launcher = "D:\\missing-a188-launcher\\jperl.bat";
        String program = "print \"out\\n\"; warn \"err\\n\"";

        List<String> command = SystemOperator.buildWindowsResolvedCommand(
                List.of(launcher, "-e", program), currentDirectory, null);
        int main = command.indexOf("org.perlonjava.app.cli.Main");

        assertTrue(main > 0, "direct command contains the PerlOnJava CLI main class");
        assertEquals(List.of("-e", program),
                command.subList(main + 1, command.size()));
        assertFalse(command.contains("cmd.exe"));
    }

    @Test
    void absoluteJperlBatchUsesDirectJavaArgvWithoutRuntimeLauncherIdentity() {
        File currentDirectory = new File("build/a188 work tree").getAbsoluteFile();
        File launcher = new File(currentDirectory, "jperl.bat");
        String program = "print \"out\\n\"; warn \"err\\n\"";

        List<String> command = SystemOperator.buildWindowsResolvedCommand(
                List.of(launcher.getAbsolutePath(), "-e", program),
                currentDirectory, null);
        int main = command.indexOf("org.perlonjava.app.cli.Main");

        assertTrue(main > 0, "direct command contains the PerlOnJava CLI main class");
        assertEquals(List.of("-e", program),
                command.subList(main + 1, command.size()));
        assertFalse(command.contains("cmd.exe"));
    }
}

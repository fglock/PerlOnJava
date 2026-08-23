package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class SystemOperatorAbsoluteJperlBatchTest {

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

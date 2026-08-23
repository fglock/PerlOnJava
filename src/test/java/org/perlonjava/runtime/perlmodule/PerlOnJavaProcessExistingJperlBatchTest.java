package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.perlonjava.runtime.operators.WindowsBatchArgvLauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOnJavaProcessExistingJperlBatchTest {

    @Test
    void existingJperlBatchWithQuotedSourceUsesLosslessTransport(
            @TempDir Path directory) throws Exception {
        Path launcher = Files.createFile(directory.resolve("jperl.bat"));
        String program = "print \"out\\n\"; warn \"err\\n\"";

        List<String> command = PerlOnJavaProcess.resolveChildCommand(
                List.of(launcher.toString(), "-e", program));

        assertTrue(command.contains(WindowsBatchArgvLauncher.class.getName()));
        assertFalse(command.contains("cmd.exe"));
    }
}

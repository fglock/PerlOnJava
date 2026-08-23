package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOnJavaProcessJperlBatchTest {

    @Test
    void windowsJperlEvalRemainsSeparateArgvInProcessService() {
        String program = "print \"out\\n\"; warn \"err\\n\"";
        List<String> command = PerlOnJavaProcess.resolveChildCommand(List.of(
                "D:\\missing-process-launcher\\jperl.bat", "-e", program));
        int main = command.indexOf("org.perlonjava.app.cli.Main");

        assertTrue(main > 0, "child command contains the PerlOnJava CLI main class");
        assertEquals(List.of("-e", program), command.subList(main + 1, command.size()));
        assertFalse(command.contains("cmd.exe"));
    }
}

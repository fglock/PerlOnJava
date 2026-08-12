package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class SystemOperatorWindowsCommandTest {

    @Test
    void quotedPerlProgramIsSplitWithoutInvokingCmd() {
        String command = "\"C:\\work tree\\jperl.bat\" -e "
                + "\"binmode STDOUT; print STDOUT pack q(H*), q(82a0ff)\"";

        assertEquals(List.of(
                "C:\\work tree\\jperl.bat",
                "-e",
                "binmode STDOUT; print STDOUT pack q(H*), q(82a0ff)"),
                SystemOperator.splitWindowsDirectCommandWords(command));
    }

    @Test
    void unquotedShellSyntaxStillRequiresCmd() {
        assertNull(SystemOperator.splitWindowsDirectCommandWords("echo value | findstr value"));
        assertNull(SystemOperator.splitWindowsDirectCommandWords("echo %PATH%"));
        assertNull(SystemOperator.splitWindowsDirectCommandWords("\"unterminated"));
    }
}

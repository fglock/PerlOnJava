package org.perlonjava;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.PerlExitException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that PerlExitException is thrown when Perl code calls exit(),
 * allowing library users to catch it and continue execution.
 * 
 * This addresses GitHub issue #291 where executePerlCode() was
 * terminating the JVM instead of returning.
 */
@Tag("unit")
public class PerlExitExceptionTest {

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
    }

    @Test
    void testExitZeroThrowsException() {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<test>";
        options.code = "exit 0;";

        PerlExitException thrown = assertThrows(PerlExitException.class, () -> {
            PerlLanguageProvider.executePerlCode(options, true);
        });

        assertEquals(0, thrown.getExitCode(), "exit 0 should have exit code 0");
    }

    @Test
    void testExitNonZeroThrowsException() {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<test>";
        options.code = "exit 42;";

        PerlExitException thrown = assertThrows(PerlExitException.class, () -> {
            PerlLanguageProvider.executePerlCode(options, true);
        });

        assertEquals(42, thrown.getExitCode(), "exit 42 should have exit code 42");
    }

    @Test
    void testExitAfterOutputThrowsException() {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<test>";
        options.code = "print 'hello'; exit 0;";

        PerlExitException thrown = assertThrows(PerlExitException.class, () -> {
            PerlLanguageProvider.executePerlCode(options, true);
        });

        assertEquals(0, thrown.getExitCode());
    }

    @Test
    void testScriptWithoutExitReturnsNormally() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<test>";
        options.code = "my $x = 1 + 1;";

        // Should not throw - script completes without calling exit()
        assertDoesNotThrow(() -> {
            PerlLanguageProvider.executePerlCode(options, true);
        });
    }

    @Test
    void testExceptionMessage() {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<test>";
        options.code = "exit 123;";

        PerlExitException thrown = assertThrows(PerlExitException.class, () -> {
            PerlLanguageProvider.executePerlCode(options, true);
        });

        assertEquals("exit 123", thrown.getMessage());
    }

    @Test
    void testExitInsideEvalNotCaught() {
        // In Perl, exit() should NOT be caught by eval{} - it should always exit
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<test>";
        options.code = "eval { exit 99; }; print 'should not reach here';";

        PerlExitException thrown = assertThrows(PerlExitException.class, () -> {
            PerlLanguageProvider.executePerlCode(options, true);
        });

        assertEquals(99, thrown.getExitCode(), "exit inside eval should still throw PerlExitException");
    }
}

package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
public class DebugSwitchCompatibilityTest {
    private PrintStream originalErr;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUp() throws Exception {
        PerlLanguageProvider.resetAll();
        originalErr = System.err;
        stderr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setErr(originalErr);
        PerlLanguageProvider.resetAll();
    }

    @Test
    void releasePerlDebugSwitchWarnsWithoutEnablingCompilerTrace() {
        CompilerOptions options = ArgumentParser.parseArguments(
                new String[] {"-Dt", "-e", "1"});

        assertEquals("t", options.debugFlags);
        assertFalse(options.debugEnabled);
        assertFalse(CompilerOptions.DEBUG_ENABLED);
        assertEquals(
                "Recompile perl with -DDEBUGGING to use -D switch (did you mean -d ?)\n",
                stderr.toString(StandardCharsets.UTF_8));
    }
}

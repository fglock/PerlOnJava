package org.perlonjava;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.ArgumentParser;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
public class CustomWarningCommandLineOverrideTest extends PerlRuntimeTestBase {
    private RuntimeIO originalStdout;
    private RuntimeIO originalStderr;
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
        originalStdout = RuntimeIO.getStdout();
        originalStderr = RuntimeIO.getStderr();
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        RuntimeIO.setStdout(new RuntimeIO(new StandardIO(stdout, true)));
        RuntimeIO.setStderr(new RuntimeIO(new StandardIO(stderr, false)));
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
    }

    @AfterEach
    void tearDown() {
        RuntimeIO.setStdout(originalStdout);
        RuntimeIO.setStderr(originalStderr);
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
        PerlLanguageProvider.resetAll();
    }

    @Test
    void jvmWOverridesRegisteredCustomCategorySuppression() throws Exception {
        assertCliGate("-W", "src/test/resources/unit/custom_warning_command_line_W.t");
    }

    @Test
    void interpreterWOverridesRegisteredCustomCategorySuppression() throws Exception {
        assertCliGate("--interpreter", "-W",
                "src/test/resources/unit/custom_warning_command_line_W.t");
    }

    private void assertCliGate(String... arguments) throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(arguments);
        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStdout().flush();
        RuntimeIO.getStderr().flush();

        String output = new String(stdout.toByteArray(), StandardCharsets.ISO_8859_1)
                + new String(stderr.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(output.contains(
                "ok 1 - -W overrides lexical custom-category suppression"), output);
        assertTrue(output.contains("ok 2 - -W warning retains its payload"), output);
        assertTrue(output.contains("1..2"), output);
        assertFalse(output.contains("not ok"), output);
        assertFalse(output.contains("Bail out!"), output);
    }
}

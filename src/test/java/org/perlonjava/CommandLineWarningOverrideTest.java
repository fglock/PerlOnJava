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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
public class CommandLineWarningOverrideTest {
    private RuntimeIO originalStdout;
    private RuntimeIO originalStderr;
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();

        originalStdout = RuntimeIO.stdout;
        originalStderr = RuntimeIO.stderr;
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();

        RuntimeIO.stdout = new RuntimeIO(new StandardIO(stdout, true));
        RuntimeIO.stderr = new RuntimeIO(new StandardIO(stderr, false));
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.stdout);
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.stderr);
    }

    @AfterEach
    void tearDown() {
        RuntimeIO.stdout = originalStdout;
        RuntimeIO.stderr = originalStderr;
        GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.stdout);
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.stderr);
        PerlLanguageProvider.resetAll();
    }

    @Test
    void xSuppressesLaterUseWarnings() throws Exception {
        assertEquals("", run("-X", "-e", "use warnings; my $b = $a + 0;"));
    }

    @Test
    void xSuppressesWarningsEnabledByUseVersion() throws Exception {
        assertEquals("", run("-X", "-e", "use 5.036; my $b = $a + 0;"));
    }

    @Test
    void wOverridesLaterNoWarnings() throws Exception {
        String output = run("-W", "-e", "no warnings; my $b = $a + 0;");

        assertTrue(output.contains("Use of uninitialized value"), output);
        assertTrue(output.contains("addition"), output);
    }

    private String run(String... args) throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(args);

        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.stdout.flush();
        RuntimeIO.stderr.flush();

        return new String(stdout.toByteArray(), StandardCharsets.ISO_8859_1)
                + new String(stderr.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}

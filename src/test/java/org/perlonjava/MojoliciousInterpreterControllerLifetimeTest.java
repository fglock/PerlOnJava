package org.perlonjava;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.io.StandardIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlExitException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MojoliciousInterpreterControllerLifetimeTest extends PerlRuntimeTestBase {
    @Test
    void controllerIsDestroyedAfterAsyncResponseFinishInInterpreter() throws Exception {
        PerlLanguageProvider.resetAll();
        String script;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "unit/mojolicious_controller_destroy_after_finish.t")) {
            Assumptions.assumeTrue(input != null,
                    "Mojolicious controller regression resource is unavailable");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        CompilerOptions options = new CompilerOptions();
        options.fileName = "unit/mojolicious_controller_destroy_after_finish.t";
        options.code = script;
        options.useInterpreter = true;
        RuntimeArray.push(options.inc, new RuntimeScalar("src/main/perl/lib"));
        RuntimeArray.push(options.inc, new RuntimeScalar("src/test/resources/unit/lib"));

        PrintStream originalOut = System.out;
        RuntimeIO originalRuntimeOut = RuntimeIO.getStdout();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8);
        try {
            RuntimeIO.setStdout(new RuntimeIO(new StandardIO(output, true)));
            GlobalVariable.getGlobalIO("main::STDOUT").setIO(RuntimeIO.getStdout());
            System.setOut(capturedOut);
            try {
                PerlLanguageProvider.executePerlCode(options, true);
            } catch (PerlExitException exit) {
                if (exit.getExitCode() != 0) throw exit;
            }
        } finally {
            RuntimeIO.setStdout(originalRuntimeOut);
            GlobalVariable.getGlobalIO("main::STDOUT").setIO(originalRuntimeOut);
            System.setOut(originalOut);
            capturedOut.close();
        }

        String tap = output.toString(StandardCharsets.UTF_8);
        if (tap.contains("1..0 # SKIP")) {
            Assumptions.abort("Mojolicious or loopback support is unavailable: " + tap.trim());
        }
        assertTrue(tap.contains("1..5"), "interpreter did not run all five assertions:\n" + tap);
        assertFalse(tap.lines().anyMatch(line -> line.trim().startsWith("not ok")), tap);
    }
}

package org.perlonjava;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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

@Tag("unit")
public class RegexSyntheticStartClassDebugTest extends PerlRuntimeTestBase {
    private RuntimeIO originalStderr;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUp() {
        PerlLanguageProvider.resetAll();
        originalStderr = RuntimeIO.getStderr();
        stderr = new ByteArrayOutputStream();
        RuntimeIO.setStderr(new RuntimeIO(new StandardIO(stderr, false)));
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
    }

    @AfterEach
    void tearDown() {
        RuntimeIO.setStderr(originalStderr);
        GlobalVariable.getGlobalIO("main::STDERR").setIO(RuntimeIO.getStderr());
        PerlLanguageProvider.resetAll();
    }

    @Test
    void reportsOnlyOptimizerProvenSyntheticStartClasses() throws Exception {
        CompilerOptions options = ArgumentParser.parseArguments(new String[] {
                "-e", "use re Debug => 'COMPILE'; "
                        + "qr/a?c/; qr/a?c/i; qr/[ab]?c/; qr/\\R?c/; "
                        + "qr/\\d?c/d; qr/\\w?c/l; qr/\\s?c/a; "
                        + "qr/[[:lower:]]?c/u; "
                        + "qr/abc/; qr/x+abc/; qr/(?:x|)abc/; qr/a?c|z/;"
        });

        PerlLanguageProvider.executePerlCode(options, true);
        RuntimeIO.getStderr().flush();
        String trace = stderr.toString(StandardCharsets.ISO_8859_1);

        assertEquals(8, occurrences(trace, "synthetic stclass"), trace);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0;
                index += needle.length()) {
            count++;
        }
        return count;
    }
}

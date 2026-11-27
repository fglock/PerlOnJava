package org.perlonjava.runtime.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StandardIOBufferingOrderTest {

    @Test
    void stderrIsVisibleBeforeBufferedStdoutOnASharedStream() {
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        StandardIO stdout = new StandardIO(merged, true);
        StandardIO stderr = new StandardIO(merged, false);

        stdout.write("stdout\n");
        stderr.write("stderr\n");
        stdout.flush();
        stderr.flush();

        assertEquals("stderr\nstdout\n", merged.toString(StandardCharsets.UTF_8));
    }
}

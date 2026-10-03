package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PerlOnJavaProcessOutputCaptureTest {

    @Test
    void drainsTheChildStreamAfterTheCapturedPrefixReachesItsLimit() {
        byte[] bytes = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        CountingInputStream input = new CountingInputStream(bytes);
        PerlOnJavaProcess.CapturedOutput output = new PerlOnJavaProcess.CapturedOutput(4);

        PerlOnJavaProcess.copyOutput(input, output, false, true);

        assertEquals(bytes.length, input.bytesRead);
        assertEquals(6, output.discardedBytes());
        assertEquals("abcd\n[PerlOnJava: captured output truncated; discarded 6 bytes]\n",
                output.asUtf8String());
    }

    @Test
    void productionCaptureLimitIsFinitePerStream() {
        assertTrue(PerlOnJavaProcess.MAX_CAPTURED_OUTPUT_BYTES_PER_STREAM
                <= 16 * 1024 * 1024);
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private int bytesRead;

        private CountingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int read = super.read(buffer, offset, length);
            if (read > 0) bytesRead += read;
            return read;
        }
    }
}

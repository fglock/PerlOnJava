package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ProcessOutputCaptureTest {

    @Test
    void retainsOnlyConfiguredPrefixWhileDrainingLargeOutput() throws Exception {
        int limit = 64 * 1024;
        long emitted = 16L * 1024 * 1024;
        InputStream largeOutput = new InputStream() {
            private long remaining = emitted;

            @Override
            public int read() {
                if (remaining == 0) return -1;
                remaining--;
                return 'x';
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                java.util.Arrays.fill(bytes, offset, offset + count, (byte) 'x');
                remaining -= count;
                return count;
            }
        };

        ProcessOutputCapture.Result result = ProcessOutputCapture.capture(largeOutput, limit);

        assertEquals(limit, result.bytes().length);
        assertTrue(result.truncated());
        assertEquals(emitted, result.totalBytes());
    }

    @Test
    void reportsOutputReaderFailures() {
        InputStream brokenOutput = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("reader broke");
            }
        };

        ProcessOutputCapture.Result result = ProcessOutputCapture.capture(brokenOutput, 128);

        assertTrue(result.error().contains("reader broke"));
    }
}

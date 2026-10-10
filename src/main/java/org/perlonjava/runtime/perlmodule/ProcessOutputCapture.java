package org.perlonjava.runtime.perlmodule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounded in-memory capture that continues draining the child process stream. */
final class ProcessOutputCapture {
    private final int limit;
    private final ByteArrayOutputStream captured;
    private long totalBytes;
    private String error = "";

    ProcessOutputCapture(int limit) {
        if (limit < 0) throw new IllegalArgumentException("capture limit must not be negative");
        this.limit = limit;
        this.captured = new ByteArrayOutputStream(Math.min(limit, 8192));
    }

    static Result capture(InputStream input, int limit) {
        ProcessOutputCapture capture = new ProcessOutputCapture(limit);
        capture.copy(input, null);
        return capture.result();
    }

    void copy(InputStream input, ChunkConsumer tee) {
        try (input) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                int keep = (int) Math.min(read, Math.max(0L, (long) limit - totalBytes));
                if (keep > 0) captured.write(buffer, 0, keep);
                totalBytes += read;
                if (tee != null) tee.accept(buffer, read);
            }
        } catch (IOException | RuntimeException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    private Result result() {
        return new Result(captured.toByteArray(), totalBytes, totalBytes > captured.size(), error);
    }

    String text() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    boolean truncated() {
        return totalBytes > captured.size();
    }

    String error() {
        return error;
    }

    record Result(byte[] bytes, long totalBytes, boolean truncated, String error) {
        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    interface ChunkConsumer {
        void accept(byte[] buffer, int length);
    }
}

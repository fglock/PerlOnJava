package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

public interface IOHandle {

    int SEEK_SET = 0;  // Seek from beginning of file
    int SEEK_CUR = 1;  // Seek from current position
    int SEEK_END = 2;  // Seek from end of file

    // Buffer for pushed-back codepoints
    ThreadLocal<Deque<Integer>> ungetBuffer = ThreadLocal.withInitial(ArrayDeque::new);

    RuntimeScalar write(String string);

    RuntimeScalar close();

    RuntimeScalar flush();

    // Default ungetc implementation - takes a Unicode codepoint
    default RuntimeScalar ungetc(int codepoint) {
        if (codepoint == -1) {
            return new RuntimeScalar(0); // Cannot push back EOF
        }
        if (!Character.isValidCodePoint(codepoint)) {
            return RuntimeIO.handleIOError("Invalid Unicode codepoint: " + codepoint);
        }
        ungetBuffer.get().addFirst(codepoint);
        return new RuntimeScalar(codepoint);
    }

    // Helper method to check if there are buffered codepoints
    default boolean hasBufferedChar() {
        return !ungetBuffer.get().isEmpty();
    }

    // Helper method to get next buffered codepoint
    default int getBufferedChar() {
        Deque<Integer> buffer = ungetBuffer.get();
        return buffer.isEmpty() ? -1 : buffer.removeFirst();
    }

    // Clear the unget buffer (useful for seek operations, etc.)
    default void clearUngetBuffer() {
        ungetBuffer.get().clear();
    }

    // Public read methods that check unget buffer first
    default RuntimeScalar read(int maxBytes) {
        return read(maxBytes, StandardCharsets.ISO_8859_1);
    }

    default RuntimeScalar read(int maxBytes, Charset charset) {
        Deque<Integer> buffer = ungetBuffer.get();

        if (buffer.isEmpty()) {
            // No buffered codepoints, delegate to actual read implementation
            return doRead(maxBytes, charset);
        }

        // We have buffered codepoints, combine them with new reads
        StringBuilder result = new StringBuilder();
        int bytesUsed = 0;

        // First, consume buffered codepoints
        while (!buffer.isEmpty() && bytesUsed < maxBytes) {
            int codepoint = buffer.removeFirst();
            String codepointStr = new String(Character.toChars(codepoint));
            byte[] codepointBytes = codepointStr.getBytes(charset);

            // Check if adding this codepoint would exceed maxBytes
            if (bytesUsed + codepointBytes.length > maxBytes) {
                // Put the codepoint back and break
                buffer.addFirst(codepoint);
                break;
            }

            result.append(codepointStr);
            bytesUsed += codepointBytes.length;
        }

        // If we still have byte capacity, read from source
        if (bytesUsed < maxBytes) {
            RuntimeScalar sourceRead = doRead(maxBytes - bytesUsed, charset);
            if (sourceRead.getDefinedBoolean()) {
                result.append(sourceRead);
            }
        }

        return new RuntimeScalar(result.toString());
    }

    // Protected method that subclasses should override for actual reading
    default RuntimeScalar doRead(int maxBytes, Charset charset) {
        return RuntimeIO.handleIOError("read operation is not supported.");
    }

    default RuntimeScalar fileno() {
        return RuntimeIO.handleIOError("fileno operation is not supported.");
    }

    default RuntimeScalar eof() {
        return RuntimeIO.handleIOError("eof operation is not supported.");
    }

    default RuntimeScalar tell() {
        return RuntimeIO.handleIOError("tell operation is not supported.");
    }

    // Socket-specific methods
    default RuntimeScalar bind(String address, int port) {
        return RuntimeIO.handleIOError("Bind operation is not supported.");
    }

    default RuntimeScalar connect(String address, int port) {
        return RuntimeIO.handleIOError("Connect operation is not supported.");
    }

    default RuntimeScalar listen(int backlog) {
        return RuntimeIO.handleIOError("Listen operation is not supported.");
    }

    default RuntimeScalar accept() {
        return RuntimeIO.handleIOError("Accept operation is not supported.");
    }

    default RuntimeScalar seek(long pos, int whence) {
        // Clear unget buffer when seeking, as position changes
        clearUngetBuffer();
        return RuntimeIO.handleIOError("Seek operation is not supported.");
    }

    default RuntimeScalar seek(long pos) {
        return seek(pos, IOHandle.SEEK_SET);
    }

    default RuntimeScalar truncate(long length) {
        return RuntimeIO.handleIOError("Truncate operation is not supported.");
    }

    // System-level I/O operations
    default RuntimeScalar sysread(int length) {
        return RuntimeIO.handleIOError("sysread operation is not supported.");
    }

    default RuntimeScalar syswrite(String data) {
        return RuntimeIO.handleIOError("syswrite operation is not supported.");
    }
}
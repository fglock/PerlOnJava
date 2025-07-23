package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeIO;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;
import org.perlonjava.runtime.PerlCompilerException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ScalarBackedIO implements IOHandle {
    private final RuntimeScalar backingScalar;
    private int position = 0;
    private boolean isEOF = false;
    private boolean isClosed = false;
    private CharsetDecoderHelper decoderHelper;

    public ScalarBackedIO(RuntimeScalar backingScalar) {
        this.backingScalar = backingScalar;
    }

    @Override
    public RuntimeScalar read(int maxBytes, Charset charset) {
        if (isClosed) {
            return RuntimeScalarCache.scalarUndef;
        }

        String content = backingScalar.toString();
        byte[] contentBytes = content.getBytes(StandardCharsets.ISO_8859_1);

        if (position >= contentBytes.length) {
            isEOF = true;
            return new RuntimeScalar("");
        }

        if (decoderHelper == null) {
            decoderHelper = new CharsetDecoderHelper();
        }

        int bytesToRead = Math.min(maxBytes, contentBytes.length - position);
        byte[] buffer = new byte[bytesToRead];
        System.arraycopy(contentBytes, position, buffer, 0, bytesToRead);

        String decoded = decoderHelper.decode(buffer, bytesToRead, charset);
        position += bytesToRead;

        if (position >= contentBytes.length) {
            isEOF = true;
        }

        return new RuntimeScalar(decoded);
    }

    @Override
    public RuntimeScalar write(String string) {
        if (isClosed) {
            return RuntimeScalarCache.scalarFalse;
        }

        String currentContent = backingScalar.toString();
        byte[] currentBytes = currentContent.getBytes(StandardCharsets.ISO_8859_1);
        byte[] newBytes = string.getBytes(StandardCharsets.ISO_8859_1);

        // Create new content array
        int newLength = Math.max(position + newBytes.length, currentBytes.length);
        byte[] resultBytes = new byte[newLength];

        // Copy existing content
        System.arraycopy(currentBytes, 0, resultBytes, 0, Math.min(position, currentBytes.length));

        // Write new content at position
        System.arraycopy(newBytes, 0, resultBytes, position, newBytes.length);

        // Copy any remaining content after write position
        if (position + newBytes.length < currentBytes.length) {
            System.arraycopy(currentBytes, position + newBytes.length,
                           resultBytes, position + newBytes.length,
                           currentBytes.length - position - newBytes.length);
        }

        // Update backing scalar
        backingScalar.set(new String(resultBytes, StandardCharsets.ISO_8859_1));
        position += newBytes.length;

        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar close() {
        isClosed = true;
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    @Override
    public RuntimeScalar flush() {
        // No buffering, so flush is a no-op
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar tell() {
        return new RuntimeScalar(position);
    }

    /**
     * Seeks to a new position based on the whence parameter.
     *
     * @param pos the offset in bytes
     * @param whence the reference point for the offset (SEEK_SET, SEEK_CUR, or SEEK_END)
     * @return RuntimeScalar with true on success
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        String content = backingScalar.toString();
        int contentLength = content.getBytes(StandardCharsets.ISO_8859_1).length;

        long newPosition;

        switch (whence) {
            case SEEK_SET: // from beginning
                newPosition = pos;
                break;
            case SEEK_CUR: // from current position
                newPosition = position + pos;
                break;
            case SEEK_END: // from end
                newPosition = contentLength + pos;
                break;
            default:
                return RuntimeIO.handleIOError("Invalid whence value: " + whence);
        }

        // Clamp position to valid range [0, contentLength]
        position = (int) Math.max(0, Math.min(newPosition, contentLength));

        isEOF = position >= contentLength;
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar truncate(long length) {
        if (length < 0) {
            return RuntimeScalarCache.scalarFalse;
        }

        String content = backingScalar.toString();
        byte[] contentBytes = content.getBytes(StandardCharsets.ISO_8859_1);

        if (length >= contentBytes.length) {
            // No truncation needed
            return RuntimeScalarCache.scalarTrue;
        }

        byte[] truncatedBytes = new byte[(int)length];
        System.arraycopy(contentBytes, 0, truncatedBytes, 0, (int)length);

        backingScalar.set(new String(truncatedBytes, StandardCharsets.ISO_8859_1));

        if (position > length) {
            position = (int)length;
            isEOF = true;
        }

        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar fileno() {
        // In-memory scalars don't have file descriptors
        return new RuntimeScalar(-1);
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        throw new PerlCompilerException("Can't bind on in-memory scalar handle");
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        throw new PerlCompilerException("Can't connect on in-memory scalar handle");
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        throw new PerlCompilerException("Can't listen on in-memory scalar handle");
    }

    @Override
    public RuntimeScalar accept() {
        throw new PerlCompilerException("Can't accept on in-memory scalar handle");
    }

    @Override
    public RuntimeScalar sysread(int length) {
        String content = backingScalar.toString();
        byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);

        int available = bytes.length - position;
        if (available <= 0) {
            // EOF
            return new RuntimeScalar("");
        }

        int toRead = Math.min(length, available);

        // Convert bytes to string representation
        StringBuilder result = new StringBuilder(toRead);
        for (int i = 0; i < toRead; i++) {
            result.append((char) (bytes[position + i] & 0xFF));
        }

        position += toRead;
        return new RuntimeScalar(result.toString());
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        // Get current content as bytes
        String content = backingScalar.toString();
        byte[] currentBytes = content.getBytes(StandardCharsets.ISO_8859_1);

        // Convert data to bytes
        byte[] dataBytes = new byte[data.length()];
        for (int i = 0; i < data.length(); i++) {
            dataBytes[i] = (byte) (data.charAt(i) & 0xFF);
        }

        // Calculate new size
        int newSize = Math.max(currentBytes.length, position + dataBytes.length);
        byte[] newBytes = new byte[newSize];

        // Copy existing data
        System.arraycopy(currentBytes, 0, newBytes, 0, currentBytes.length);

        // Write new data at position
        System.arraycopy(dataBytes, 0, newBytes, position, dataBytes.length);

        // Convert back to string and update scalar
        StringBuilder newContent = new StringBuilder(newSize);
        for (byte b : newBytes) {
            newContent.append((char) (b & 0xFF));
        }
        backingScalar.set(newContent.toString());

        position += dataBytes.length;
        return new RuntimeScalar(dataBytes.length);
    }
}
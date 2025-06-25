package org.perlonjava.io;

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

    @Override
    public RuntimeScalar seek(long pos) {
        String content = backingScalar.toString();
        int contentLength = content.getBytes(StandardCharsets.ISO_8859_1).length;

        if (pos < 0) {
            // Seek from end
            position = Math.max(0, contentLength + (int)pos);
        } else {
            position = (int)pos;
        }

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
    public RuntimeScalar getc() {
        if (isClosed || isEOF) {
            return RuntimeScalarCache.scalarUndef;
        }

        String content = backingScalar.toString();
        byte[] contentBytes = content.getBytes(StandardCharsets.ISO_8859_1);

        if (position >= contentBytes.length) {
            isEOF = true;
            return RuntimeScalarCache.scalarUndef;
        }

        char ch = (char)(contentBytes[position] & 0xFF);
        position++;

        return new RuntimeScalar(String.valueOf(ch));
    }

//    @Override
//    public RuntimeScalar stat() {
//        // Return basic stat info for in-memory scalar
//        // Most fields will be 0 or -1 for non-file handles
//        return new RuntimeScalar(-1);
//    }

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
}
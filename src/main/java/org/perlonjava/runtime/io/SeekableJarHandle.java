package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * IOHandle implementation for reading JAR resources with seek support.
 * <p>
 * JAR resources normally don't support seeking because they're accessed
 * via InputStream. This class reads the entire content into memory to
 * enable random access, which is necessary for modules like Module::Metadata
 * that need to seek back after detecting file encoding.
 * <p>
 * Memory usage: The entire file content is kept in memory. For large files
 * (> 10MB), this could be a concern, but most Perl modules are much smaller.
 */
public class SeekableJarHandle implements IOHandle {

    private final byte[] content;
    private int position = 0;
    private boolean isClosed = false;

    /**
     * Creates a SeekableJarHandle by reading the entire content from an InputStream.
     *
     * @param is The input stream to read from (will be read completely and closed)
     * @throws IOException if reading fails
     */
    public SeekableJarHandle(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        is.close();
        this.content = baos.toByteArray();
    }

    @Override
    public RuntimeScalar write(String string) {
        // JAR resources are read-only
        return RuntimeScalarCache.scalarFalse;
    }

    @Override
    public RuntimeScalar close() {
        isClosed = true;
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar flush() {
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar eof() {
        if (isClosed || position >= content.length) {
            return RuntimeScalarCache.scalarTrue;
        }
        return RuntimeScalarCache.scalarFalse;
    }

    @Override
    public RuntimeScalar tell() {
        return new RuntimeScalar(position);
    }

    @Override
    public RuntimeScalar seek(long pos, int whence) {
        // Clear unget buffer when seeking
        clearUngetBuffer();
        
        long newPos;
        switch (whence) {
            case IOHandle.SEEK_SET:
                newPos = pos;
                break;
            case IOHandle.SEEK_CUR:
                newPos = position + pos;
                break;
            case IOHandle.SEEK_END:
                newPos = content.length + pos;
                break;
            default:
                return RuntimeScalarCache.scalarFalse;
        }
        
        if (newPos < 0 || newPos > content.length) {
            return RuntimeScalarCache.scalarFalse;
        }
        
        position = (int) newPos;
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        if (isClosed || position >= content.length) {
            return new RuntimeScalar();
        }

        int bytesToRead = Math.min(maxBytes, content.length - position);
        String result = new String(content, position, bytesToRead, charset);
        position += bytesToRead;
        return new RuntimeScalar(result);
    }

    @Override
    public RuntimeScalar read(int maxBytes) {
        return read(maxBytes, StandardCharsets.ISO_8859_1);
    }

    @Override
    public RuntimeScalar sysread(int maxBytes) {
        if (isClosed || position >= content.length) {
            return new RuntimeScalar();
        }
        
        int bytesToRead = Math.min(maxBytes, content.length - position);
        byte[] buffer = new byte[bytesToRead];
        System.arraycopy(content, position, buffer, 0, bytesToRead);
        position += bytesToRead;
        
        // Return bytes as a string using ISO-8859-1 (preserves byte values)
        return new RuntimeScalar(new String(buffer, StandardCharsets.ISO_8859_1));
    }
}

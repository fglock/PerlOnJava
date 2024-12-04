package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

public class CustomFileChannel implements IOHandle {

    private final FileChannel fileChannel;
    private boolean isEOF;

    public CustomFileChannel(Path path, Set<StandardOpenOption> options) throws IOException {
        this.fileChannel = FileChannel.open(path, options);
        this.isEOF = false;
    }

    public CustomFileChannel(FileDescriptor fd, Set<StandardOpenOption> options) throws IOException {
        if (options.contains(StandardOpenOption.READ)) {
            this.fileChannel = new FileInputStream(fd).getChannel();
        } else if (options.contains(StandardOpenOption.WRITE)) {
            this.fileChannel = new FileOutputStream(fd).getChannel();
        } else {
            throw new IllegalArgumentException("Invalid options for FileDescriptor");
        }
        this.isEOF = false;
    }

    @Override
    public RuntimeScalar read(byte[] buffer) {
        try {
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            int bytesRead = fileChannel.read(byteBuffer);
            if (bytesRead == -1) {
                isEOF = true;
            }
            return new RuntimeScalar(bytesRead);
        } catch (IOException e) {
            handleIOException(e, "Read operation failed");
            return RuntimeScalarCache.scalarUndef;
        }
    }

    @Override
    public RuntimeScalar write(byte[] data) {
        try {
            ByteBuffer byteBuffer = ByteBuffer.wrap(data);
            int bytesWritten = fileChannel.write(byteBuffer);
            return new RuntimeScalar(bytesWritten);
        } catch (IOException e) {
            handleIOException(e, "Write operation failed");
            return scalarFalse;
        }
    }

    @Override
    public RuntimeScalar close() {
        try {
            fileChannel.close();
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Close operation failed");
            return scalarFalse;
        }
    }

    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    @Override
    public long tell() {
        try {
            return fileChannel.position();
        } catch (IOException e) {
            handleIOException(e, "Tell operation failed");
            return -1;
        }
    }

    @Override
    public RuntimeScalar seek(long pos) {
        try {
            fileChannel.position(pos);
            isEOF = false;
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Seek operation failed");
            return scalarFalse;
        }
    }

    @Override
    public RuntimeScalar flush() {
        try {
            fileChannel.force(false);
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Flush operation failed");
            return scalarFalse;
        }
    }

    @Override
    public RuntimeScalar fileno() {
        return RuntimeScalarCache.scalarUndef; // FileChannel does not expose a file descriptor
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        throw new UnsupportedOperationException("Bind operation is not supported for file channels");
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        throw new UnsupportedOperationException("Connect operation is not supported for file channels");
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        throw new UnsupportedOperationException("Listen operation is not supported for file channels");
    }

    @Override
    public RuntimeScalar accept() {
        throw new UnsupportedOperationException("Accept operation is not supported for file channels");
    }

    @Override
    public RuntimeScalar getc() {
        try {
            ByteBuffer singleByteBuffer = ByteBuffer.allocate(1);
            int bytesRead = fileChannel.read(singleByteBuffer);
            if (bytesRead == -1) {
                isEOF = true;
                return RuntimeScalarCache.scalarUndef;
            }
            singleByteBuffer.flip();
            return new RuntimeScalar(singleByteBuffer.get() & 0xFF);
        } catch (IOException e) {
            handleIOException(e, "getc operation failed");
            return RuntimeScalarCache.scalarUndef;
        }
    }

    public RuntimeScalar truncate(long length) {
        if (length < 0) {
            throw new IllegalArgumentException("Invalid arguments for truncate operation.");
        }
        try {
            fileChannel.truncate(length);
            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Truncate operation failed");
            return scalarFalse;
        }
    }
}

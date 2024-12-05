package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;

public class StandardIO implements IOHandle {
    public static final int STDIN_FILENO = 0;
    public static final int STDOUT_FILENO = 1;
    public static final int STDERR_FILENO = 2;
    private static final int LOCAL_BUFFER_SIZE = 1024; // Define the size of the local buffer

    private final int fileno;
    private final byte[] localBuffer = new byte[LOCAL_BUFFER_SIZE];
    private final BlockingQueue<byte[]> printQueue = new LinkedBlockingQueue<>();
    private final Thread printThread;
    private final Object flushLock = new Object();
    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedOutputStream bufferedOutputStream;
    private boolean isEOF;
    private int bufferPosition = 0;
    private boolean flushInProgress = false;

    public StandardIO(InputStream inputStream) {
        this.inputStream = inputStream;
        this.fileno = STDIN_FILENO;
        this.printThread = null; // No print thread needed for input
    }

    public StandardIO(OutputStream outputStream, boolean isStdout) {
        this.outputStream = outputStream;
        this.bufferedOutputStream = new BufferedOutputStream(outputStream);
        this.fileno = isStdout ? STDOUT_FILENO : STDERR_FILENO;

        // Start a daemon thread to handle printing
        printThread = new Thread(() -> {
            try {
                while (true) {
                    byte[] data = printQueue.take();
                    if (data.length == 0) break; // Exit signal
                    bufferedOutputStream.write(data);
                    bufferedOutputStream.flush();
                    synchronized (flushLock) {
                        if (flushInProgress && printQueue.isEmpty()) {
                            flushInProgress = false;
                            flushLock.notifyAll();
                        }
                    }
                }
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        printThread.setDaemon(true); // Set the thread as a daemon
        printThread.start();
    }

    @Override
    public RuntimeScalar write(byte[] data) {
        synchronized (localBuffer) {
            int dataLength = data.length;
            int spaceLeft = LOCAL_BUFFER_SIZE - bufferPosition;

            if (dataLength > spaceLeft) {
                // Fill the remaining space in the local buffer
                System.arraycopy(data, 0, localBuffer, bufferPosition, spaceLeft);
                bufferPosition += spaceLeft;
                flushLocalBuffer();

                // Write remaining data directly if it exceeds buffer size
                int remainingDataLength = dataLength - spaceLeft;
                if (remainingDataLength > LOCAL_BUFFER_SIZE) {
                    printQueue.offer(data);
                } else {
                    System.arraycopy(data, spaceLeft, localBuffer, 0, remainingDataLength);
                    bufferPosition = remainingDataLength;
                }
            } else {
                System.arraycopy(data, 0, localBuffer, bufferPosition, dataLength);
                bufferPosition += dataLength;
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar flush() {
        synchronized (localBuffer) {
            flushLocalBuffer();
        }
        synchronized (flushLock) {
            flushInProgress = true;
            while (flushInProgress) {
                try {
                    flushLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    private void flushLocalBuffer() {
        if (bufferPosition > 0) {
            byte[] dataToFlush = new byte[bufferPosition];
            System.arraycopy(localBuffer, 0, dataToFlush, 0, bufferPosition);
            printQueue.offer(dataToFlush);
            bufferPosition = 0;
        }
    }

    @Override
    public RuntimeScalar close() {
        // Signal the print thread to exit
        if (printThread != null) {
            printQueue.offer(new byte[0]);
            try {
                printThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }


    @Override
    public RuntimeScalar read(byte[] buffer) {
        try {
            if (inputStream != null) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    isEOF = true;
                }
                return new RuntimeScalar(bytesRead);
            }
        } catch (IOException e) {
            return handleIOException(e, "Read operation failed");
        }
        return RuntimeScalarCache.scalarUndef;
    }


    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    @Override
    public RuntimeScalar tell() {
        throw new UnsupportedOperationException("Tell operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar seek(long pos) {
        throw new UnsupportedOperationException("Seek operation is not supported for standard streams");
    }


    @Override
    public RuntimeScalar fileno() {
        return new RuntimeScalar(fileno);
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        throw new UnsupportedOperationException("Bind operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        throw new UnsupportedOperationException("Connect operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        throw new UnsupportedOperationException("Listen operation is not supported for standard streams");
    }

    @Override
    public RuntimeScalar accept() {
        throw new UnsupportedOperationException("Accept operation is not supported for standard streams");
    }

    public RuntimeScalar getc() {
        try {
            if (inputStream != null) {
                int byteRead = inputStream.read();
                if (byteRead == -1) {
                    isEOF = true;
                    return RuntimeScalarCache.scalarUndef; // Return undefined if EOF is reached
                }
                return new RuntimeScalar(byteRead);
            }
        } catch (IOException e) {
            handleIOException(e, "getc operation failed");
        }
        return RuntimeScalarCache.scalarUndef;
    }

    public RuntimeScalar truncate(long length) {
        throw new UnsupportedOperationException("Truncate operation is not supported.");
    }
}

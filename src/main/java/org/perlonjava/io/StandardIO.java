package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;

/**
 * The StandardIO class implements the IOHandle interface and provides functionality for
 * handling standard input and output operations. It uses a separate thread to manage
 * writing to standard output (STDOUT) or standard error (STDERR), allowing the main
 * program to continue executing without being blocked by IO operations.
 */
public class StandardIO implements IOHandle {
    // Standard file descriptors
    public static final int STDIN_FILENO = 0;
    public static final int STDOUT_FILENO = 1;
    public static final int STDERR_FILENO = 2;

    // Configuration constants
    private static final int LOCAL_BUFFER_SIZE = 1024;
    private static final long FLUSH_TIMEOUT_MS = 1000;

    // Core instance fields
    private final int fileno;
    private final byte[] localBuffer = new byte[LOCAL_BUFFER_SIZE];
    private final BlockingQueue<byte[]> printQueue = new LinkedBlockingQueue<>();
    private final Thread printThread;
    private final Object flushLock = new Object();
    private volatile boolean shutdownRequested = false;

    // Stream handling
    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedOutputStream bufferedOutputStream;
    private boolean isEOF;
    private int bufferPosition = 0;

    /**
     * Constructor for creating a StandardIO instance for reading from an input stream.
     *
     * @param inputStream The input stream to read from.
     */
    public StandardIO(InputStream inputStream) {
        this.inputStream = inputStream;
        this.fileno = STDIN_FILENO;
        this.printThread = null; // No print thread needed for input
    }

    /**
     * Constructor for creating a StandardIO instance for writing to an output stream.
     *
     * @param outputStream The output stream to write to.
     * @param isStdout    Flag indicating if the output stream is standard output.
     */
    public StandardIO(OutputStream outputStream, boolean isStdout) {
        this.outputStream = outputStream;
        this.bufferedOutputStream = new BufferedOutputStream(outputStream);
        this.fileno = isStdout ? STDOUT_FILENO : STDERR_FILENO;

        // Initialize and start the print thread for asynchronous output processing
        printThread = new Thread(() -> {
            try {
                while (!shutdownRequested || !printQueue.isEmpty()) {
                    // Poll with timeout to allow checking shutdown condition
                    byte[] data = printQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (data == null) continue;
                    if (data.length == 0) break; // Empty array signals shutdown

                    bufferedOutputStream.write(data);
                    bufferedOutputStream.flush();

                    // Notify any waiting flush operations
                    synchronized (flushLock) {
                        flushLock.notifyAll();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Stream errors handled silently
            }
        });
        printThread.setDaemon(true);
        printThread.start();
    }

    /**
     * Writes data to the output stream. If the data exceeds the local buffer size,
     * it is added to the print queue for the print thread to handle.
     *
     * @param data The data to write.
     * @return A RuntimeScalar indicating the success of the operation.
     */
    @Override
    public RuntimeScalar write(byte[] data) {
        synchronized (localBuffer) {
            int dataLength = data.length;
            int spaceLeft = LOCAL_BUFFER_SIZE - bufferPosition;

            if (dataLength > spaceLeft) {
                flushLocalBuffer();
                if (dataLength > LOCAL_BUFFER_SIZE) {
                    // Large writes go directly to queue
                    printQueue.offer(data.clone());
                } else {
                    // Smaller writes use the local buffer
                    System.arraycopy(data, 0, localBuffer, 0, dataLength);
                    bufferPosition = dataLength;
                }
            } else {
                // Accumulate in local buffer
                System.arraycopy(data, 0, localBuffer, bufferPosition, dataLength);
                bufferPosition += dataLength;
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    /**
     * Helper method to flush the local buffer contents to the print queue.
     */
    private void flushLocalBuffer() {
        if (bufferPosition > 0) {
            byte[] data = new byte[bufferPosition];
            System.arraycopy(localBuffer, 0, data, 0, bufferPosition);
            printQueue.offer(data);
            bufferPosition = 0;
        }
    }

    /**
     * Flushes the local buffer and waits for the print queue to be empty.
     * Uses a timeout to prevent indefinite blocking.
     *
     * @return A RuntimeScalar indicating the success of the operation.
     */
    @Override
    public RuntimeScalar flush() {
        synchronized (localBuffer) {
            flushLocalBuffer();
        }

        synchronized (flushLock) {
            if (!printQueue.isEmpty()) {
                try {
                    flushLock.wait(FLUSH_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    /**
     * Closes the IO handle by flushing pending data and signaling the print thread to exit.
     *
     * @return A RuntimeScalar indicating the success of the operation.
     */
    @Override
    public RuntimeScalar close() {
        flush();
        shutdownRequested = true;
        if (printThread != null) {
            printQueue.offer(new byte[0]); // Signal thread to exit
            try {
                printThread.join(FLUSH_TIMEOUT_MS);
                bufferedOutputStream.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                return handleIOException(e, "Close operation failed");
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    /**
     * Reads data from the input stream into the provided buffer.
     *
     * @param buffer The buffer to read data into.
     * @return A RuntimeScalar representing the number of bytes read.
     */
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

    /**
     * Checks if the end of the input stream has been reached.
     *
     * @return A RuntimeScalar indicating if EOF has been reached.
     */
    @Override
    public RuntimeScalar eof() {
        return new RuntimeScalar(isEOF);
    }

    /**
     * Returns the file descriptor associated with this IO handle.
     *
     * @return A RuntimeScalar representing the file descriptor.
     */
    @Override
    public RuntimeScalar fileno() {
        return new RuntimeScalar(fileno);
    }

    /**
     * Reads a single byte from the input stream.
     *
     * @return A RuntimeScalar representing the byte read, or undefined if EOF is reached.
     */
    public RuntimeScalar getc() {
        try {
            if (inputStream != null) {
                int byteRead = inputStream.read();
                if (byteRead == -1) {
                    isEOF = true;
                    return RuntimeScalarCache.scalarUndef;
                }
                return new RuntimeScalar(byteRead);
            }
        } catch (IOException e) {
            handleIOException(e, "getc operation failed");
        }
        return RuntimeScalarCache.scalarUndef;
    }
}

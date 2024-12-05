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

/**
 * The StandardIO class implements the IOHandle interface and provides functionality for
 * handling standard input and output operations. It uses a separate thread to manage
 * writing to standard output (STDOUT) or standard error (STDERR), allowing the main
 * program to continue executing without being blocked by IO operations.
 */
public class StandardIO implements IOHandle {
    // Constants representing the file descriptors for standard input, output, and error
    public static final int STDIN_FILENO = 0;
    public static final int STDOUT_FILENO = 1;
    public static final int STDERR_FILENO = 2;

    // Size of the local buffer used for temporarily storing data before writing
    private static final int LOCAL_BUFFER_SIZE = 1024;

    // File descriptor for this IO handle
    private final int fileno;

    // Local buffer for temporarily storing data
    private final byte[] localBuffer = new byte[LOCAL_BUFFER_SIZE];

    // Queue for managing data to be printed by the print thread
    private final BlockingQueue<byte[]> printQueue = new LinkedBlockingQueue<>();

    // Thread responsible for writing data to the output stream
    private final Thread printThread;

    // Lock object used for synchronizing flush operations
    private final Object flushLock = new Object();

    // Input and output streams for reading and writing data
    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedOutputStream bufferedOutputStream;

    // Flag indicating if the end of the input stream has been reached
    private boolean isEOF;

    // Position in the local buffer where the next byte will be written
    private int bufferPosition = 0;

    // Flag indicating if a flush operation is in progress
    private boolean flushInProgress = false;

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
     * @param isStdout     Flag indicating if the output stream is standard output.
     */
    public StandardIO(OutputStream outputStream, boolean isStdout) {
        this.outputStream = outputStream;
        this.bufferedOutputStream = new BufferedOutputStream(outputStream);
        this.fileno = isStdout ? STDOUT_FILENO : STDERR_FILENO;

        // Start a daemon thread to handle printing
        printThread = new Thread(() -> {
            try {
                while (true) {
                    // Take data from the queue and write it to the buffered output stream
                    byte[] data = printQueue.take();
                    if (data.length == 0) break; // Exit signal
                    bufferedOutputStream.write(data);
                    bufferedOutputStream.flush();
                    synchronized (flushLock) {
                        // Notify waiting threads if a flush operation is complete
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

    /**
     * Flushes the local buffer and waits for the print queue to be empty.
     *
     * @return A RuntimeScalar indicating the success of the operation.
     */
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

    /**
     * Flushes the local buffer by adding its contents to the print queue.
     */
    private void flushLocalBuffer() {
        if (bufferPosition > 0) {
            byte[] dataToFlush = new byte[bufferPosition];
            System.arraycopy(localBuffer, 0, dataToFlush, 0, bufferPosition);
            printQueue.offer(dataToFlush);
            bufferPosition = 0;
        }
    }

    /**
     * Closes the IO handle by signaling the print thread to exit.
     *
     * @return A RuntimeScalar indicating the success of the operation.
     */
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
                    return RuntimeScalarCache.scalarUndef; // Return undefined if EOF is reached
                }
                return new RuntimeScalar(byteRead);
            }
        } catch (IOException e) {
            handleIOException(e, "getc operation failed");
        }
        return RuntimeScalarCache.scalarUndef;
    }
}

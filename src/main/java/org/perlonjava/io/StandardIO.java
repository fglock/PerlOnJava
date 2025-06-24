package org.perlonjava.io;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;

/**
 * The StandardIO class implements the IOHandle interface and provides functionality for
 * handling standard input and output operations. It uses a separate thread to manage
 * writing to standard output (STDOUT) or standard error (STDERR), allowing the main
 * program to continue executing without being blocked by IO operations.
 */
public class StandardIO implements IOHandle {
    public static final int STDIN_FILENO = 0;
    public static final int STDOUT_FILENO = 1;
    public static final int STDERR_FILENO = 2;

    private static final int LOCAL_BUFFER_SIZE = 1024;

    private final int fileno;
    private final byte[] localBuffer = new byte[LOCAL_BUFFER_SIZE];
    private final BlockingQueue<QueueItem> printQueue = new LinkedBlockingQueue<>();
    private final Thread printThread;
    private final Object flushLock = new Object();
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final AtomicInteger lastPrinted = new AtomicInteger(-1);
    private volatile boolean shutdownRequested = false;

    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedOutputStream bufferedOutputStream;
    private boolean isEOF;
    private int bufferPosition = 0;

    public StandardIO(InputStream inputStream) {
        this.inputStream = inputStream;
        this.fileno = STDIN_FILENO;
        this.printThread = null;
    }

    public StandardIO(OutputStream outputStream, boolean isStdout) {
        this.outputStream = outputStream;
        this.bufferedOutputStream = new BufferedOutputStream(outputStream);
        this.fileno = isStdout ? STDOUT_FILENO : STDERR_FILENO;

        printThread = new Thread(() -> {
            try {
                while (!shutdownRequested || !printQueue.isEmpty()) {
                    QueueItem item = printQueue.take();
                    if (item.data.length == 0) continue;

                    // Wait for our turn
                    while (item.seq != lastPrinted.get() + 1) {
                        if (shutdownRequested) return;
                        Thread.yield();
                    }

                    try {
                        bufferedOutputStream.write(item.data);
                        bufferedOutputStream.flush();
                        lastPrinted.set(item.seq);

                        synchronized (flushLock) {
                            flushLock.notifyAll();
                        }
                    } catch (IOException e) {

                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        printThread.setDaemon(true);
        printThread.start();
    }

    @Override
    public RuntimeScalar write(String string) {
        var data = string.getBytes();
        synchronized (localBuffer) {
            int dataLength = data.length;
            int spaceLeft = LOCAL_BUFFER_SIZE - bufferPosition;

            if (dataLength > spaceLeft) {
                if (bufferPosition > 0) {
                    byte[] firstPart = new byte[bufferPosition];
                    System.arraycopy(localBuffer, 0, firstPart, 0, bufferPosition);
                    queueWrite(firstPart);
                    bufferPosition = 0;
                }

                if (dataLength > LOCAL_BUFFER_SIZE) {
                    queueWrite(data);
                } else {
                    System.arraycopy(data, 0, localBuffer, 0, dataLength);
                    bufferPosition = dataLength;
                }
            } else {
                System.arraycopy(data, 0, localBuffer, bufferPosition, dataLength);
                bufferPosition += dataLength;
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    private void queueWrite(byte[] data) {
        int seq = sequence.getAndIncrement();

        printQueue.offer(new QueueItem(data.clone(), seq));
    }

    @Override
    public RuntimeScalar flush() {
        synchronized (localBuffer) {
            if (bufferPosition > 0) {
                byte[] data = new byte[bufferPosition];
                System.arraycopy(localBuffer, 0, data, 0, bufferPosition);
                queueWrite(data);
                bufferPosition = 0;
            }
        }

        int currentSeq = sequence.get() - 1;
        if (currentSeq >= 0) {
            synchronized (flushLock) {
                while (lastPrinted.get() < currentSeq) {
                    try {
                        flushLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        try {
            bufferedOutputStream.flush();
        } catch (IOException e) {
            return handleIOException(e, "Flush operation failed");
        }

        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar close() {
        flush();
        shutdownRequested = true;
        if (printThread != null) {
            try {
                printThread.join();
                bufferedOutputStream.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                return handleIOException(e, "Close operation failed");
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
    public RuntimeScalar fileno() {
        return new RuntimeScalar(fileno);
    }

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

    private static class QueueItem {
        final byte[] data;
        final int seq;

        QueueItem(byte[] data, int seq) {
            this.data = data;
            this.seq = seq;
        }
    }
}

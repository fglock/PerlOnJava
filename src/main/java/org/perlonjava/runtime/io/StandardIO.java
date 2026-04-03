package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeIO.handleIOException;

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

    private static final int BUFFER_SIZE = 65536;  // 64KB buffer for better throughput

    private final int fileno;
    private final Object writeLock = new Object();

    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedOutputStream bufferedOutputStream;
    private boolean isEOF;
    private CharsetDecoderHelper decoderHelper;

    public StandardIO(InputStream inputStream) {
        this.inputStream = inputStream;
        this.fileno = STDIN_FILENO;
    }

    public StandardIO(OutputStream outputStream, boolean isStdout) {
        this.outputStream = outputStream;
        this.bufferedOutputStream = new BufferedOutputStream(outputStream, BUFFER_SIZE);
        this.fileno = isStdout ? STDOUT_FILENO : STDERR_FILENO;
    }

    @Override
    public RuntimeScalar write(String string) {
        if (bufferedOutputStream == null) {
            return RuntimeScalarCache.scalarFalse;
        }
        try {
            synchronized (writeLock) {
                // Check for wide characters (codepoint > 255)
                // Perl 5 auto-upgrades to UTF-8 for wide chars
                boolean hasWideChars = false;
                for (int i = 0; i < string.length(); i++) {
                    if (string.charAt(i) > 255) {
                        hasWideChars = true;
                        break;
                    }
                }
                byte[] data = hasWideChars
                        ? string.getBytes(StandardCharsets.UTF_8)
                        : string.getBytes(StandardCharsets.ISO_8859_1);
                bufferedOutputStream.write(data);
            }
            return RuntimeScalarCache.scalarTrue;
        } catch (IOException e) {
            if (System.getenv("JPERL_IO_DEBUG") != null) {
                System.err.println("[JPERL_IO_DEBUG] StandardIO write IOException fileno=" + fileno +
                        " message=" + e.getMessage());
            }
            return RuntimeScalarCache.scalarFalse;
        }
    }

    @Override
    public RuntimeScalar flush() {
        if (bufferedOutputStream == null) {
            return RuntimeScalarCache.scalarTrue;
        }

        try {
            synchronized (writeLock) {
                bufferedOutputStream.flush();
            }
        } catch (IOException e) {
            return handleIOException(e, "Flush operation failed");
        }

        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar close() {
        // Don't actually close STDIN, STDOUT, or STDERR - they should remain open
        // Just return success to indicate the operation completed
        if (fileno == STDIN_FILENO || fileno == STDOUT_FILENO || fileno == STDERR_FILENO) {
            flush();  // Flush any pending output
            return RuntimeScalarCache.scalarTrue;
        }

        flush();
        if (bufferedOutputStream != null) {
            try {
                bufferedOutputStream.close();
            } catch (IOException e) {
                return handleIOException(e, "Close operation failed");
            }
        }
        return RuntimeScalarCache.scalarTrue;
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        try {
            if (inputStream != null) {
                if (decoderHelper == null) {
                    decoderHelper = new CharsetDecoderHelper();
                }

                StringBuilder result = new StringBuilder();

                // Keep reading while we need more data for multi-byte sequences
                do {
                    byte[] buffer = new byte[maxBytes];
                    int bytesRead = inputStream.read(buffer);

                    if (bytesRead == -1) {
                        isEOF = true;
                        // Decode any remaining bytes on EOF
                        String decoded = decoderHelper.decode(buffer, bytesRead, charset);
                        if (!decoded.isEmpty()) {
                            result.append(decoded);
                        }
                        break;
                    }

                    String decoded = decoderHelper.decode(buffer, bytesRead, charset);
                    result.append(decoded);

                    // Continue if we need more data to decode a complete character
                } while (decoderHelper.needsMoreData() && !isEOF);

                return new RuntimeScalar(result.toString());
            }
        } catch (IOException e) {
            return handleIOException(e, "Read operation failed");
        }
        return new RuntimeScalar("");  // Return empty string instead of undef
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

    @Override
    public RuntimeScalar sysread(int length) {
        if (inputStream != null) {
            try {
                byte[] buffer = new byte[length];
                int bytesRead = inputStream.read(buffer);

                if (bytesRead == -1) {
                    // EOF
                    return new RuntimeScalar("");
                }

                // Convert bytes to string representation
                StringBuilder result = new StringBuilder(bytesRead);
                for (int i = 0; i < bytesRead; i++) {
                    result.append((char) (buffer[i] & 0xFF));
                }

                return new RuntimeScalar(result.toString());
            } catch (IOException e) {
                getGlobalVariable("main::!").set(e.getMessage());
                return new RuntimeScalar(); // undef
            }
        }
        return RuntimeIO.handleIOError("sysread operation not supported on output stream");
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        if (outputStream != null) {
            try {
                // Convert string to bytes
                byte[] bytes = new byte[data.length()];
                for (int i = 0; i < data.length(); i++) {
                    bytes[i] = (byte) (data.charAt(i) & 0xFF);
                }

                outputStream.write(bytes);
//                if (autoflush) {
//                    outputStream.flush();
//                }

                return new RuntimeScalar(bytes.length);
            } catch (IOException e) {
                getGlobalVariable("main::!").set(e.getMessage());
                return new RuntimeScalar(); // undef
            }
        }
        return RuntimeIO.handleIOError("syswrite operation not supported on input stream");
    }
}

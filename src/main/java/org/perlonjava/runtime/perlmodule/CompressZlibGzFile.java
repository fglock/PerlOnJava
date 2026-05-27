package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Implements the Compress::Zlib::gzFile class for gzip file I/O.
 * This provides gzread, gzwrite, gzreadline, gzeof, gzclose methods
 * on objects returned by Compress::Zlib::gzopen.
 */
public class CompressZlibGzFile extends PerlModuleBase {

    private static final String STREAM_KEY = "_stream";
    private static final String MODE_KEY = "_mode";
    private static final String EOF_KEY = "_eof";
    private static final String PUSHBACK_KEY = "_pushback";
    private static final String POS_KEY = "_pos";

    public CompressZlibGzFile() {
        super("Compress::Zlib::gzFile", false);
    }

    public static void initialize() {
        CompressZlibGzFile gzFile = new CompressZlibGzFile();
        try {
            gzFile.registerMethod("gzread", null);
            gzFile.registerMethod("gzwrite", null);
            gzFile.registerMethod("gzreadline", null);
            gzFile.registerMethod("gzeof", null);
            gzFile.registerMethod("gztell", null);
            gzFile.registerMethod("gzseek", null);
            gzFile.registerMethod("gzflush", null);
            gzFile.registerMethod("gzsetparams", null);
            gzFile.registerMethod("gzclose", null);
            gzFile.registerMethod("gzerror", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Compress::Zlib::gzFile method: " + e.getMessage());
        }
    }

    /**
     * $gz->gzread($buffer [, $size])
     * Reads $size bytes into $buffer. Returns bytes read, 0 on EOF, -1 on error.
     * Modifies $_[1] (the buffer argument) in-place via @_ aliasing.
     */
    public static RuntimeList gzread(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(-2).getList(); // Z_STREAM_ERROR
        }

        RuntimeHash self = args.get(0).hashDeref();
        int nbytes = args.size() >= 3 ? args.get(2).getInt() : 4096;

        RuntimeScalar streamScalar = self.get(STREAM_KEY);
        if (streamScalar == null || streamScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(streamScalar.value instanceof InputStream is)) {
            return new RuntimeScalar(-2).getList(); // Z_STREAM_ERROR
        }

        try {
            byte[] buf = new byte[nbytes];
            int totalRead = 0;
            while (totalRead < nbytes) {
                int n = is.read(buf, totalRead, nbytes - totalRead);
                if (n == -1) {
                    self.put(EOF_KEY, new RuntimeScalar(1));
                    break;
                }
                totalRead += n;
            }

            if (totalRead == 0) {
                // Modify the buffer argument in-place
                args.get(1).set("");
                return new RuntimeScalar(0).getList();
            }

            // Modify the buffer argument in-place via @_ alias
            byte[] data = new byte[totalRead];
            System.arraycopy(buf, 0, data, 0, totalRead);
            args.get(1).set(new RuntimeScalar(data));
            addPosition(self, totalRead);
            if (totalRead == nbytes && is instanceof PushbackInputStream pushback) {
                int next = pushback.read();
                if (next == -1) {
                    self.put(EOF_KEY, new RuntimeScalar(1));
                } else {
                    pushback.unread(next);
                }
            }
            return new RuntimeScalar(totalRead).getList();
        } catch (IOException e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    /**
     * $gz->gzwrite($data)
     * Writes $data to the gzip file. Returns bytes written, 0 on error.
     */
    public static RuntimeList gzwrite(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            if (!args.isEmpty()) {
                RuntimeHash self = args.get(0).hashDeref();
                RuntimeScalar mode = self.get(MODE_KEY);
                if (mode != null && mode.toString().startsWith("r")) {
                    return new RuntimeScalar(-2).getList(); // Z_STREAM_ERROR
                }
            }
            return new RuntimeScalar(0).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String data = args.get(1).toString();
        StringParser.assertNoWideCharacters(data, "gzwrite");

        RuntimeScalar streamScalar = self.get(STREAM_KEY);
        if (streamScalar == null || streamScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(streamScalar.value instanceof OutputStream os)) {
            return new RuntimeScalar(-2).getList(); // Z_STREAM_ERROR
        }

        try {
            byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
            os.write(bytes);
            addPosition(self, bytes.length);
            return new RuntimeScalar(bytes.length).getList();
        } catch (IOException e) {
            return new RuntimeScalar(0).getList();
        }
    }

    /**
     * $gz->gzreadline($line)
     * Reads a line from the gzip file. Returns bytes read, 0 on EOF, -1 on error.
     * Modifies $_[1] (the line argument) in-place via @_ aliasing.
     */
    public static RuntimeList gzreadline(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return new RuntimeScalar(-1).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        RuntimeScalar streamScalar = self.get(STREAM_KEY);
        if (streamScalar == null || streamScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(streamScalar.value instanceof InputStream is)) {
            return new RuntimeScalar(-1).getList();
        }

        try {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                line.append((char) c);
                if (c == '\n') {
                    break;
                }
            }

            if (line.isEmpty()) {
                self.put(EOF_KEY, new RuntimeScalar(1));
                args.get(1).set("");
                return new RuntimeScalar(0).getList();
            }

            String result = line.toString();
            // Modify the line argument in-place via @_ alias
            args.get(1).set(new RuntimeScalar(result.getBytes(StandardCharsets.ISO_8859_1)));
            addPosition(self, result.length());
            if (c == -1) {
                self.put(EOF_KEY, new RuntimeScalar(1));
            } else if (c == '\n' && is instanceof PushbackInputStream pushback) {
                int next = pushback.read();
                if (next == -1) {
                    self.put(EOF_KEY, new RuntimeScalar(1));
                } else {
                    pushback.unread(next);
                }
            }
            return new RuntimeScalar(result.length()).getList();
        } catch (IOException e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    /**
     * $gz->gzeof()
     * Returns 1 if at end of file, 0 otherwise.
     */
    public static RuntimeList gzeof(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar eofScalar = self.get(EOF_KEY);
        int eof = (eofScalar != null) ? eofScalar.getInt() : 0;
        return new RuntimeScalar(eof).getList();
    }

    public static RuntimeList gztell(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(-1).getList();
        }
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar pos = self.get(POS_KEY);
        return new RuntimeScalar(pos != null ? pos.getLong() : 0).getList();
    }

    public static RuntimeList gzseek(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("gzseek: not enough arguments");
        }
        RuntimeHash self = args.get(0).hashDeref();
        long offset = args.get(1).getLong();
        int whence = args.get(2).getInt();
        long current = self.get(POS_KEY) != null ? self.get(POS_KEY).getLong() : 0;
        RuntimeScalar mode = self.get(MODE_KEY);
        long target;
        if (whence == 0) {
            target = offset;
        } else if (whence == 1) {
            target = current + offset;
        } else if (whence == 2) {
            if (mode != null && mode.toString().startsWith("r")) {
                throw new IllegalArgumentException("gzseek: SEEK_END not allowed");
            }
            throw new IllegalArgumentException("gzseek: cannot seek backwards");
        } else {
            throw new IllegalArgumentException("gzseek: unknown value, " + whence + ", for whence parameter");
        }
        if (target < current) {
            throw new IllegalArgumentException("gzseek: cannot seek backwards");
        }
        long gap = target - current;
        long newPosition = target;
        if (gap > 0 && mode != null && mode.toString().startsWith("r")) {
            RuntimeScalar streamScalar = self.get(STREAM_KEY);
            if (streamScalar != null && streamScalar.type == RuntimeScalarType.JAVAOBJECT
                    && streamScalar.value instanceof InputStream is) {
                try {
                    long remaining = gap;
                    while (remaining > 0) {
                        long skipped = is.skip(remaining);
                        if (skipped > 0) {
                            remaining -= skipped;
                            continue;
                        }
                        int one = is.read();
                        if (one == -1) {
                            self.put(EOF_KEY, new RuntimeScalar(1));
                            break;
                        }
                        remaining--;
                    }
                    gap -= remaining;
                    newPosition = current + gap;
                } catch (IOException e) {
                    return new RuntimeScalar(0).getList();
                }
            }
        } else if (gap > 0 && mode != null && mode.toString().startsWith("w")) {
            RuntimeScalar streamScalar = self.get(STREAM_KEY);
            if (streamScalar != null && streamScalar.type == RuntimeScalarType.JAVAOBJECT
                    && streamScalar.value instanceof OutputStream os) {
                try {
                    byte[] zeros = new byte[(int) Math.min(gap, 8192)];
                    while (gap > 0) {
                        int chunk = (int) Math.min(gap, zeros.length);
                        os.write(zeros, 0, chunk);
                        gap -= chunk;
                    }
                } catch (IOException e) {
                    return new RuntimeScalar(0).getList();
                }
            }
        }
        self.put(POS_KEY, new RuntimeScalar(newPosition));
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList gzflush(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(-2).getList();
        }
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar streamScalar = self.get(STREAM_KEY);
        if (streamScalar == null || streamScalar.type != RuntimeScalarType.JAVAOBJECT) {
            return new RuntimeScalar(-2).getList();
        }
        try {
            if (streamScalar.value instanceof OutputStream os) {
                os.flush();
            }
            return new RuntimeScalar(0).getList();
        } catch (IOException e) {
            return new RuntimeScalar(-2).getList();
        }
    }

    public static RuntimeList gzsetparams(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("Usage: Compress::Zlib::gzFile::gzsetparams(file, level, strategy)");
        }
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar mode = self.get(MODE_KEY);
        if (mode != null && mode.toString().startsWith("r")) {
            return new RuntimeScalar(-2).getList();
        }
        return new RuntimeScalar(0).getList();
    }

    /**
     * $gz->gzclose()
     * Closes the gzip file. Returns 0 (Z_OK) on success, -1 on error.
     */
    public static RuntimeList gzclose(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(-1).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();

        RuntimeScalar streamScalar = self.get(STREAM_KEY);
        if (streamScalar == null || streamScalar.type != RuntimeScalarType.JAVAOBJECT) {
            return new RuntimeScalar(-1).getList();
        }

        try {
            Object stream = streamScalar.value;
            if (stream instanceof OutputStream os) {
                os.flush();
                os.close();
            } else if (stream instanceof InputStream is) {
                is.close();
            }
            self.put(STREAM_KEY, scalarUndef);
            self.put(EOF_KEY, new RuntimeScalar(1));
            return new RuntimeScalar(0).getList(); // Z_OK
        } catch (IOException e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    /**
     * $gz->gzerror()
     * Returns the last error message or empty string if no error.
     * In list context, returns (message, errno).
     */
    public static RuntimeList gzerror(RuntimeArray args, int ctx) {
        // No error tracking in this implementation — always return success
        if (ctx == org.perlonjava.runtime.runtimetypes.RuntimeContextType.LIST) {
            RuntimeList result = new RuntimeList();
            result.add(new RuntimeScalar(""));
            result.add(new RuntimeScalar(0)); // Z_OK
            return result;
        }
        return new RuntimeScalar(0).getList();
    }

    private static void addPosition(RuntimeHash self, long amount) {
        RuntimeScalar pos = self.get(POS_KEY);
        long current = pos != null ? pos.getLong() : 0;
        self.put(POS_KEY, new RuntimeScalar(current + amount));
    }
}

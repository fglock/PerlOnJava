package org.perlonjava.runtime.perlmodule;

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
            return new RuntimeScalar(-1).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        int nbytes = args.size() >= 3 ? args.get(2).getInt() : 4096;

        RuntimeScalar streamScalar = self.get(STREAM_KEY);
        if (streamScalar == null || streamScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(streamScalar.value instanceof InputStream is)) {
            return new RuntimeScalar(-1).getList();
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

            String data = new String(buf, 0, totalRead, StandardCharsets.ISO_8859_1);
            // Modify the buffer argument in-place via @_ alias
            args.get(1).set(data);
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
            return new RuntimeScalar(0).getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        String data = args.get(1).toString();

        RuntimeScalar streamScalar = self.get(STREAM_KEY);
        if (streamScalar == null || streamScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(streamScalar.value instanceof OutputStream os)) {
            return new RuntimeScalar(0).getList();
        }

        try {
            byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
            os.write(bytes);
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
            args.get(1).set(result);
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
        return new RuntimeScalar("").getList();
    }
}

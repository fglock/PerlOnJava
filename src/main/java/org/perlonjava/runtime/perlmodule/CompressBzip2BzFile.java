package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Implements the Compress::Bzip2::bzFile class returned by
 * {@link CompressBzip2#bzopen}. Provides bzread / bzwrite / bzreadline /
 * bzeof / bzclose / bzerror methods, matching the OO API of the upstream
 * Compress::Bzip2 CPAN module.
 *
 * <p>Mirrors the pattern of {@link CompressZlibGzFile} for consistency.
 */
public class CompressBzip2BzFile extends PerlModuleBase {

    private static final String STREAM_KEY = "_stream";
    private static final String EOF_KEY = "_eof";

    public CompressBzip2BzFile() {
        super("Compress::Bzip2::bzFile", false);
    }

    public static void initialize() {
        CompressBzip2BzFile bz = new CompressBzip2BzFile();
        try {
            bz.registerMethod("bzread", null);
            bz.registerMethod("bzwrite", null);
            bz.registerMethod("bzreadline", null);
            bz.registerMethod("bzeof", null);
            bz.registerMethod("bzclose", null);
            bz.registerMethod("bzerror", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Compress::Bzip2::bzFile method: " + e.getMessage());
        }
    }

    /**
     * $bz->bzread($buffer [, $size]) — fills $buffer in place and returns the
     * number of bytes read (0 on EOF, -1 on error).
     */
    public static RuntimeList bzread(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(-1).getList();

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
                args.get(1).set("");
                return new RuntimeScalar(0).getList();
            }

            String data = new String(buf, 0, totalRead, StandardCharsets.ISO_8859_1);
            args.get(1).set(data);
            return new RuntimeScalar(totalRead).getList();
        } catch (IOException e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    /** $bz->bzwrite($data) — returns bytes written or 0 on error. */
    public static RuntimeList bzwrite(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(0).getList();

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
     * $bz->bzreadline($line) — reads one line into $line in place, returns
     * length read (0 on EOF, -1 on error).
     */
    public static RuntimeList bzreadline(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeScalar(-1).getList();

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
                if (c == '\n') break;
            }

            if (line.isEmpty()) {
                self.put(EOF_KEY, new RuntimeScalar(1));
                args.get(1).set("");
                return new RuntimeScalar(0).getList();
            }

            String result = line.toString();
            args.get(1).set(result);
            return new RuntimeScalar(result.length()).getList();
        } catch (IOException e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    /** $bz->bzeof — 1 if at end of stream, 0 otherwise. */
    public static RuntimeList bzeof(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return new RuntimeScalar(0).getList();
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar eofScalar = self.get(EOF_KEY);
        return new RuntimeScalar(eofScalar != null ? eofScalar.getInt() : 0).getList();
    }

    /** $bz->bzclose — closes the stream. Returns 0 (BZ_OK) on success, -1 on error. */
    public static RuntimeList bzclose(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return new RuntimeScalar(-1).getList();

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
            return new RuntimeScalar(0).getList();
        } catch (IOException e) {
            return new RuntimeScalar(-1).getList();
        }
    }

    /**
     * $bz->bzerror — no error tracking; always reports success. Returns
     * "" in scalar context, ("", 0) in list context (matching upstream).
     */
    public static RuntimeList bzerror(RuntimeArray args, int ctx) {
        if (ctx == RuntimeContextType.LIST) {
            RuntimeList result = new RuntimeList();
            result.add(new RuntimeScalar(""));
            result.add(new RuntimeScalar(0));
            return result;
        }
        return new RuntimeScalar("").getList();
    }
}

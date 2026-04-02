package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

public class CompressZlib extends PerlModuleBase {

    private static final String INFLATER_KEY = "_inflater";
    private static final String DEFLATER_KEY = "_deflater";

    public CompressZlib() {
        super("Compress::Zlib", false);
    }

    public static void initialize() {
        CompressZlib cz = new CompressZlib();
        try {
            cz.registerMethod("inflateInit", null);
            cz.registerMethod("deflateInit", null);
            cz.registerMethod("Z_OK", null);
            cz.registerMethod("Z_STREAM_END", null);
            cz.registerMethod("Z_STREAM_ERROR", null);
            cz.registerMethod("Z_DATA_ERROR", null);
            cz.registerMethod("Z_BUF_ERROR", null);
            cz.registerMethod("MAX_WBITS", null);
            cz.registerMethod("inflate", "inflateMethod", null);
            cz.registerMethod("deflate", "deflateMethod", null);
            cz.registerMethod("flush", null);
            cz.registerMethod("gzopen", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Compress::Zlib method: " + e.getMessage());
        }

        // Initialize gzFile methods (gzread, gzwrite, gzreadline, gzeof, gzclose)
        CompressZlibGzFile.initialize();
    }

    public static RuntimeList Z_OK(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList Z_STREAM_END(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList Z_STREAM_ERROR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(-2).getList();
    }

    public static RuntimeList Z_DATA_ERROR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(-3).getList();
    }

    public static RuntimeList Z_BUF_ERROR(RuntimeArray args, int ctx) {
        return new RuntimeScalar(-5).getList();
    }

    public static RuntimeList MAX_WBITS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(15).getList();
    }

    public static RuntimeList inflateInit(RuntimeArray args, int ctx) {
        boolean nowrap = false;

        for (int i = 0; i < args.size() - 1; i++) {
            String key = args.get(i).toString();
            if (key.equals("-WindowBits") || key.equals("WindowBits")) {
                int wbits = args.get(i + 1).getInt();
                if (wbits < 0) {
                    nowrap = true;
                }
                break;
            }
        }

        try {
            Inflater inflater = new Inflater(nowrap);
            RuntimeHash self = new RuntimeHash();
            self.put(INFLATER_KEY, new RuntimeScalar(inflater));
            RuntimeScalar ref = self.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Zlib"));
            return ref.getList();
        } catch (Exception e) {
            return scalarUndef.getList();
        }
    }

    public static RuntimeList deflateInit(RuntimeArray args, int ctx) {
        int level = Deflater.DEFAULT_COMPRESSION;

        for (int i = 0; i < args.size() - 1; i++) {
            String key = args.get(i).toString();
            if (key.equals("-Level") || key.equals("Level")) {
                level = args.get(i + 1).getInt();
                break;
            }
        }

        try {
            Deflater deflater = new Deflater(level);
            RuntimeHash self = new RuntimeHash();
            self.put(DEFLATER_KEY, new RuntimeScalar(deflater));
            RuntimeScalar ref = self.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Zlib"));
            return ref.getList();
        } catch (Exception e) {
            return scalarUndef.getList();
        }
    }

    public static RuntimeList inflateMethod(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            RuntimeList result = new RuntimeList();
            result.add(scalarUndef);
            result.add(new RuntimeScalar(-2));
            return result;
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar dataScalar = args.get(1);

        RuntimeScalar inflaterScalar = self.get(INFLATER_KEY);
        if (inflaterScalar == null || inflaterScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(inflaterScalar.value instanceof Inflater inflater)) {
            RuntimeList result = new RuntimeList();
            result.add(scalarUndef);
            result.add(new RuntimeScalar(-2));
            return result;
        }

        String dataStr = dataScalar.toString();
        byte[] input = dataStr.getBytes(StandardCharsets.ISO_8859_1);
        inflater.setInput(input);

        byte[] outputBuf = new byte[input.length * 4 + 1024];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int status = 0; // Z_OK

        try {
            while (!inflater.finished() && !inflater.needsInput()) {
                int count = inflater.inflate(outputBuf);
                if (count > 0) {
                    baos.write(outputBuf, 0, count);
                } else if (count == 0 && !inflater.finished()) {
                    break;
                }
            }
            if (inflater.finished()) {
                status = 1; // Z_STREAM_END
            }
        } catch (DataFormatException e) {
            RuntimeList result = new RuntimeList();
            result.add(scalarUndef);
            result.add(new RuntimeScalar(-3)); // Z_DATA_ERROR
            return result;
        }

        String outputStr = baos.toString(StandardCharsets.ISO_8859_1);

        RuntimeList result = new RuntimeList();
        RuntimeScalar outputScalar = new RuntimeScalar(outputStr);
        outputScalar.type = RuntimeScalarType.BYTE_STRING;
        result.add(outputScalar);
        result.add(new RuntimeScalar(status));
        return result;
    }

    public static RuntimeList deflateMethod(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar dataScalar = args.get(1);

        RuntimeScalar deflaterScalar = self.get(DEFLATER_KEY);
        if (deflaterScalar == null || deflaterScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(deflaterScalar.value instanceof Deflater deflater)) {
            return scalarUndef.getList();
        }

        String dataStr = dataScalar.toString();
        byte[] input = dataStr.getBytes(StandardCharsets.ISO_8859_1);
        deflater.setInput(input);

        byte[] outputBuf = new byte[input.length + 256];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        int count;
        while ((count = deflater.deflate(outputBuf, 0, outputBuf.length, Deflater.SYNC_FLUSH)) > 0) {
            baos.write(outputBuf, 0, count);
        }

        String outputStr = baos.toString(StandardCharsets.ISO_8859_1);
        RuntimeScalar outputScalar = new RuntimeScalar(outputStr);
        outputScalar.type = RuntimeScalarType.BYTE_STRING;
        return outputScalar.getList();
    }

    public static RuntimeList flush(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar deflaterScalar = self.get(DEFLATER_KEY);
        if (deflaterScalar == null || deflaterScalar.type != RuntimeScalarType.JAVAOBJECT
                || !(deflaterScalar.value instanceof Deflater deflater)) {
            return scalarUndef.getList();
        }

        deflater.finish();

        byte[] outputBuf = new byte[1024];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        while (!deflater.finished()) {
            int count = deflater.deflate(outputBuf);
            if (count > 0) {
                baos.write(outputBuf, 0, count);
            } else {
                break;
            }
        }

        String outputStr = baos.toString(StandardCharsets.ISO_8859_1);
        RuntimeScalar outputScalar = new RuntimeScalar(outputStr);
        outputScalar.type = RuntimeScalarType.BYTE_STRING;
        return outputScalar.getList();
    }

    /**
     * gzopen($filename, $mode)
     * Opens a gzip file for reading ('rb') or writing ('wb').
     * Returns a blessed Compress::Zlib::gzFile object, or undef on error.
     */
    public static RuntimeList gzopen(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            return scalarUndef.getList();
        }

        // Skip 'self' if called as Compress::Zlib->gzopen() (class method)
        int argOffset = 0;
        String firstArg = args.get(0).toString();
        if (firstArg.equals("Compress::Zlib") || firstArg.contains("::")) {
            argOffset = 1;
        }

        if (args.size() < argOffset + 2) {
            return scalarUndef.getList();
        }

        String filename = args.get(argOffset).toString();
        String mode = args.get(argOffset + 1).toString();

        try {
            RuntimeHash self = new RuntimeHash();
            self.put("_mode", new RuntimeScalar(mode));
            self.put("_eof", new RuntimeScalar(0));

            if (mode.startsWith("r")) {
                // Read mode
                InputStream fis = new FileInputStream(filename);
                GZIPInputStream gis = new GZIPInputStream(fis);
                self.put("_stream", new RuntimeScalar(gis));
            } else if (mode.startsWith("w")) {
                // Write mode - check for compression level
                OutputStream fos = new FileOutputStream(filename);
                GZIPOutputStream gos = new GZIPOutputStream(fos);
                self.put("_stream", new RuntimeScalar(gos));
            } else {
                return scalarUndef.getList();
            }

            RuntimeScalar ref = self.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Zlib::gzFile"));
            return ref.getList();
        } catch (IOException e) {
            return scalarUndef.getList();
        }
    }
}

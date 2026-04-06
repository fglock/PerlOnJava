package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

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
            cz.registerMethod("compress", null);
            cz.registerMethod("uncompress", null);
            cz.registerMethod("memGzip", null);
            cz.registerMethod("memGunzip", null);
            cz.registerMethod("crc32", null);
            cz.registerMethod("adler32", null);
            cz.registerMethod("Z_OK", null);
            cz.registerMethod("Z_STREAM_END", null);
            cz.registerMethod("Z_STREAM_ERROR", null);
            cz.registerMethod("Z_DATA_ERROR", null);
            cz.registerMethod("Z_BUF_ERROR", null);
            cz.registerMethod("Z_NO_FLUSH", null);
            cz.registerMethod("Z_SYNC_FLUSH", null);
            cz.registerMethod("Z_FULL_FLUSH", null);
            cz.registerMethod("Z_FINISH", null);
            cz.registerMethod("Z_DEFAULT_COMPRESSION", null);
            cz.registerMethod("Z_BEST_SPEED", null);
            cz.registerMethod("Z_BEST_COMPRESSION", null);
            cz.registerMethod("Z_FILTERED", null);
            cz.registerMethod("Z_HUFFMAN_ONLY", null);
            cz.registerMethod("Z_DEFAULT_STRATEGY", null);
            cz.registerMethod("Z_DEFLATED", null);
            cz.registerMethod("WANT_GZIP", null);
            cz.registerMethod("WANT_GZIP_OR_ZLIB", null);
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

    public static RuntimeList Z_NO_FLUSH(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList Z_SYNC_FLUSH(RuntimeArray args, int ctx) {
        return new RuntimeScalar(2).getList();
    }

    public static RuntimeList Z_FULL_FLUSH(RuntimeArray args, int ctx) {
        return new RuntimeScalar(3).getList();
    }

    public static RuntimeList Z_FINISH(RuntimeArray args, int ctx) {
        return new RuntimeScalar(4).getList();
    }

    public static RuntimeList Z_DEFAULT_COMPRESSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(-1).getList();
    }

    public static RuntimeList Z_BEST_SPEED(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList Z_BEST_COMPRESSION(RuntimeArray args, int ctx) {
        return new RuntimeScalar(9).getList();
    }

    public static RuntimeList Z_FILTERED(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList Z_HUFFMAN_ONLY(RuntimeArray args, int ctx) {
        return new RuntimeScalar(2).getList();
    }

    public static RuntimeList Z_DEFAULT_STRATEGY(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList Z_DEFLATED(RuntimeArray args, int ctx) {
        return new RuntimeScalar(8).getList();
    }

    public static RuntimeList WANT_GZIP(RuntimeArray args, int ctx) {
        return new RuntimeScalar(16).getList();
    }

    public static RuntimeList WANT_GZIP_OR_ZLIB(RuntimeArray args, int ctx) {
        return new RuntimeScalar(32).getList();
    }

    public static RuntimeList MAX_WBITS(RuntimeArray args, int ctx) {
        return new RuntimeScalar(15).getList();
    }

    /**
     * Helper to extract byte data from a scalar or scalar reference.
     */
    private static byte[] getInputBytes(RuntimeScalar dataScalar) {
        RuntimeScalar actual = dataScalar;
        if (dataScalar.type == RuntimeScalarType.REFERENCE) {
            actual = dataScalar.scalarDeref();
        }
        return actual.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * compress($data [, $level])
     * One-shot zlib compression (RFC 1950 format).
     * Returns compressed data or undef on error.
     */
    public static RuntimeList compress(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        int level = Deflater.DEFAULT_COMPRESSION;
        byte[] input = getInputBytes(args.get(0));

        if (args.size() > 1) {
            level = args.get(1).getInt();
        }

        try {
            Deflater deflater = new Deflater(level, false); // nowrap=false for zlib format
            deflater.setInput(input);
            deflater.finish();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length + 64);
            byte[] buf = new byte[Math.max(input.length + 64, 1024)];

            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                if (count > 0) {
                    baos.write(buf, 0, count);
                } else {
                    break;
                }
            }
            deflater.end();

            RuntimeScalar result = new RuntimeScalar(baos.toString(StandardCharsets.ISO_8859_1));
            result.type = RuntimeScalarType.BYTE_STRING;
            return result.getList();
        } catch (Exception e) {
            return scalarUndef.getList();
        }
    }

    /**
     * uncompress($data)
     * One-shot zlib decompression (RFC 1950 format).
     * Returns decompressed data or undef on error.
     */
    public static RuntimeList uncompress(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        byte[] input = getInputBytes(args.get(0));

        try {
            Inflater inflater = new Inflater(false); // nowrap=false for zlib format
            inflater.setInput(input);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length * 4);
            byte[] buf = new byte[Math.max(input.length * 4, 1024)];

            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                if (count > 0) {
                    baos.write(buf, 0, count);
                } else if (inflater.needsInput()) {
                    break;
                }
            }

            if (!inflater.finished()) {
                inflater.end();
                return scalarUndef.getList();
            }

            inflater.end();

            RuntimeScalar result = new RuntimeScalar(baos.toString(StandardCharsets.ISO_8859_1));
            result.type = RuntimeScalarType.BYTE_STRING;
            return result.getList();
        } catch (DataFormatException | IllegalArgumentException e) {
            return scalarUndef.getList();
        }
    }

    /**
     * memGzip($data)
     * Compress data in gzip format (RFC 1952).
     * Returns gzipped data or undef on error.
     */
    public static RuntimeList memGzip(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        byte[] input = getInputBytes(args.get(0));

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length + 64);
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                gos.write(input);
            }

            RuntimeScalar result = new RuntimeScalar(baos.toString(StandardCharsets.ISO_8859_1));
            result.type = RuntimeScalarType.BYTE_STRING;
            return result.getList();
        } catch (IOException e) {
            return scalarUndef.getList();
        }
    }

    /**
     * memGunzip($data)
     * Decompress gzip format data (RFC 1952).
     * Returns decompressed data or undef on error.
     */
    public static RuntimeList memGunzip(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return scalarUndef.getList();
        }

        byte[] input = getInputBytes(args.get(0));

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(input);
            GZIPInputStream gis = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length * 4);

            byte[] buf = new byte[4096];
            int count;
            while ((count = gis.read(buf)) != -1) {
                baos.write(buf, 0, count);
            }
            gis.close();

            RuntimeScalar result = new RuntimeScalar(baos.toString(StandardCharsets.ISO_8859_1));
            result.type = RuntimeScalarType.BYTE_STRING;
            return result.getList();
        } catch (IOException e) {
            return scalarUndef.getList();
        }
    }

    /**
     * crc32($data [, $crc])
     * Calculate CRC-32 checksum. If $crc is provided, continue from that value.
     * Returns unsigned 32-bit integer.
     */
    public static RuntimeList crc32(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }

        byte[] input = getInputBytes(args.get(0));
        CRC32 crc = new CRC32();

        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            // Continue from previous CRC value — CRC32 doesn't support direct seeding,
            // but Perl's crc32() with a second arg does a running checksum.
            // Java's CRC32 doesn't allow seeding, so we use Adler32-style workaround:
            // For compatibility, we use the combine approach if needed.
            // Actually, Perl's Compress::Zlib::crc32 with initial value works by
            // calling zlib's crc32() C function which supports seeding.
            // Java's CRC32 doesn't support this directly, but we can use the
            // crc32_combine concept. For simplicity, if the initial value is 0,
            // just compute normally. For non-zero seed, we need a different approach.
            long seed = args.get(1).getLong() & 0xFFFFFFFFL;
            if (seed != 0) {
                // Use reflection or a manual CRC32 table for seeding.
                // Simpler: compute via direct table calculation.
                long crcVal = crc32WithSeed(input, seed);
                return new RuntimeScalar(crcVal).getList();
            }
        }

        crc.update(input);
        return new RuntimeScalar(crc.getValue()).getList();
    }

    /**
     * CRC-32 computation with an initial seed value.
     * Implements the CRC-32 algorithm directly with a lookup table.
     */
    private static final long[] CRC32_TABLE = new long[256];
    static {
        for (int i = 0; i < 256; i++) {
            long c = i;
            for (int j = 0; j < 8; j++) {
                if ((c & 1) != 0) {
                    c = 0xEDB88320L ^ (c >>> 1);
                } else {
                    c >>>= 1;
                }
            }
            CRC32_TABLE[i] = c;
        }
    }

    private static long crc32WithSeed(byte[] data, long seed) {
        long crc = seed ^ 0xFFFFFFFFL;
        for (byte b : data) {
            crc = CRC32_TABLE[(int) ((crc ^ b) & 0xFF)] ^ (crc >>> 8);
        }
        return (crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    /**
     * adler32($data [, $adler])
     * Calculate Adler-32 checksum. If $adler is provided, continue from that value.
     * Returns unsigned 32-bit integer.
     */
    public static RuntimeList adler32(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(1).getList(); // Adler-32 of empty data is 1
        }

        byte[] input = getInputBytes(args.get(0));
        Adler32 adler = new Adler32();

        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            long seed = args.get(1).getLong() & 0xFFFFFFFFL;
            if (seed != 1) {
                // Adler32 with seed: compute manually
                long s1 = seed & 0xFFFF;
                long s2 = (seed >>> 16) & 0xFFFF;
                for (byte b : input) {
                    s1 = (s1 + (b & 0xFF)) % 65521;
                    s2 = (s2 + s1) % 65521;
                }
                return new RuntimeScalar((s2 << 16) | s1).getList();
            }
        }

        adler.update(input);
        return new RuntimeScalar(adler.getValue()).getList();
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
        boolean nowrap = false;

        for (int i = 0; i < args.size() - 1; i++) {
            String key = args.get(i).toString();
            if (key.equals("-Level") || key.equals("Level")) {
                level = args.get(i + 1).getInt();
                i++;
            } else if (key.equals("-WindowBits") || key.equals("WindowBits")) {
                int wbits = args.get(i + 1).getInt();
                if (wbits < 0) {
                    nowrap = true; // raw deflate
                }
                // wbits >= 16 would mean gzip format, but Java's Deflater
                // doesn't support that directly — use memGzip instead
                i++;
            }
        }

        try {
            Deflater deflater = new Deflater(level, nowrap);
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

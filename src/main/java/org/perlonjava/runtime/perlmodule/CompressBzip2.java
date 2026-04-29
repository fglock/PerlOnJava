package org.perlonjava.runtime.perlmodule;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Implements the Compress::Bzip2 module on top of Apache Commons Compress.
 *
 * <p>The upstream CPAN module (Rob Janes) is XS-only. This Java backend
 * provides the API surface that pure-Perl callers use:
 * <ul>
 *   <li>One-shot helpers: memBzip, memBunzip, bzip2, bzunzip</li>
 *   <li>File handle API: bzopen returning a Compress::Bzip2::bzFile object</li>
 *   <li>The error-code constants exported by :constants</li>
 * </ul>
 * Companion class {@link CompressBzip2BzFile} provides the bzread / bzwrite /
 * bzclose / bzreadline / bzeof methods on the returned handle objects.
 */
public class CompressBzip2 extends PerlModuleBase {

    public CompressBzip2() {
        super("Compress::Bzip2", false);
    }

    public static void initialize() {
        CompressBzip2 cb = new CompressBzip2();
        try {
            cb.registerMethod("memBzip", null);
            cb.registerMethod("memBunzip", null);
            cb.registerMethod("bzip2", null);
            cb.registerMethod("bzunzip", null);
            cb.registerMethod("bzopen", null);

            // libbzip2 status / mode constants. The numeric values mirror the
            // upstream Compress::Bzip2 / libbzip2 headers so callers comparing
            // against e.g. BZ_STREAM_END keep working.
            cb.registerMethod("BZ_OK", null);
            cb.registerMethod("BZ_RUN_OK", null);
            cb.registerMethod("BZ_FLUSH_OK", null);
            cb.registerMethod("BZ_FINISH_OK", null);
            cb.registerMethod("BZ_STREAM_END", null);
            cb.registerMethod("BZ_SEQUENCE_ERROR", null);
            cb.registerMethod("BZ_PARAM_ERROR", null);
            cb.registerMethod("BZ_MEM_ERROR", null);
            cb.registerMethod("BZ_DATA_ERROR", null);
            cb.registerMethod("BZ_DATA_ERROR_MAGIC", null);
            cb.registerMethod("BZ_IO_ERROR", null);
            cb.registerMethod("BZ_UNEXPECTED_EOF", null);
            cb.registerMethod("BZ_OUTBUFF_FULL", null);
            cb.registerMethod("BZ_CONFIG_ERROR", null);
            cb.registerMethod("BZ_RUN", null);
            cb.registerMethod("BZ_FLUSH", null);
            cb.registerMethod("BZ_FINISH", null);
            cb.registerMethod("BZ_MAX_UNUSED", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Compress::Bzip2 method: " + e.getMessage());
        }

        CompressBzip2BzFile.initialize();
    }

    public static RuntimeList BZ_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList BZ_RUN_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList BZ_FLUSH_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList BZ_FINISH_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(3).getList(); }
    public static RuntimeList BZ_STREAM_END(RuntimeArray args, int ctx) { return new RuntimeScalar(4).getList(); }
    public static RuntimeList BZ_SEQUENCE_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(-1).getList(); }
    public static RuntimeList BZ_PARAM_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(-2).getList(); }
    public static RuntimeList BZ_MEM_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(-3).getList(); }
    public static RuntimeList BZ_DATA_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(-4).getList(); }
    public static RuntimeList BZ_DATA_ERROR_MAGIC(RuntimeArray args, int ctx) { return new RuntimeScalar(-5).getList(); }
    public static RuntimeList BZ_IO_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(-6).getList(); }
    public static RuntimeList BZ_UNEXPECTED_EOF(RuntimeArray args, int ctx) { return new RuntimeScalar(-7).getList(); }
    public static RuntimeList BZ_OUTBUFF_FULL(RuntimeArray args, int ctx) { return new RuntimeScalar(-8).getList(); }
    public static RuntimeList BZ_CONFIG_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(-9).getList(); }
    public static RuntimeList BZ_RUN(RuntimeArray args, int ctx) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList BZ_FLUSH(RuntimeArray args, int ctx) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList BZ_FINISH(RuntimeArray args, int ctx) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList BZ_MAX_UNUSED(RuntimeArray args, int ctx) { return new RuntimeScalar(5000).getList(); }

    /**
     * Treat a scalar (or scalar reference) as a byte string. Mirrors the
     * helper in {@link CompressZlib}.
     */
    private static byte[] getInputBytes(RuntimeScalar dataScalar) {
        RuntimeScalar actual = dataScalar;
        if (dataScalar.type == RuntimeScalarType.REFERENCE) {
            actual = dataScalar.scalarDeref();
        }
        return actual.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static RuntimeScalar bytesToScalar(byte[] bytes, int len) {
        RuntimeScalar s = new RuntimeScalar(new String(bytes, 0, len, StandardCharsets.ISO_8859_1));
        s.type = RuntimeScalarType.BYTE_STRING;
        return s;
    }

    /**
     * memBzip($data) — one-shot compression. Returns the bzip2 stream or
     * undef on error (matching the upstream module).
     */
    public static RuntimeList memBzip(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return scalarUndef.getList();
        byte[] input = getInputBytes(args.get(0));
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, input.length / 2));
            try (BZip2CompressorOutputStream out = new BZip2CompressorOutputStream(baos)) {
                out.write(input);
            }
            byte[] result = baos.toByteArray();
            return bytesToScalar(result, result.length).getList();
        } catch (IOException e) {
            return scalarUndef.getList();
        }
    }

    /**
     * memBunzip($data) — one-shot decompression. Returns the inflated bytes
     * or undef on error.
     */
    public static RuntimeList memBunzip(RuntimeArray args, int ctx) {
        if (args.isEmpty()) return scalarUndef.getList();
        byte[] input = getInputBytes(args.get(0));
        try (BZip2CompressorInputStream in = new BZip2CompressorInputStream(
                new ByteArrayInputStream(input), true)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length * 4);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            byte[] result = baos.toByteArray();
            return bytesToScalar(result, result.length).getList();
        } catch (IOException e) {
            return scalarUndef.getList();
        }
    }

    /** bzip2($data) — alias of memBzip in the upstream module. */
    public static RuntimeList bzip2(RuntimeArray args, int ctx) {
        return memBzip(args, ctx);
    }

    /** bzunzip($data) — alias of memBunzip. */
    public static RuntimeList bzunzip(RuntimeArray args, int ctx) {
        return memBunzip(args, ctx);
    }

    /**
     * bzopen($filename, $mode) — opens a bzip2 file for reading ('rb') or
     * writing ('wb'). Returns a blessed Compress::Bzip2::bzFile handle, or
     * undef on error. Method-call syntax (Compress::Bzip2-&gt;bzopen) is
     * also accepted.
     */
    public static RuntimeList bzopen(RuntimeArray args, int ctx) {
        int argOffset = 0;
        if (!args.isEmpty()) {
            String first = args.get(0).toString();
            if (first.equals("Compress::Bzip2") || first.contains("::")) {
                argOffset = 1;
            }
        }
        if (args.size() < argOffset + 2) return scalarUndef.getList();

        String filename = args.get(argOffset).toString();
        String mode = args.get(argOffset + 1).toString();

        try {
            RuntimeHash self = new RuntimeHash();
            self.put("_mode", new RuntimeScalar(mode));
            self.put("_eof", new RuntimeScalar(0));

            if (mode.startsWith("r")) {
                // Concatenated streams (true) so multi-block .bz2 archives
                // (typical for tarballs) are read end-to-end.
                InputStream fis = new FileInputStream(filename);
                BZip2CompressorInputStream in = new BZip2CompressorInputStream(fis, true);
                self.put("_stream", new RuntimeScalar(in));
            } else if (mode.startsWith("w")) {
                OutputStream fos = new FileOutputStream(filename);
                BZip2CompressorOutputStream out = new BZip2CompressorOutputStream(fos);
                self.put("_stream", new RuntimeScalar(out));
            } else {
                return scalarUndef.getList();
            }

            RuntimeScalar ref = self.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Bzip2::bzFile"));
            return ref.getList();
        } catch (IOException e) {
            return scalarUndef.getList();
        }
    }
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.*;
import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Java XS backend for Compress::Raw::Zlib.
 * Provides low-level zlib streaming compress/decompress API
 * used by IO::Compress::Gzip, Mojo::Content, etc.
 */
public class CompressRawZlib extends PerlModuleBase {

    // Zlib constants
    private static final int Z_OK = 0;
    private static final int Z_STREAM_END = 1;
    private static final int Z_NEED_DICT = 2;
    private static final int Z_ERRNO = -1;
    private static final int Z_STREAM_ERROR = -2;
    private static final int Z_DATA_ERROR = -3;
    private static final int Z_MEM_ERROR = -4;
    private static final int Z_BUF_ERROR = -5;
    private static final int Z_VERSION_ERROR = -6;

    private static final int Z_NO_FLUSH = 0;
    private static final int Z_SYNC_FLUSH = 2;
    private static final int Z_FULL_FLUSH = 3;
    private static final int Z_FINISH = 4;

    private static final int Z_DEFAULT_COMPRESSION = -1;

    private static final int MAX_WBITS = 15;
    private static final int MAX_MEM_LEVEL = 9;

    // Flags
    private static final int FLAG_APPEND = 1;
    private static final int FLAG_CRC = 2;
    private static final int FLAG_ADLER = 4;
    private static final int FLAG_CONSUME_INPUT = 8;
    private static final int FLAG_LIMIT_OUTPUT = 16;

    // Constant map for the constant() function
    private static final Map<String, Object> CONSTANTS = new HashMap<>();
    static {
        CONSTANTS.put("Z_OK", 0);
        CONSTANTS.put("Z_STREAM_END", 1);
        CONSTANTS.put("Z_NEED_DICT", 2);
        CONSTANTS.put("Z_ERRNO", -1);
        CONSTANTS.put("Z_STREAM_ERROR", -2);
        CONSTANTS.put("Z_DATA_ERROR", -3);
        CONSTANTS.put("Z_MEM_ERROR", -4);
        CONSTANTS.put("Z_BUF_ERROR", -5);
        CONSTANTS.put("Z_VERSION_ERROR", -6);
        CONSTANTS.put("Z_NO_FLUSH", 0);
        CONSTANTS.put("Z_PARTIAL_FLUSH", 1);
        CONSTANTS.put("Z_SYNC_FLUSH", 2);
        CONSTANTS.put("Z_FULL_FLUSH", 3);
        CONSTANTS.put("Z_FINISH", 4);
        CONSTANTS.put("Z_BLOCK", 5);
        CONSTANTS.put("Z_TREES", 6);
        CONSTANTS.put("Z_NO_COMPRESSION", 0);
        CONSTANTS.put("Z_BEST_SPEED", 1);
        CONSTANTS.put("Z_BEST_COMPRESSION", 9);
        CONSTANTS.put("Z_DEFAULT_COMPRESSION", -1);
        CONSTANTS.put("Z_FILTERED", 1);
        CONSTANTS.put("Z_HUFFMAN_ONLY", 2);
        CONSTANTS.put("Z_RLE", 3);
        CONSTANTS.put("Z_FIXED", 4);
        CONSTANTS.put("Z_DEFAULT_STRATEGY", 0);
        CONSTANTS.put("Z_DEFLATED", 8);
        CONSTANTS.put("Z_NULL", 0);
        CONSTANTS.put("Z_ASCII", 1);
        CONSTANTS.put("Z_BINARY", 0);
        CONSTANTS.put("Z_UNKNOWN", 2);
        CONSTANTS.put("MAX_WBITS", 15);
        CONSTANTS.put("MAX_MEM_LEVEL", 9);
        CONSTANTS.put("OS_CODE", 3); // Unix
        CONSTANTS.put("DEF_WBITS", 15);
        CONSTANTS.put("ZLIB_VERSION", "1.2.13");
        CONSTANTS.put("ZLIB_VERNUM", 0x12D0);
        // zlib-ng constants (we're not zlib-ng)
        CONSTANTS.put("ZLIBNG_VERSION", "");
        CONSTANTS.put("ZLIBNG_VERNUM", 0);
        CONSTANTS.put("ZLIBNG_VER_MAJOR", 0);
        CONSTANTS.put("ZLIBNG_VER_MINOR", 0);
        CONSTANTS.put("ZLIBNG_VER_REVISION", 0);
        CONSTANTS.put("ZLIBNG_VER_STATUS", 0);
        CONSTANTS.put("ZLIBNG_VER_MODIFIED", 0);
    }

    // CRC32 lookup table for seeded CRC computation
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

    public CompressRawZlib() {
        super("Compress::Raw::Zlib", false);
    }

    public static void initialize() {
        CompressRawZlib crz = new CompressRawZlib();
        try {
            // Package-level functions
            crz.registerMethod("constant", null);
            crz.registerMethod("crc32", "crc32Func", null);
            crz.registerMethod("adler32", "adler32Func", null);
            crz.registerMethod("_deflateInit", "deflateInit", null);
            crz.registerMethod("_inflateInit", "inflateInit", null);
            crz.registerMethod("zlib_version", "zlibVersion", null);
            crz.registerMethod("zlibCompileFlags", null);
            crz.registerMethod("is_zlib_native", "isZlibNative", null);
            crz.registerMethod("is_zlibng_native", "isZlibngNative", null);
            crz.registerMethod("is_zlibng_compat", "isZlibngCompat", null);
            crz.registerMethod("is_zlibng", "isZlibng", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Compress::Raw::Zlib method: " + e.getMessage());
        }

        // Register deflateStream methods
        String[] deflateMethods = {
            "deflate", "flush", "deflateReset", "_deflateParams", "deflateTune",
            "crc32", "adler32", "total_in", "total_out", "msg",
            "dict_adler", "get_Level", "get_Strategy", "get_Bufsize",
            "compressedBytes", "uncompressedBytes", "DESTROY"
        };
        registerStreamMethods("Compress::Raw::Zlib::deflateStream", deflateMethods, "ds_");

        // Register inflateStream methods
        String[] inflateMethods = {
            "inflate", "inflateReset", "inflateSync",
            "crc32", "adler32", "total_in", "total_out", "msg",
            "dict_adler", "get_Bufsize",
            "compressedBytes", "uncompressedBytes", "DESTROY"
        };
        registerStreamMethods("Compress::Raw::Zlib::inflateStream", inflateMethods, "is_");

        // Set $Compress::Raw::Zlib::gzip_os_code
        GlobalVariable.getGlobalVariable("Compress::Raw::Zlib::gzip_os_code")
            .set(new RuntimeScalar(3)); // Unix

        // Set $XS_VERSION for version check in CPAN .pm
        GlobalVariable.getGlobalVariable("Compress::Raw::Zlib::XS_VERSION")
            .set(new RuntimeScalar("2.222"));
    }

    /**
     * Register static methods from this class into a different Perl package.
     */
    private static void registerStreamMethods(String packageName, String[] methods, String javaPrefix) {
        for (String method : methods) {
            try {
                String javaName = javaPrefix + method;
                MethodHandle mh = RuntimeCode.lookup.findStatic(
                    CompressRawZlib.class, javaName, RuntimeCode.methodType);
                RuntimeCode code = new RuntimeCode(mh, null, null);
                code.isStatic = true;
                code.packageName = packageName;
                code.subName = method;
                String fullName = NameNormalizer.normalizeVariableName(method, packageName);
                GlobalVariable.getGlobalCodeRef(fullName).set(new RuntimeScalar(code));
            } catch (Exception e) {
                System.err.println("Warning: Missing " + packageName + "::" + method + ": " + e.getMessage());
            }
        }
    }

    // =============================================
    // Package-level functions
    // =============================================

    /**
     * constant($name) - returns ($error, $value) for AUTOLOAD
     */
    public static RuntimeList constant(RuntimeArray args, int ctx) {
        String name = args.size() > 0 ? args.get(0).toString() : "";
        Object val = CONSTANTS.get(name);
        RuntimeList result = new RuntimeList();
        if (val != null) {
            result.add(new RuntimeScalar("")); // no error
            if (val instanceof String) {
                result.add(new RuntimeScalar((String) val));
            } else {
                result.add(new RuntimeScalar(((Number) val).longValue()));
            }
        } else {
            result.add(new RuntimeScalar("Unknown constant: " + name));
            result.add(new RuntimeScalar(0));
        }
        return result;
    }

    public static RuntimeList zlibVersion(RuntimeArray args, int ctx) {
        return new RuntimeScalar("1.2.13").getList();
    }

    public static RuntimeList zlibCompileFlags(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList isZlibNative(RuntimeArray args, int ctx) {
        return new RuntimeScalar(1).getList();
    }

    public static RuntimeList isZlibngNative(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList isZlibngCompat(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    public static RuntimeList isZlibng(RuntimeArray args, int ctx) {
        return new RuntimeScalar(0).getList();
    }

    /**
     * crc32($buffer [, $crc])
     */
    public static RuntimeList crc32Func(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0).getList();
        }
        byte[] input = getInputBytes(args.get(0));
        long seed = 0;
        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            seed = args.get(1).getLong() & 0xFFFFFFFFL;
        }
        long crc = crc32WithSeed(input, seed);
        return new RuntimeScalar(crc).getList();
    }

    /**
     * adler32($buffer [, $adler])
     */
    public static RuntimeList adler32Func(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(1).getList();
        }
        byte[] input = getInputBytes(args.get(0));
        long seed = 1;
        if (args.size() > 1 && args.get(1).getDefinedBoolean()) {
            seed = args.get(1).getLong() & 0xFFFFFFFFL;
        }
        long s1 = seed & 0xFFFF;
        long s2 = (seed >>> 16) & 0xFFFF;
        for (byte b : input) {
            s1 = (s1 + (b & 0xFF)) % 65521;
            s2 = (s2 + s1) % 65521;
        }
        return new RuntimeScalar((s2 << 16) | s1).getList();
    }

    // =============================================
    // _deflateInit / _inflateInit
    // =============================================

    /**
     * _deflateInit($flags, $level, $method, $windowBits, $memLevel, $strategy, $bufsize, $dictionary)
     * Returns ($stream, $status)
     */
    public static RuntimeList deflateInit(RuntimeArray args, int ctx) {
        int flags     = args.size() > 0 ? args.get(0).getInt() : 0;
        int level     = args.size() > 1 ? args.get(1).getInt() : Z_DEFAULT_COMPRESSION;
        // int method = args.size() > 2 ? args.get(2).getInt() : 8; // always Z_DEFLATED
        int wbits     = args.size() > 3 ? args.get(3).getInt() : MAX_WBITS;
        // int memLevel = args.size() > 4 ? args.get(4).getInt() : MAX_MEM_LEVEL;
        int strategy  = args.size() > 5 ? args.get(5).getInt() : 0;
        int bufsize   = args.size() > 6 ? args.get(6).getInt() : 4096;
        String dict   = args.size() > 7 ? args.get(7).toString() : "";

        try {
            boolean nowrap;
            boolean gzipMode = false;
            int actualWbits;
            if (wbits < 0) {
                // Raw deflate (no header)
                nowrap = true;
                actualWbits = -wbits;
            } else if (wbits > 15) {
                // Gzip mode
                nowrap = true; // We'll handle gzip header/trailer in Perl
                gzipMode = true;
                actualWbits = wbits - 16;
            } else {
                // Zlib format
                nowrap = false;
                actualWbits = wbits;
            }

            Deflater deflater = new Deflater(level, nowrap);
            if (strategy == 1) deflater.setStrategy(Deflater.FILTERED);
            else if (strategy == 2) deflater.setStrategy(Deflater.HUFFMAN_ONLY);

            if (!dict.isEmpty()) {
                byte[] dictBytes = dict.getBytes(StandardCharsets.ISO_8859_1);
                deflater.setDictionary(dictBytes);
            }

            // Create the stream object
            RuntimeHash self = new RuntimeHash();
            self.put("_deflater", new RuntimeScalar(deflater));
            self.put("_flags", new RuntimeScalar(flags));
            self.put("_level", new RuntimeScalar(level));
            self.put("_strategy", new RuntimeScalar(strategy));
            self.put("_bufsize", new RuntimeScalar(bufsize));
            self.put("_total_in", new RuntimeScalar(0));
            self.put("_total_out", new RuntimeScalar(0));
            self.put("_crc32", new RuntimeScalar(0L));
            self.put("_adler32", new RuntimeScalar(1L));
            self.put("_dict_adler", new RuntimeScalar(0));
            self.put("_msg", new RuntimeScalar(""));

            RuntimeScalar ref = self.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Raw::Zlib::deflateStream"));

            // In scalar context, return only the object (CPAN code uses: $d ||= Deflate->new(...))
            if (ctx == RuntimeContextType.SCALAR) {
                RuntimeList result = new RuntimeList();
                result.add(ref);
                return result;
            }

            RuntimeList result = new RuntimeList();
            result.add(ref);
            result.add(new RuntimeScalar(Z_OK));
            return result;
        } catch (Exception e) {
            // In scalar context, return undef on error
            if (ctx == RuntimeContextType.SCALAR) {
                RuntimeList result = new RuntimeList();
                result.add(scalarUndef);
                return result;
            }
            RuntimeList result = new RuntimeList();
            result.add(scalarUndef);
            result.add(new RuntimeScalar(Z_STREAM_ERROR));
            return result;
        }
    }

    /**
     * _inflateInit($flags, $windowBits, $bufsize, $dictionary)
     * Returns ($stream, $status)
     */
    public static RuntimeList inflateInit(RuntimeArray args, int ctx) {
        int flags   = args.size() > 0 ? args.get(0).getInt() : 0;
        int wbits   = args.size() > 1 ? args.get(1).getInt() : MAX_WBITS;
        int bufsize = args.size() > 2 ? args.get(2).getInt() : 4096;
        String dict = args.size() > 3 ? args.get(3).toString() : "";

        try {
            boolean nowrap;
            if (wbits < 0) {
                nowrap = true;
            } else if (wbits > 15) {
                // WANT_GZIP or WANT_GZIP_OR_ZLIB - Java Inflater handles raw,
                // gzip headers stripped by IO::Uncompress layer
                nowrap = true;
            } else {
                nowrap = false;
            }

            Inflater inflater = new Inflater(nowrap);

            if (!dict.isEmpty()) {
                byte[] dictBytes = dict.getBytes(StandardCharsets.ISO_8859_1);
                inflater.setDictionary(dictBytes);
            }

            RuntimeHash self = new RuntimeHash();
            self.put("_inflater", new RuntimeScalar(inflater));
            self.put("_flags", new RuntimeScalar(flags));
            self.put("_bufsize", new RuntimeScalar(bufsize));
            self.put("_total_in", new RuntimeScalar(0));
            self.put("_total_out", new RuntimeScalar(0));
            self.put("_crc32", new RuntimeScalar(0L));
            self.put("_adler32", new RuntimeScalar(1L));
            self.put("_dict_adler", new RuntimeScalar(0));
            self.put("_msg", new RuntimeScalar(""));

            RuntimeScalar ref = self.createReference();
            ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Raw::Zlib::inflateStream"));

            // In scalar context, return only the object (CPAN code uses: $i ||= Inflate->new(...))
            if (ctx == RuntimeContextType.SCALAR) {
                RuntimeList result = new RuntimeList();
                result.add(ref);
                return result;
            }

            RuntimeList result = new RuntimeList();
            result.add(ref);
            result.add(new RuntimeScalar(Z_OK));
            return result;
        } catch (Exception e) {
            // In scalar context, return undef on error
            if (ctx == RuntimeContextType.SCALAR) {
                RuntimeList result = new RuntimeList();
                result.add(scalarUndef);
                return result;
            }
            RuntimeList result = new RuntimeList();
            result.add(scalarUndef);
            result.add(new RuntimeScalar(Z_STREAM_ERROR));
            return result;
        }
    }

    // =============================================
    // deflateStream methods (ds_ prefix)
    // =============================================

    /**
     * $d->deflate($input, $output)
     * Compresses input and writes to output scalar.
     * Returns status code.
     */
    public static RuntimeList ds_deflate(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar inputScalar = args.size() > 1 ? args.get(1) : new RuntimeScalar("");
        RuntimeScalar outputRef = args.size() > 2 ? args.get(2) : null;

        Deflater deflater = getDeflater(self);
        if (deflater == null) return new RuntimeScalar(Z_STREAM_ERROR).getList();

        int flags = self.get("_flags").getInt();
        byte[] input = getInputBytes(inputScalar);

        // Track CRC/Adler of uncompressed data
        if ((flags & FLAG_CRC) != 0) {
            long crc = crc32WithSeed(input, self.get("_crc32").getLong() & 0xFFFFFFFFL);
            self.put("_crc32", new RuntimeScalar(crc));
        }
        if ((flags & FLAG_ADLER) != 0) {
            long adler = adler32WithSeed(input, self.get("_adler32").getLong() & 0xFFFFFFFFL);
            self.put("_adler32", new RuntimeScalar(adler));
        }

        deflater.setInput(input);
        int bufsize = self.get("_bufsize").getInt();
        byte[] buf = new byte[Math.max(bufsize, input.length + 256)];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        int count;
        while ((count = deflater.deflate(buf, 0, buf.length, Deflater.NO_FLUSH)) > 0) {
            baos.write(buf, 0, count);
        }

        // Update totals
        long totalIn = self.get("_total_in").getLong() + input.length;
        long totalOut = self.get("_total_out").getLong() + baos.size();
        self.put("_total_in", new RuntimeScalar(totalIn));
        self.put("_total_out", new RuntimeScalar(totalOut));

        // Write output
        if (outputRef != null) {
            writeOutput(outputRef, baos, flags);
        }

        return new RuntimeScalar(Z_OK).getList();
    }

    /**
     * $d->flush($output [, $flush_type])
     */
    public static RuntimeList ds_flush(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar outputRef = args.size() > 1 ? args.get(1) : null;
        int flushType = args.size() > 2 ? args.get(2).getInt() : Z_FINISH;

        Deflater deflater = getDeflater(self);
        if (deflater == null) return new RuntimeScalar(Z_STREAM_ERROR).getList();

        int flags = self.get("_flags").getInt();

        if (flushType == Z_FINISH) {
            deflater.finish();
        }

        int bufsize = self.get("_bufsize").getInt();
        byte[] buf = new byte[Math.max(bufsize, 1024)];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        int javaFlush = (flushType == Z_SYNC_FLUSH) ? Deflater.SYNC_FLUSH :
                        (flushType == Z_FULL_FLUSH) ? Deflater.FULL_FLUSH :
                        Deflater.NO_FLUSH;
        if (flushType == Z_FINISH) {
            javaFlush = Deflater.NO_FLUSH; // finish() already called
        }

        int count;
        do {
            if (flushType == Z_FINISH) {
                count = deflater.deflate(buf);
            } else {
                count = deflater.deflate(buf, 0, buf.length, javaFlush);
            }
            if (count > 0) {
                baos.write(buf, 0, count);
            }
        } while (count > 0);

        long totalOut = self.get("_total_out").getLong() + baos.size();
        self.put("_total_out", new RuntimeScalar(totalOut));

        if (outputRef != null) {
            writeOutput(outputRef, baos, flags);
        }

        int status = deflater.finished() ? Z_STREAM_END : Z_OK;
        return new RuntimeScalar(status).getList();
    }

    public static RuntimeList ds_deflateReset(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        Deflater deflater = getDeflater(self);
        if (deflater == null) return new RuntimeScalar(Z_STREAM_ERROR).getList();
        deflater.reset();
        self.put("_total_in", new RuntimeScalar(0));
        self.put("_total_out", new RuntimeScalar(0));
        self.put("_crc32", new RuntimeScalar(0L));
        self.put("_adler32", new RuntimeScalar(1L));
        return new RuntimeScalar(Z_OK).getList();
    }

    public static RuntimeList ds__deflateParams(RuntimeArray args, int ctx) {
        // _deflateParams($flags, $level, $strategy, $bufsize)
        RuntimeHash self = args.get(0).hashDeref();
        Deflater deflater = getDeflater(self);
        if (deflater == null) return new RuntimeScalar(Z_STREAM_ERROR).getList();

        if (args.size() > 2) {
            int level = args.get(2).getInt();
            deflater.setLevel(level);
            self.put("_level", new RuntimeScalar(level));
        }
        if (args.size() > 3) {
            int strategy = args.get(3).getInt();
            if (strategy == 1) deflater.setStrategy(Deflater.FILTERED);
            else if (strategy == 2) deflater.setStrategy(Deflater.HUFFMAN_ONLY);
            else deflater.setStrategy(Deflater.DEFAULT_STRATEGY);
            self.put("_strategy", new RuntimeScalar(strategy));
        }
        if (args.size() > 4) {
            self.put("_bufsize", args.get(4));
        }
        return new RuntimeScalar(Z_OK).getList();
    }

    /**
     * deflateTune($good_length, $max_lazy, $nice_length, $max_chain)
     * Not supported by java.util.zip.Deflater -- stub returns Z_OK.
     */
    public static RuntimeList ds_deflateTune(RuntimeArray args, int ctx) {
        return new RuntimeScalar(Z_OK).getList();
    }

    public static RuntimeList ds_crc32(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_crc32").getLong()).getList();
    }

    public static RuntimeList ds_adler32(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_adler32").getLong()).getList();
    }

    public static RuntimeList ds_total_in(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_total_in").getLong()).getList();
    }

    public static RuntimeList ds_total_out(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_total_out").getLong()).getList();
    }

    public static RuntimeList ds_msg(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return self.get("_msg").getList();
    }

    public static RuntimeList ds_dict_adler(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_dict_adler").getLong()).getList();
    }

    public static RuntimeList ds_get_Level(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return self.get("_level").getList();
    }

    public static RuntimeList ds_get_Strategy(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return self.get("_strategy").getList();
    }

    public static RuntimeList ds_get_Bufsize(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return self.get("_bufsize").getList();
    }

    public static RuntimeList ds_compressedBytes(RuntimeArray args, int ctx) {
        return ds_total_out(args, ctx);
    }

    public static RuntimeList ds_uncompressedBytes(RuntimeArray args, int ctx) {
        return ds_total_in(args, ctx);
    }

    public static RuntimeList ds_DESTROY(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        Deflater deflater = getDeflater(self);
        if (deflater != null) {
            deflater.end();
        }
        return new RuntimeList();
    }

    // =============================================
    // inflateStream methods (is_ prefix)
    // =============================================

    /**
     * $i->inflate($input, $output [, $eof])
     * Decompresses input, writes to output.
     * Returns status code.
     */
    public static RuntimeList is_inflate(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar inputRef = args.size() > 1 ? args.get(1) : new RuntimeScalar("");
        RuntimeScalar outputRef = args.size() > 2 ? args.get(2) : null;

        Inflater inflater = getInflater(self);
        if (inflater == null) return new RuntimeScalar(Z_STREAM_ERROR).getList();

        int flags = self.get("_flags").getInt();
        int bufsize = self.get("_bufsize").getInt();

        // Get input data (dereference if needed)
        RuntimeScalar actualInput = inputRef;
        if (inputRef.type == RuntimeScalarType.REFERENCE) {
            actualInput = inputRef.scalarDeref();
        }
        byte[] input = actualInput.toString().getBytes(StandardCharsets.ISO_8859_1);
        int inputLenBefore = input.length;

        inflater.setInput(input);

        byte[] buf = new byte[Math.max(bufsize, 4096)];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int status = Z_OK;

        try {
            boolean limitOutput = (flags & FLAG_LIMIT_OUTPUT) != 0;
            int totalInflated = 0;

            while (!inflater.finished() && !inflater.needsInput()) {
                int count = inflater.inflate(buf);
                if (count > 0) {
                    baos.write(buf, 0, count);
                    totalInflated += count;
                    if (limitOutput && totalInflated >= bufsize) {
                        status = Z_BUF_ERROR;
                        break;
                    }
                } else if (inflater.needsDictionary()) {
                    status = Z_NEED_DICT;
                    break;
                } else {
                    break;
                }
            }

            if (inflater.finished()) {
                status = Z_STREAM_END;
            }
        } catch (DataFormatException e) {
            self.put("_msg", new RuntimeScalar(e.getMessage() != null ? e.getMessage() : "data error"));
            return new RuntimeScalar(Z_DATA_ERROR).getList();
        }

        // Track how many input bytes were consumed
        int remaining = inflater.getRemaining();
        int consumed = inputLenBefore - remaining;

        // Update totals
        long totalIn = self.get("_total_in").getLong() + consumed;
        long totalOut = self.get("_total_out").getLong() + baos.size();
        self.put("_total_in", new RuntimeScalar(totalIn));
        self.put("_total_out", new RuntimeScalar(totalOut));

        // Track CRC/Adler of uncompressed output
        byte[] outputBytes = baos.toByteArray();
        if ((flags & FLAG_CRC) != 0) {
            long crc = crc32WithSeed(outputBytes, self.get("_crc32").getLong() & 0xFFFFFFFFL);
            self.put("_crc32", new RuntimeScalar(crc));
        }
        if ((flags & FLAG_ADLER) != 0) {
            long adler = adler32WithSeed(outputBytes, self.get("_adler32").getLong() & 0xFFFFFFFFL);
            self.put("_adler32", new RuntimeScalar(adler));
        }

        // FLAG_CONSUME_INPUT: modify input to remove consumed bytes
        if ((flags & FLAG_CONSUME_INPUT) != 0) {
            if (remaining > 0) {
                String remainStr = new String(input, consumed, remaining, StandardCharsets.ISO_8859_1);
                RuntimeScalar remainScalar = new RuntimeScalar(remainStr);
                remainScalar.type = RuntimeScalarType.BYTE_STRING;
                if (inputRef.type == RuntimeScalarType.REFERENCE) {
                    inputRef.scalarDeref().set(remainScalar);
                } else {
                    inputRef.set(remainScalar);
                }
            } else {
                RuntimeScalar empty = new RuntimeScalar("");
                if (inputRef.type == RuntimeScalarType.REFERENCE) {
                    inputRef.scalarDeref().set(empty);
                } else {
                    inputRef.set(empty);
                }
            }
        }

        // Write output
        if (outputRef != null) {
            writeOutput(outputRef, baos, flags);
        }

        return new RuntimeScalar(status).getList();
    }

    public static RuntimeList is_inflateReset(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        Inflater inflater = getInflater(self);
        if (inflater == null) return new RuntimeScalar(Z_STREAM_ERROR).getList();
        inflater.reset();
        self.put("_total_in", new RuntimeScalar(0));
        self.put("_total_out", new RuntimeScalar(0));
        self.put("_crc32", new RuntimeScalar(0L));
        self.put("_adler32", new RuntimeScalar(1L));
        return new RuntimeScalar(Z_OK).getList();
    }

    public static RuntimeList is_inflateSync(RuntimeArray args, int ctx) {
        // Stub - not commonly used
        return new RuntimeScalar(Z_OK).getList();
    }

    public static RuntimeList is_crc32(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_crc32").getLong()).getList();
    }

    public static RuntimeList is_adler32(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_adler32").getLong()).getList();
    }

    public static RuntimeList is_total_in(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_total_in").getLong()).getList();
    }

    public static RuntimeList is_total_out(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_total_out").getLong()).getList();
    }

    public static RuntimeList is_msg(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return self.get("_msg").getList();
    }

    public static RuntimeList is_dict_adler(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return new RuntimeScalar(self.get("_dict_adler").getLong()).getList();
    }

    public static RuntimeList is_get_Bufsize(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        return self.get("_bufsize").getList();
    }

    public static RuntimeList is_compressedBytes(RuntimeArray args, int ctx) {
        return is_total_in(args, ctx);
    }

    public static RuntimeList is_uncompressedBytes(RuntimeArray args, int ctx) {
        return is_total_out(args, ctx);
    }

    public static RuntimeList is_DESTROY(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        Inflater inflater = getInflater(self);
        if (inflater != null) {
            inflater.end();
        }
        return new RuntimeList();
    }

    // =============================================
    // Helper methods
    // =============================================

    private static byte[] getInputBytes(RuntimeScalar scalar) {
        RuntimeScalar actual = scalar;
        if (scalar.type == RuntimeScalarType.REFERENCE) {
            actual = scalar.scalarDeref();
        }
        return actual.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Deflater getDeflater(RuntimeHash self) {
        RuntimeScalar ds = self.get("_deflater");
        if (ds != null && ds.type == RuntimeScalarType.JAVAOBJECT && ds.value instanceof Deflater) {
            return (Deflater) ds.value;
        }
        return null;
    }

    private static Inflater getInflater(RuntimeHash self) {
        RuntimeScalar is = self.get("_inflater");
        if (is != null && is.type == RuntimeScalarType.JAVAOBJECT && is.value instanceof Inflater) {
            return (Inflater) is.value;
        }
        return null;
    }

    /**
     * Write output bytes to a Perl scalar reference, respecting FLAG_APPEND.
     */
    private static void writeOutput(RuntimeScalar outputRef, ByteArrayOutputStream baos, int flags) {
        String outStr = baos.toString(StandardCharsets.ISO_8859_1);
        RuntimeScalar outScalar;

        if (outputRef.type == RuntimeScalarType.REFERENCE) {
            outScalar = outputRef.scalarDeref();
        } else {
            outScalar = outputRef;
        }

        if ((flags & FLAG_APPEND) != 0) {
            String existing = outScalar.toString();
            RuntimeScalar result = new RuntimeScalar(existing + outStr);
            result.type = RuntimeScalarType.BYTE_STRING;
            outScalar.set(result);
        } else {
            RuntimeScalar result = new RuntimeScalar(outStr);
            result.type = RuntimeScalarType.BYTE_STRING;
            outScalar.set(result);
        }
    }

    private static long crc32WithSeed(byte[] data, long seed) {
        long crc = seed ^ 0xFFFFFFFFL;
        for (byte b : data) {
            crc = CRC32_TABLE[(int) ((crc ^ b) & 0xFF)] ^ (crc >>> 8);
        }
        return (crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    private static long adler32WithSeed(byte[] data, long seed) {
        long s1 = seed & 0xFFFF;
        long s2 = (seed >>> 16) & 0xFFFF;
        for (byte b : data) {
            s1 = (s1 + (b & 0xFF)) % 65521;
            s2 = (s2 + s1) % 65521;
        }
        return (s2 << 16) | s1;
    }
}

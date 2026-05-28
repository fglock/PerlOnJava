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
    private static final int Z_PARTIAL_FLUSH = 1;
    private static final int Z_SYNC_FLUSH = 2;
    private static final int Z_FULL_FLUSH = 3;
    private static final int Z_FINISH = 4;
    private static final int Z_BLOCK = 5;
    private static final int Z_TREES = 6;

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
            crz.registerMethod("_inflateScanInit", "inflateScanInit", null);
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

        String[] inflateScanMethods = {
            "scan", "inflate", "inflateReset", "inflateSync", "_createDeflateStream",
            "createDeflateStream", "getLastBlockOffset", "getEndOffset", "resetLastBlockByte",
            "crc32", "adler32", "total_in", "total_out", "msg",
            "dict_adler", "get_Bufsize",
            "compressedBytes", "uncompressedBytes", "DESTROY"
        };
        registerStreamMethods("Compress::Raw::Zlib::inflateScanStream", inflateScanMethods, "iss_");

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
        byte[] dictBytes = args.size() > 7 ? getInputBytes(args.get(7)) : new byte[0];

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

            long dictAdler = 0;
            if (dictBytes.length > 0) {
                deflater.setDictionary(dictBytes);
                dictAdler = adler32WithSeed(dictBytes, 1L);
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
            self.put("_dict_adler", new RuntimeScalar(dictAdler));
            self.put("_msg", new RuntimeScalar());

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
        byte[] dictBytes = args.size() > 3 ? getInputBytes(args.get(3)) : new byte[0];
        String dict = new String(dictBytes, StandardCharsets.ISO_8859_1);

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

            RuntimeHash self = new RuntimeHash();
            self.put("_inflater", new RuntimeScalar(inflater));
            self.put("_flags", new RuntimeScalar(flags));
            self.put("_bufsize", new RuntimeScalar(bufsize));
            self.put("_total_in", new RuntimeScalar(0));
            self.put("_total_out", new RuntimeScalar(0));
            self.put("_crc32", new RuntimeScalar(0L));
            self.put("_adler32", new RuntimeScalar(1L));
            self.put("_dict_adler", new RuntimeScalar(0));
            RuntimeScalar dictionaryScalar = new RuntimeScalar(dict);
            dictionaryScalar.type = RuntimeScalarType.BYTE_STRING;
            self.put("_dictionary", dictionaryScalar);
            self.put("_dictionary_used", new RuntimeScalar(0));
            self.put("_msg", new RuntimeScalar());

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

    public static RuntimeList inflateScanInit(RuntimeArray args, int ctx) {
        RuntimeList result = inflateInit(args, ctx);
        if (ctx == RuntimeContextType.SCALAR || result.isEmpty()) {
            if (!result.isEmpty()) {
                RuntimeScalar ref = result.elements.get(0).scalar();
                if (ref.getDefinedBoolean()) {
                    ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Raw::Zlib::inflateScanStream"));
                    initializeInflateScanState(ref.hashDeref());
                }
            }
            return result;
        }
        RuntimeScalar ref = result.elements.get(0).scalar();
        if (ref.getDefinedBoolean()) {
            ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Raw::Zlib::inflateScanStream"));
            initializeInflateScanState(ref.hashDeref());
        }
        return result;
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
        self.put("_msg", new RuntimeScalar());

        // Write output
        if (outputRef != null) {
            writeDeflateOutput(self, outputRef, baos, flags, false);
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
        if (flushType < Z_NO_FLUSH || flushType > Z_TREES) {
            self.put("_msg", new RuntimeScalar("stream error"));
            return new RuntimeScalar(Z_STREAM_ERROR).getList();
        }

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
        self.put("_msg", new RuntimeScalar());

        if (outputRef != null) {
            writeDeflateOutput(self, outputRef, baos, flags, flushType == Z_FINISH);
        }

        return new RuntimeScalar(Z_OK).getList();
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
        self.put("_msg", new RuntimeScalar());
        return new RuntimeScalar(Z_OK).getList();
    }

    public static RuntimeList ds__deflateParams(RuntimeArray args, int ctx) {
        // _deflateParams($flags, $level, $strategy, $bufsize)
        RuntimeHash self = args.get(0).hashDeref();
        Deflater deflater = getDeflater(self);
        if (deflater == null) return new RuntimeScalar(Z_STREAM_ERROR).getList();
        int flags = args.size() > 1 ? args.get(1).getInt() : 0;

        if ((flags & 1) != 0 && args.size() > 2) {
            int level = args.get(2).getInt();
            deflater.setLevel(level);
            self.put("_level", new RuntimeScalar(level));
        }
        if ((flags & 2) != 0 && args.size() > 3) {
            int strategy = args.get(3).getInt();
            if (strategy == 1) deflater.setStrategy(Deflater.FILTERED);
            else if (strategy == 2) deflater.setStrategy(Deflater.HUFFMAN_ONLY);
            else deflater.setStrategy(Deflater.DEFAULT_STRATEGY);
            self.put("_strategy", new RuntimeScalar(strategy));
        }
        if ((flags & 4) != 0 && args.size() > 4) {
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
                    if (applyInflaterDictionary(self, inflater)) {
                        continue;
                    }
                    status = Z_NEED_DICT;
                    break;
                } else {
                    break;
                }
            }

            if (inflater.finished()) {
                status = Z_STREAM_END;
            }
            if (status == Z_OK || status == Z_STREAM_END) {
                self.put("_msg", new RuntimeScalar());
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

        if (isInflateScanStream(self)) {
            appendScanInput(self, input, consumed);
            if (status == Z_STREAM_END) {
                updateInflateScanState(self);
            }
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
        self.put("_dict_adler", new RuntimeScalar(0));
        self.put("_dictionary_used", new RuntimeScalar(0));
        self.put("_msg", new RuntimeScalar());
        return new RuntimeScalar(Z_OK).getList();
    }

    public static RuntimeList is_inflateSync(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        Inflater inflater = getInflater(self);
        if (inflater == null || args.size() < 2) {
            return new RuntimeScalar(Z_STREAM_ERROR).getList();
        }

        RuntimeScalar inputScalar = args.get(1);
        RuntimeScalar actualInput = inputScalar.type == RuntimeScalarType.REFERENCE
            ? inputScalar.scalarDeref()
            : inputScalar;

        String previousTail = "";
        RuntimeScalar tailScalar = self.get("_sync_tail");
        if (tailScalar != null && tailScalar.getDefinedBoolean()) {
            previousTail = tailScalar.toString();
        }

        String inputString = actualInput.toString();
        byte[] combined = (previousTail + inputString).getBytes(StandardCharsets.ISO_8859_1);
        int marker = findFullFlushMarker(combined);

        if (marker < 0) {
            int tailLength = Math.min(3, combined.length);
            String tail = new String(combined, combined.length - tailLength, tailLength, StandardCharsets.ISO_8859_1);
            self.put("_sync_tail", new RuntimeScalar(tail));
            setScalarBytes(actualInput, "");
            return new RuntimeScalar(Z_DATA_ERROR).getList();
        }

        int afterMarker = marker + 4;
        String remaining = new String(combined, afterMarker, combined.length - afterMarker, StandardCharsets.ISO_8859_1);
        self.put("_sync_tail", new RuntimeScalar(""));
        self.put("_inflater", new RuntimeScalar(new Inflater(true)));
        setScalarBytes(actualInput, remaining);
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

    public static RuntimeList iss_scan(RuntimeArray args, int ctx) {
        return is_inflate(args, ctx);
    }

    public static RuntimeList iss_inflate(RuntimeArray args, int ctx) {
        return is_inflate(args, ctx);
    }

    public static RuntimeList iss_inflateReset(RuntimeArray args, int ctx) {
        return is_inflateReset(args, ctx);
    }

    public static RuntimeList iss_inflateSync(RuntimeArray args, int ctx) {
        return is_inflateSync(args, ctx);
    }

    public static RuntimeList iss__createDeflateStream(RuntimeArray args, int ctx) {
        return createDeflateStreamFromScan(args, sliceArgs(args, 1), ctx);
    }

    public static RuntimeList iss_createDeflateStream(RuntimeArray args, int ctx) {
        return createDeflateStreamFromScan(args, deflateInitArgsFromScanCreate(args), ctx);
    }

    public static RuntimeList iss_getLastBlockOffset(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar offset = self.get("_scan_last_block_offset");
        return new RuntimeScalar(offset != null ? offset.getLong() : 0).getList();
    }

    public static RuntimeList iss_getEndOffset(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar offset = self.get("_scan_end_offset");
        if (offset != null) {
            return offset.getList();
        }
        return is_total_in(args, ctx);
    }

    public static RuntimeList iss_resetLastBlockByte(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        if (args.size() > 1) {
            RuntimeScalar byteScalar = args.get(1).scalar();
            String value = byteScalar.toString();
            if (!value.isEmpty()) {
                RuntimeScalar maskScalar = self.get("_scan_last_block_mask");
                int mask = maskScalar != null ? maskScalar.getInt() : 1;
                int rewritten = (value.charAt(0) & 0xFF) ^ mask;
                setScalarBytes(byteScalar, Character.toString((char) rewritten));
            }
        }
        return new RuntimeScalar(Z_OK).getList();
    }

    public static RuntimeList iss_crc32(RuntimeArray args, int ctx) {
        return is_crc32(args, ctx);
    }

    public static RuntimeList iss_adler32(RuntimeArray args, int ctx) {
        return is_adler32(args, ctx);
    }

    public static RuntimeList iss_total_in(RuntimeArray args, int ctx) {
        return is_total_in(args, ctx);
    }

    public static RuntimeList iss_total_out(RuntimeArray args, int ctx) {
        return is_total_out(args, ctx);
    }

    public static RuntimeList iss_msg(RuntimeArray args, int ctx) {
        return is_msg(args, ctx);
    }

    public static RuntimeList iss_dict_adler(RuntimeArray args, int ctx) {
        return is_dict_adler(args, ctx);
    }

    public static RuntimeList iss_get_Bufsize(RuntimeArray args, int ctx) {
        return is_get_Bufsize(args, ctx);
    }

    public static RuntimeList iss_compressedBytes(RuntimeArray args, int ctx) {
        return is_compressedBytes(args, ctx);
    }

    public static RuntimeList iss_uncompressedBytes(RuntimeArray args, int ctx) {
        return is_uncompressedBytes(args, ctx);
    }

    public static RuntimeList iss_DESTROY(RuntimeArray args, int ctx) {
        return is_DESTROY(args, ctx);
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

    private static boolean applyInflaterDictionary(RuntimeHash self, Inflater inflater) {
        RuntimeScalar used = self.get("_dictionary_used");
        if (used != null && used.getBoolean()) {
            return false;
        }

        RuntimeScalar dictionary = self.get("_dictionary");
        if (dictionary == null || !dictionary.getDefinedBoolean()) {
            return false;
        }

        byte[] dictBytes = getInputBytes(dictionary);
        if (dictBytes.length == 0) {
            return false;
        }

        try {
            inflater.setDictionary(dictBytes);
            self.put("_dict_adler", new RuntimeScalar(adler32WithSeed(dictBytes, 1L)));
            self.put("_dictionary_used", new RuntimeScalar(1));
            self.put("_msg", new RuntimeScalar());
            return true;
        } catch (IllegalArgumentException e) {
            self.put("_msg", new RuntimeScalar(e.getMessage() != null ? e.getMessage() : "dictionary error"));
            return false;
        }
    }

    private static RuntimeArray sliceArgs(RuntimeArray args, int start) {
        RuntimeArray sliced = new RuntimeArray(Math.max(0, args.size() - start));
        for (int i = start; i < args.size(); i++) {
            sliced.elements.add(args.get(i));
        }
        return sliced;
    }

    private static void initializeInflateScanState(RuntimeHash self) {
        self.put("_scan_last_block_offset", new RuntimeScalar(0));
        self.put("_scan_last_block_mask", new RuntimeScalar(1));
        self.put("_scan_end_offset", new RuntimeScalar(0));
        self.put("_scan_prime_bits", new RuntimeScalar(0));
        self.put("_scan_prime_carry", new RuntimeScalar(0));
        self.put("_scan_prime_final_emitted", new RuntimeScalar(0));
        self.put("_scan_input", new RuntimeScalar(new ByteArrayOutputStream()));
    }

    private static boolean isInflateScanStream(RuntimeHash self) {
        return self.get("_scan_input") != null;
    }

    private static void appendScanInput(RuntimeHash self, byte[] input, int consumed) {
        if (consumed <= 0) {
            return;
        }
        RuntimeScalar scanInput = self.get("_scan_input");
        ByteArrayOutputStream baos;
        if (scanInput != null && scanInput.type == RuntimeScalarType.JAVAOBJECT
                && scanInput.value instanceof ByteArrayOutputStream existing) {
            baos = existing;
        } else {
            baos = new ByteArrayOutputStream();
            self.put("_scan_input", new RuntimeScalar(baos));
        }
        baos.write(input, 0, Math.min(consumed, input.length));
    }

    private static void updateInflateScanState(RuntimeHash self) {
        RuntimeScalar scanInput = self.get("_scan_input");
        if (scanInput == null || scanInput.type != RuntimeScalarType.JAVAOBJECT
                || !(scanInput.value instanceof ByteArrayOutputStream baos)) {
            return;
        }

        byte[] data = baos.toByteArray();
        DeflateScanInfo info = DeflateScanInfo.scan(data);
        if (info == null) {
            self.put("_scan_end_offset", self.get("_total_in"));
            self.put("_scan_prime_bits", new RuntimeScalar(0));
            self.put("_scan_prime_carry", new RuntimeScalar(0));
            return;
        }

        int endByteOffset = info.endBit / 8;
        int primeBits = info.endBit & 7;
        int primeCarry = 0;
        if (primeBits != 0 && endByteOffset < data.length) {
            primeCarry = data[endByteOffset] & ((1 << primeBits) - 1);
        }

        self.put("_scan_last_block_offset", new RuntimeScalar(info.lastBlockBit / 8));
        self.put("_scan_last_block_mask", new RuntimeScalar(1 << (info.lastBlockBit & 7)));
        self.put("_scan_end_offset", new RuntimeScalar(endByteOffset));
        self.put("_scan_prime_bits", new RuntimeScalar(primeBits));
        self.put("_scan_prime_carry", new RuntimeScalar(primeCarry));
        self.put("_scan_prime_final_emitted", new RuntimeScalar(0));
    }

    private static RuntimeList createDeflateStreamFromScan(RuntimeArray args, RuntimeArray initArgs, int ctx) {
        RuntimeHash scanSelf = args.get(0).hashDeref();
        RuntimeList result = deflateInit(initArgs, ctx);
        if (!result.isEmpty()) {
            RuntimeScalar ref = result.elements.get(0).scalar();
            if (ref.getDefinedBoolean()) {
                RuntimeHash deflateSelf = ref.hashDeref();
                copyScanScalar(scanSelf, deflateSelf, "_scan_prime_bits", "_prime_bits");
                copyScanScalar(scanSelf, deflateSelf, "_scan_prime_carry", "_prime_carry");
                deflateSelf.put("_prime_final_emitted", new RuntimeScalar(0));
            }
        }
        return result;
    }

    private static void copyScanScalar(RuntimeHash from, RuntimeHash to, String fromKey, String toKey) {
        RuntimeScalar value = from.get(fromKey);
        if (value != null) {
            to.put(toKey, new RuntimeScalar(value.getLong()));
        }
    }

    private static RuntimeArray deflateInitArgsFromScanCreate(RuntimeArray args) {
        if (args.size() > 1 && isIntegerLike(args.get(1))) {
            return sliceArgs(args, 1);
        }

        int flags = 0;
        int level = Z_DEFAULT_COMPRESSION;
        int method = 8;
        int windowBits = -MAX_WBITS;
        int memLevel = MAX_MEM_LEVEL;
        int strategy = 0;
        int bufsize = 4096;

        for (int i = 1; i < args.size() - 1; i += 2) {
            String key = normalizeOption(args.get(i).toString());
            RuntimeScalar value = args.get(i + 1);
            switch (key) {
                case "AppendOutput" -> {
                    if (value.getBoolean()) flags |= FLAG_APPEND;
                }
                case "CRC32" -> {
                    if (value.getBoolean()) flags |= FLAG_CRC;
                }
                case "ADLER32" -> {
                    if (value.getBoolean()) flags |= FLAG_ADLER;
                }
                case "Bufsize" -> bufsize = value.getInt();
                case "Level" -> level = value.getInt();
                case "Method" -> method = value.getInt();
                case "WindowBits" -> windowBits = value.getInt();
                case "MemLevel" -> memLevel = value.getInt();
                case "Strategy" -> strategy = value.getInt();
                default -> throw new PerlCompilerException(
                    "Compress::Raw::Zlib::InflateScan::createDeflateStream: unknown key value(s) " + key);
            }
        }

        if (bufsize < 1) {
            throw new PerlCompilerException(
                "Compress::Raw::Zlib::InflateScan::createDeflateStream: Bufsize must be >= 1, you specified "
                    + bufsize);
        }

        RuntimeArray initArgs = new RuntimeArray(7);
        initArgs.elements.add(new RuntimeScalar(flags));
        initArgs.elements.add(new RuntimeScalar(level));
        initArgs.elements.add(new RuntimeScalar(method));
        initArgs.elements.add(new RuntimeScalar(windowBits));
        initArgs.elements.add(new RuntimeScalar(memLevel));
        initArgs.elements.add(new RuntimeScalar(strategy));
        initArgs.elements.add(new RuntimeScalar(bufsize));
        return initArgs;
    }

    private static boolean isIntegerLike(RuntimeScalar scalar) {
        String value = scalar.toString();
        if (value.isEmpty()) return false;
        int start = (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (start == value.length()) return false;
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeOption(String key) {
        while (key.startsWith("-")) {
            key = key.substring(1);
        }
        return key;
    }

    private static int findFullFlushMarker(byte[] input) {
        for (int i = 0; i <= input.length - 4; i++) {
            if (input[i] == 0
                    && input[i + 1] == 0
                    && (input[i + 2] & 0xFF) == 0xFF
                    && (input[i + 3] & 0xFF) == 0xFF) {
                return i;
            }
        }
        return -1;
    }

    private static void setScalarBytes(RuntimeScalar scalar, String value) {
        RuntimeScalar replacement = new RuntimeScalar(value);
        replacement.type = RuntimeScalarType.BYTE_STRING;
        scalar.set(replacement);
    }

    /**
     * Write output bytes to a Perl scalar reference, respecting FLAG_APPEND.
     */
    private static void writeDeflateOutput(RuntimeHash self, RuntimeScalar outputRef,
                                           ByteArrayOutputStream baos, int flags, boolean finishing) {
        RuntimeScalar bitsScalar = self.get("_prime_bits");
        int primeBits = bitsScalar != null ? bitsScalar.getInt() : 0;
        if (primeBits <= 0 || primeBits >= 8) {
            writeOutput(outputRef, baos, flags);
            return;
        }

        byte[] input = baos.toByteArray();
        ByteArrayOutputStream shifted = new ByteArrayOutputStream(input.length + (finishing ? 1 : 0));
        RuntimeScalar carryScalar = self.get("_prime_carry");
        int carry = carryScalar != null ? carryScalar.getInt() : 0;

        for (byte value : input) {
            int b = value & 0xFF;
            shifted.write((carry | ((b << primeBits) & 0xFF)) & 0xFF);
            carry = b >>> (8 - primeBits);
        }

        RuntimeScalar finalEmittedScalar = self.get("_prime_final_emitted");
        boolean finalEmitted = finalEmittedScalar != null && finalEmittedScalar.getBoolean();
        if (finishing && !finalEmitted) {
            shifted.write(carry & 0xFF);
            carry = 0;
            self.put("_prime_final_emitted", new RuntimeScalar(1));
        }

        self.put("_prime_carry", new RuntimeScalar(carry));
        writeOutput(outputRef, shifted, flags);
    }

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

    private static final class DeflateScanInfo {
        final int lastBlockBit;
        final int endBit;

        DeflateScanInfo(int lastBlockBit, int endBit) {
            this.lastBlockBit = lastBlockBit;
            this.endBit = endBit;
        }

        static DeflateScanInfo scan(byte[] data) {
            try {
                BitReader reader = new BitReader(data);
                int lastBlockBit;
                boolean last;
                do {
                    lastBlockBit = reader.bitPosition();
                    last = reader.readBits(1) != 0;
                    int type = reader.readBits(2);
                    switch (type) {
                        case 0 -> skipStored(reader);
                        case 1 -> skipCompressed(reader, fixedLiteralLengthTree(), fixedDistanceTree());
                        case 2 -> {
                            HuffmanTrees trees = readDynamicTrees(reader);
                            skipCompressed(reader, trees.literalLength, trees.distance);
                        }
                        default -> {
                            return null;
                        }
                    }
                } while (!last);
                return new DeflateScanInfo(lastBlockBit, reader.bitPosition());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static void skipStored(BitReader reader) {
            reader.alignToByte();
            int len = reader.readBits(16);
            int nlen = reader.readBits(16);
            if (((len ^ 0xFFFF) & 0xFFFF) != nlen) {
                throw new IllegalArgumentException("stored block length mismatch");
            }
            reader.skipBits(len * 8);
        }

        private static void skipCompressed(BitReader reader, HuffmanTree literalLength, HuffmanTree distance) {
            while (true) {
                int symbol = literalLength.decode(reader);
                if (symbol < 256) {
                    continue;
                }
                if (symbol == 256) {
                    return;
                }
                if (symbol > 285) {
                    throw new IllegalArgumentException("bad length symbol");
                }

                int lengthIndex = symbol - 257;
                reader.skipBits(LENGTH_EXTRA_BITS[lengthIndex]);
                int distanceSymbol = distance.decode(reader);
                if (distanceSymbol < 0 || distanceSymbol >= DISTANCE_EXTRA_BITS.length) {
                    throw new IllegalArgumentException("bad distance symbol");
                }
                reader.skipBits(DISTANCE_EXTRA_BITS[distanceSymbol]);
            }
        }

        private static HuffmanTrees readDynamicTrees(BitReader reader) {
            int hlit = reader.readBits(5) + 257;
            int hdist = reader.readBits(5) + 1;
            int hclen = reader.readBits(4) + 4;

            int[] codeLengthLengths = new int[19];
            for (int i = 0; i < hclen; i++) {
                codeLengthLengths[CODE_LENGTH_ORDER[i]] = reader.readBits(3);
            }
            HuffmanTree codeLengthTree = new HuffmanTree(codeLengthLengths);

            int[] lengths = new int[hlit + hdist];
            int index = 0;
            while (index < lengths.length) {
                int symbol = codeLengthTree.decode(reader);
                if (symbol <= 15) {
                    lengths[index++] = symbol;
                } else if (symbol == 16) {
                    if (index == 0) {
                        throw new IllegalArgumentException("repeat with no previous length");
                    }
                    int repeat = reader.readBits(2) + 3;
                    int previous = lengths[index - 1];
                    for (int i = 0; i < repeat && index < lengths.length; i++) {
                        lengths[index++] = previous;
                    }
                } else if (symbol == 17) {
                    int repeat = reader.readBits(3) + 3;
                    for (int i = 0; i < repeat && index < lengths.length; i++) {
                        lengths[index++] = 0;
                    }
                } else if (symbol == 18) {
                    int repeat = reader.readBits(7) + 11;
                    for (int i = 0; i < repeat && index < lengths.length; i++) {
                        lengths[index++] = 0;
                    }
                } else {
                    throw new IllegalArgumentException("bad code length symbol");
                }
            }

            int[] litLen = new int[hlit];
            System.arraycopy(lengths, 0, litLen, 0, hlit);
            int[] dist = new int[hdist];
            System.arraycopy(lengths, hlit, dist, 0, hdist);
            return new HuffmanTrees(new HuffmanTree(litLen), new HuffmanTree(dist));
        }

        private static HuffmanTree fixedLiteralLengthTree() {
            int[] lengths = new int[288];
            for (int i = 0; i <= 143; i++) lengths[i] = 8;
            for (int i = 144; i <= 255; i++) lengths[i] = 9;
            for (int i = 256; i <= 279; i++) lengths[i] = 7;
            for (int i = 280; i <= 287; i++) lengths[i] = 8;
            return new HuffmanTree(lengths);
        }

        private static HuffmanTree fixedDistanceTree() {
            int[] lengths = new int[32];
            for (int i = 0; i < lengths.length; i++) {
                lengths[i] = 5;
            }
            return new HuffmanTree(lengths);
        }

        private static int reverseBits(int code, int length) {
            int reversed = 0;
            for (int i = 0; i < length; i++) {
                reversed = (reversed << 1) | (code & 1);
                code >>>= 1;
            }
            return reversed;
        }

        private static final int[] LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1,
            2, 2, 2, 2,
            3, 3, 3, 3,
            4, 4, 4, 4,
            5, 5, 5, 5,
            0
        };

        private static final int[] DISTANCE_EXTRA_BITS = {
            0, 0, 0, 0,
            1, 1,
            2, 2,
            3, 3,
            4, 4,
            5, 5,
            6, 6,
            7, 7,
            8, 8,
            9, 9,
            10, 10,
            11, 11,
            12, 12,
            13, 13
        };

        private static final int[] CODE_LENGTH_ORDER = {
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
        };

        private record HuffmanTrees(HuffmanTree literalLength, HuffmanTree distance) {}

        private static final class HuffmanTree {
            private final Map<Integer, Integer> symbols = new HashMap<>();
            private final int maxBits;

            HuffmanTree(int[] lengths) {
                int max = 0;
                for (int length : lengths) {
                    max = Math.max(max, length);
                }
                this.maxBits = max;
                int[] counts = new int[max + 1];
                for (int length : lengths) {
                    if (length > 0) {
                        counts[length]++;
                    }
                }

                int[] nextCode = new int[max + 1];
                int code = 0;
                for (int bits = 1; bits <= max; bits++) {
                    code = (code + counts[bits - 1]) << 1;
                    nextCode[bits] = code;
                }

                for (int symbol = 0; symbol < lengths.length; symbol++) {
                    int length = lengths[symbol];
                    if (length == 0) {
                        continue;
                    }
                    int canonical = nextCode[length]++;
                    int reversed = reverseBits(canonical, length);
                    symbols.put((length << 16) | reversed, symbol);
                }
            }

            int decode(BitReader reader) {
                int code = 0;
                for (int length = 1; length <= maxBits; length++) {
                    code |= reader.readBits(1) << (length - 1);
                    Integer symbol = symbols.get((length << 16) | code);
                    if (symbol != null) {
                        return symbol;
                    }
                }
                throw new IllegalArgumentException("bad huffman code");
            }
        }

        private static final class BitReader {
            private final byte[] data;
            private int bitPosition;

            BitReader(byte[] data) {
                this.data = data;
            }

            int bitPosition() {
                return bitPosition;
            }

            int readBits(int count) {
                if (count < 0 || bitPosition + count > data.length * 8) {
                    throw new IllegalArgumentException("deflate stream is truncated");
                }
                int value = 0;
                for (int i = 0; i < count; i++) {
                    int byteIndex = bitPosition >>> 3;
                    int bitIndex = bitPosition & 7;
                    value |= ((data[byteIndex] >>> bitIndex) & 1) << i;
                    bitPosition++;
                }
                return value;
            }

            void skipBits(int count) {
                if (count < 0 || bitPosition + count > data.length * 8) {
                    throw new IllegalArgumentException("deflate stream is truncated");
                }
                bitPosition += count;
            }

            void alignToByte() {
                int remainder = bitPosition & 7;
                if (remainder != 0) {
                    skipBits(8 - remainder);
                }
            }
        }
    }
}

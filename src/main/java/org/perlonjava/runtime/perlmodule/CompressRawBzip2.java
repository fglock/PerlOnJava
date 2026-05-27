package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Java XS backend for Compress::Raw::Bzip2.
 *
 * <p>The byte-level bzip2 work is shared with {@link CompressBzip2}; this class
 * provides the lower-level stateful API expected by IO::Compress and the CPAN
 * Compress::Raw::Bzip2 distribution.</p>
 */
public class CompressRawBzip2 extends PerlModuleBase {

    public static String XS_VERSION = "2.218";

    private static final int BZ_OK = 0;
    private static final int BZ_RUN_OK = 1;
    private static final int BZ_FLUSH_OK = 2;
    private static final int BZ_FINISH_OK = 3;
    private static final int BZ_STREAM_END = 4;
    private static final int BZ_SEQUENCE_ERROR = -1;
    private static final int BZ_PARAM_ERROR = -2;
    private static final int BZ_MEM_ERROR = -3;
    private static final int BZ_DATA_ERROR = -4;
    private static final int BZ_DATA_ERROR_MAGIC = -5;
    private static final int BZ_IO_ERROR = -6;
    private static final int BZ_UNEXPECTED_EOF = -7;
    private static final int BZ_OUTBUFF_FULL = -8;
    private static final int BZ_CONFIG_ERROR = -9;

    private static final int FLAG_APPEND_OUTPUT = 1;
    private static final int FLAG_CONSUME_INPUT = 8;
    private static final int FLAG_LIMIT_OUTPUT = 16;

    private static final Map<String, Integer> CONSTANTS = new HashMap<>();
    static {
        CONSTANTS.put("BZ_RUN", 0);
        CONSTANTS.put("BZ_FLUSH", 1);
        CONSTANTS.put("BZ_FINISH", 2);
        CONSTANTS.put("BZ_OK", BZ_OK);
        CONSTANTS.put("BZ_RUN_OK", BZ_RUN_OK);
        CONSTANTS.put("BZ_FLUSH_OK", BZ_FLUSH_OK);
        CONSTANTS.put("BZ_FINISH_OK", BZ_FINISH_OK);
        CONSTANTS.put("BZ_STREAM_END", BZ_STREAM_END);
        CONSTANTS.put("BZ_SEQUENCE_ERROR", BZ_SEQUENCE_ERROR);
        CONSTANTS.put("BZ_PARAM_ERROR", BZ_PARAM_ERROR);
        CONSTANTS.put("BZ_MEM_ERROR", BZ_MEM_ERROR);
        CONSTANTS.put("BZ_DATA_ERROR", BZ_DATA_ERROR);
        CONSTANTS.put("BZ_DATA_ERROR_MAGIC", BZ_DATA_ERROR_MAGIC);
        CONSTANTS.put("BZ_IO_ERROR", BZ_IO_ERROR);
        CONSTANTS.put("BZ_UNEXPECTED_EOF", BZ_UNEXPECTED_EOF);
        CONSTANTS.put("BZ_OUTBUFF_FULL", BZ_OUTBUFF_FULL);
        CONSTANTS.put("BZ_CONFIG_ERROR", BZ_CONFIG_ERROR);
    }

    public CompressRawBzip2() {
        super("Compress::Raw::Bzip2", false);
    }

    public static void initialize() {
        CompressRawBzip2 crb = new CompressRawBzip2();
        try {
            crb.registerMethod("constant", null);
            crb.registerMethod("bzlibversion", null);
            crb.registerMethod("new", "newBzip2", null);
            crb.registerMethod("BZ_RUN", null);
            crb.registerMethod("BZ_FLUSH", null);
            crb.registerMethod("BZ_FINISH", null);
            crb.registerMethod("BZ_OK", null);
            crb.registerMethod("BZ_RUN_OK", null);
            crb.registerMethod("BZ_FLUSH_OK", null);
            crb.registerMethod("BZ_FINISH_OK", null);
            crb.registerMethod("BZ_STREAM_END", null);
            crb.registerMethod("BZ_SEQUENCE_ERROR", null);
            crb.registerMethod("BZ_PARAM_ERROR", null);
            crb.registerMethod("BZ_MEM_ERROR", null);
            crb.registerMethod("BZ_DATA_ERROR", null);
            crb.registerMethod("BZ_DATA_ERROR_MAGIC", null);
            crb.registerMethod("BZ_IO_ERROR", null);
            crb.registerMethod("BZ_UNEXPECTED_EOF", null);
            crb.registerMethod("BZ_OUTBUFF_FULL", null);
            crb.registerMethod("BZ_CONFIG_ERROR", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Compress::Raw::Bzip2 method: " + e.getMessage());
        }

        registerStreamMethods("Compress::Raw::Bzip2", new String[] {
                "bzdeflate", "bzflush", "bzclose", "total_in_lo32", "total_out_lo32",
                "compressedBytes", "uncompressedBytes", "DESTROY"
        }, "bz_");

        registerStreamMethods("Compress::Raw::Bunzip2", new String[] {
                "new", "bzinflate", "inflateCount", "status", "total_in_lo32",
                "total_out_lo32", "compressedBytes", "uncompressedBytes", "DESTROY"
        }, "bun_");

        GlobalVariable.getGlobalVariable("Compress::Raw::Bzip2::XS_VERSION")
            .set(new RuntimeScalar(XS_VERSION));
    }

    private static void registerStreamMethods(String packageName, String[] methods, String javaPrefix) {
        for (String method : methods) {
            try {
                MethodHandle mh = RuntimeCode.lookup.findStatic(
                        CompressRawBzip2.class, javaPrefix + method, RuntimeCode.methodType);
                RuntimeCode code = new RuntimeCode(mh, null, null);
                code.isStatic = true;
                code.packageName = packageName;
                code.subName = method;
                GlobalVariable.getGlobalCodeRef(
                        NameNormalizer.normalizeVariableName(method, packageName))
                    .set(new RuntimeScalar(code));
            } catch (Exception e) {
                System.err.println("Warning: Missing " + packageName + "::" + method + ": " + e.getMessage());
            }
        }
    }

    public static RuntimeList constant(RuntimeArray args, int ctx) {
        String name = args.size() > 0 ? args.get(0).toString() : "";
        RuntimeList result = new RuntimeList();
        Integer value = CONSTANTS.get(name);
        if (value == null) {
            result.add(new RuntimeScalar(name + " is not a valid Bzip2 macro"));
        } else {
            result.add(scalarUndef);
            result.add(new RuntimeScalar(value));
        }
        return result;
    }

    public static RuntimeList bzlibversion(RuntimeArray args, int ctx) {
        return new RuntimeScalar("1.0.8").getList();
    }

    public static RuntimeList newBzip2(RuntimeArray args, int ctx) {
        int offset = classArgOffset(args, "Compress::Raw::Bzip2");
        if (args.size() - offset > 4) {
            return WarnDie.die(new RuntimeScalar(
                    "Usage: Compress::Raw::Bzip2::new(className, appendOut=1, blockSize100k=1, workfactor=0, verbosity=0)"),
                    new RuntimeScalar(" at ")).getList();
        }

        Bzip2Options options = Bzip2Options.forCompress(args, offset);
        if (options.blockSize100k < 1 || options.blockSize100k > 9) {
            return constructorResult(scalarUndef, BZ_PARAM_ERROR, ctx);
        }

        RuntimeHash self = new RuntimeHash();
        self.put("_input", new RuntimeScalar(new ByteArrayOutputStream()));
        self.put("_flags", new RuntimeScalar(options.appendOutput ? FLAG_APPEND_OUTPUT : 0));
        self.put("_block_size", new RuntimeScalar(options.blockSize100k));
        self.put("_closed", new RuntimeScalar(0));
        self.put("_compressed_bytes", new RuntimeScalar(0));
        self.put("_uncompressed_bytes", new RuntimeScalar(0));
        self.put("_last_error", new RuntimeScalar(BZ_OK));

        RuntimeScalar ref = self.createReference();
        ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Raw::Bzip2"));
        return constructorResult(ref, BZ_OK, ctx);
    }

    public static RuntimeList bun_new(RuntimeArray args, int ctx) {
        int offset = classArgOffset(args, "Compress::Raw::Bunzip2");
        Bzip2Options options = Bzip2Options.forUncompress(args, offset);

        int flags = 0;
        if (options.appendOutput) flags |= FLAG_APPEND_OUTPUT;
        if (options.consumeInput) flags |= FLAG_CONSUME_INPUT;
        if (options.limitOutput) flags |= FLAG_LIMIT_OUTPUT | FLAG_CONSUME_INPUT;

        RuntimeHash self = new RuntimeHash();
        self.put("_input", new RuntimeScalar(new ByteArrayOutputStream()));
        self.put("_flags", new RuntimeScalar(flags));
        self.put("_bufsize", new RuntimeScalar(options.bufsize));
        self.put("_finished", new RuntimeScalar(0));
        self.put("_emitted", new RuntimeScalar(0));
        self.put("_inflate_count", new RuntimeScalar(0));
        self.put("_compressed_bytes", new RuntimeScalar(0));
        self.put("_uncompressed_bytes", new RuntimeScalar(0));
        self.put("_last_error", new RuntimeScalar(BZ_OK));

        RuntimeScalar ref = self.createReference();
        ReferenceOperators.bless(ref, new RuntimeScalar("Compress::Raw::Bunzip2"));
        return constructorResult(ref, BZ_OK, ctx);
    }

    private static RuntimeList constructorResult(RuntimeScalar object, int status, int ctx) {
        RuntimeList result = new RuntimeList();
        result.add(object);
        if (ctx != RuntimeContextType.SCALAR) {
            result.add(new RuntimeScalar(status));
        }
        return result;
    }

    public static RuntimeList bz_bzdeflate(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        byte[] input = args.size() > 1 ? inputBytes(args.get(1)) : new byte[0];
        RuntimeScalar output = args.size() > 2 ? args.get(2) : null;
        int flags = self.get("_flags").getInt();

        try {
            inputBuffer(self).write(input);
            self.put("_uncompressed_bytes",
                    new RuntimeScalar(self.get("_uncompressed_bytes").getLong() + input.length));
            self.put("_last_error", new RuntimeScalar(BZ_RUN_OK));
            if (output != null) writeOutput(output, new byte[0], flags);
            return new RuntimeScalar(BZ_RUN_OK).getList();
        } catch (IOException e) {
            self.put("_last_error", new RuntimeScalar(BZ_IO_ERROR));
            return new RuntimeScalar(BZ_IO_ERROR).getList();
        }
    }

    public static RuntimeList bz_bzflush(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar output = args.size() > 1 ? args.get(1) : null;
        int flags = self.get("_flags").getInt();
        if (output != null) writeOutput(output, new byte[0], flags);
        self.put("_last_error", new RuntimeScalar(BZ_RUN_OK));
        return new RuntimeScalar(BZ_RUN_OK).getList();
    }

    public static RuntimeList bz_bzclose(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar output = args.size() > 1 ? args.get(1) : null;
        int flags = self.get("_flags").getInt();
        if (self.get("_closed").getBoolean()) {
            if (output != null) writeOutput(output, new byte[0], flags);
            return new RuntimeScalar(BZ_SEQUENCE_ERROR).getList();
        }

        try {
            byte[] compressed = CompressBzip2.compressBytes(
                    inputBuffer(self).toByteArray(), self.get("_block_size").getInt());
            self.put("_closed", new RuntimeScalar(1));
            self.put("_compressed_bytes",
                    new RuntimeScalar(self.get("_compressed_bytes").getLong() + compressed.length));
            self.put("_last_error", new RuntimeScalar(BZ_STREAM_END));
            if (output != null) writeOutput(output, compressed, flags);
            return new RuntimeScalar(BZ_STREAM_END).getList();
        } catch (IOException e) {
            self.put("_last_error", new RuntimeScalar(BZ_IO_ERROR));
            return new RuntimeScalar(BZ_IO_ERROR).getList();
        }
    }

    public static RuntimeList bun_bzinflate(RuntimeArray args, int ctx) {
        RuntimeHash self = args.get(0).hashDeref();
        RuntimeScalar inputScalar = args.size() > 1 ? args.get(1) : new RuntimeScalar("");
        RuntimeScalar output = args.size() > 2 ? args.get(2) : null;
        int flags = self.get("_flags").getInt();
        boolean consumeInput = (flags & FLAG_CONSUME_INPUT) != 0;

        RuntimeScalar actualInput = actualScalar(inputScalar);
        if (consumeInput && isReadOnly(actualInput)) {
            return WarnDie.die(new RuntimeScalar(
                    "Compress::Raw::Bunzip2::bzinflate input parameter cannot be read-only when ConsumeInput is specified"),
                    new RuntimeScalar(" at ")).getList();
        }

        byte[] currentInput = toBytes(actualInput);
        ByteArrayOutputStream accumulated = inputBuffer(self);
        int previousLength = accumulated.size();
        try {
            accumulated.write(currentInput);
        } catch (IOException e) {
            self.put("_last_error", new RuntimeScalar(BZ_IO_ERROR));
            return new RuntimeScalar(BZ_IO_ERROR).getList();
        }

        byte[] allInput = accumulated.toByteArray();
        try {
            CompressBzip2.DecompressResult decompressed = CompressBzip2.decompressFirstStream(allInput);
            int emitted = self.get("_emitted").getInt();
            byte[] delta = slice(decompressed.output, emitted, decompressed.output.length);
            self.put("_emitted", new RuntimeScalar(decompressed.output.length));
            self.put("_inflate_count", new RuntimeScalar(delta.length));
            self.put("_compressed_bytes", new RuntimeScalar(decompressed.consumed));
            self.put("_uncompressed_bytes", new RuntimeScalar(decompressed.output.length));
            self.put("_finished", new RuntimeScalar(1));
            self.put("_last_error", new RuntimeScalar(BZ_STREAM_END));

            if (consumeInput) {
                int consumedFromCurrent = Math.max(0, decompressed.consumed - previousLength);
                consumedFromCurrent = Math.min(consumedFromCurrent, currentInput.length);
                setBytes(actualInput, slice(currentInput, consumedFromCurrent, currentInput.length));
            }
            if (output != null) writeOutput(output, delta, flags);
            return new RuntimeScalar(BZ_STREAM_END).getList();
        } catch (IOException e) {
            self.put("_inflate_count", new RuntimeScalar(0));
            self.put("_compressed_bytes",
                    new RuntimeScalar(self.get("_compressed_bytes").getLong() + currentInput.length));
            self.put("_last_error", new RuntimeScalar(BZ_OK));
            if (consumeInput) setBytes(actualInput, new byte[0]);
            if (output != null) writeOutput(output, new byte[0], flags);
            return new RuntimeScalar(BZ_OK).getList();
        }
    }

    public static RuntimeList bun_inflateCount(RuntimeArray args, int ctx) {
        return args.get(0).hashDeref().get("_inflate_count").getList();
    }

    public static RuntimeList bun_status(RuntimeArray args, int ctx) {
        return args.get(0).hashDeref().get("_last_error").getList();
    }

    public static RuntimeList bz_total_in_lo32(RuntimeArray args, int ctx) {
        return bz_uncompressedBytes(args, ctx);
    }

    public static RuntimeList bz_total_out_lo32(RuntimeArray args, int ctx) {
        return bz_compressedBytes(args, ctx);
    }

    public static RuntimeList bz_compressedBytes(RuntimeArray args, int ctx) {
        return args.get(0).hashDeref().get("_compressed_bytes").getList();
    }

    public static RuntimeList bz_uncompressedBytes(RuntimeArray args, int ctx) {
        return args.get(0).hashDeref().get("_uncompressed_bytes").getList();
    }

    public static RuntimeList bun_total_in_lo32(RuntimeArray args, int ctx) {
        return bun_compressedBytes(args, ctx);
    }

    public static RuntimeList bun_total_out_lo32(RuntimeArray args, int ctx) {
        return bun_uncompressedBytes(args, ctx);
    }

    public static RuntimeList bun_compressedBytes(RuntimeArray args, int ctx) {
        return args.get(0).hashDeref().get("_compressed_bytes").getList();
    }

    public static RuntimeList bun_uncompressedBytes(RuntimeArray args, int ctx) {
        return args.get(0).hashDeref().get("_uncompressed_bytes").getList();
    }

    public static RuntimeList bz_DESTROY(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }

    public static RuntimeList bun_DESTROY(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }

    private static int classArgOffset(RuntimeArray args, String className) {
        if (args.isEmpty()) return 0;
        String first = args.get(0).toString();
        return first.equals(className) || first.contains("::") ? 1 : 0;
    }

    private static ByteArrayOutputStream inputBuffer(RuntimeHash self) {
        return (ByteArrayOutputStream) self.get("_input").value;
    }

    private static RuntimeScalar actualScalar(RuntimeScalar scalar) {
        return scalar.type == RuntimeScalarType.REFERENCE ? scalar.scalarDeref() : scalar;
    }

    private static byte[] inputBytes(RuntimeScalar scalar) {
        return toBytes(actualScalar(scalar));
    }

    private static byte[] toBytes(RuntimeScalar scalar) {
        return scalar.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static RuntimeScalar bytesToScalar(byte[] bytes) {
        RuntimeScalar scalar = new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1));
        scalar.type = RuntimeScalarType.BYTE_STRING;
        return scalar;
    }

    private static void setBytes(RuntimeScalar scalar, byte[] bytes) {
        scalar.set(bytesToScalar(bytes));
    }

    private static void writeOutput(RuntimeScalar outputArg, byte[] bytes, int flags) {
        RuntimeScalar output = actualScalar(outputArg);
        RuntimeScalar value = bytesToScalar(bytes);
        if ((flags & FLAG_APPEND_OUTPUT) != 0) {
            output.set(bytesToScalar(toBytes(output), bytes));
        } else {
            output.set(value);
        }
    }

    private static RuntimeScalar bytesToScalar(byte[] prefix, byte[] suffix) {
        byte[] combined = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(suffix, 0, combined, prefix.length, suffix.length);
        return bytesToScalar(combined);
    }

    private static byte[] slice(byte[] bytes, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, bytes.length));
        int safeEnd = Math.max(safeStart, Math.min(end, bytes.length));
        byte[] result = new byte[safeEnd - safeStart];
        System.arraycopy(bytes, safeStart, result, 0, result.length);
        return result;
    }

    private static boolean isReadOnly(RuntimeScalar scalar) {
        return scalar instanceof RuntimeScalarReadOnly
                || scalar.type == RuntimeScalarType.READONLY_SCALAR;
    }

    private static final class Bzip2Options {
        boolean appendOutput = true;
        boolean consumeInput = true;
        boolean limitOutput = false;
        int blockSize100k = 1;
        int bufsize = 16 * 1024;

        static Bzip2Options forCompress(RuntimeArray args, int offset) {
            Bzip2Options options = new Bzip2Options();
            if (args.size() > offset) {
                if (!options.applyHash(args.get(offset), true)) {
                    options.appendOutput = args.get(offset).getBoolean();
                    if (args.size() > offset + 1) options.blockSize100k = args.get(offset + 1).getInt();
                }
            }
            return options;
        }

        static Bzip2Options forUncompress(RuntimeArray args, int offset) {
            Bzip2Options options = new Bzip2Options();
            if (args.size() > offset) {
                if (!options.applyHash(args.get(offset), false)) {
                    options.appendOutput = args.get(offset).getBoolean();
                    if (args.size() > offset + 1) options.consumeInput = args.get(offset + 1).getBoolean();
                    if (args.size() > offset + 4) options.limitOutput = args.get(offset + 4).getBoolean();
                }
            }
            return options;
        }

        private boolean applyHash(RuntimeScalar arg, boolean compress) {
            if (arg.type != RuntimeScalarType.REFERENCE) return false;
            RuntimeHash hash;
            try {
                hash = arg.hashDeref();
            } catch (Exception e) {
                return false;
            }
            appendOutput = optionBoolean(hash, "-AppendOutput", appendOutput);
            if (compress) {
                blockSize100k = optionInt(hash, "-BlockSize100k", blockSize100k);
            } else {
                consumeInput = optionBoolean(hash, "-ConsumeInput", consumeInput);
                limitOutput = optionBoolean(hash, "-LimitOutput", limitOutput);
                bufsize = optionInt(hash, "-Bufsize", bufsize);
            }
            return true;
        }

        private static boolean optionBoolean(RuntimeHash hash, String key, boolean defaultValue) {
            return hash.exists(key).getBoolean() ? hash.get(key).getBoolean() : defaultValue;
        }

        private static int optionInt(RuntimeHash hash, String key, int defaultValue) {
            return hash.exists(key).getBoolean() ? hash.get(key).getInt() : defaultValue;
        }
    }

    public static RuntimeList BZ_RUN(RuntimeArray args, int ctx) { return new RuntimeScalar(0).getList(); }
    public static RuntimeList BZ_FLUSH(RuntimeArray args, int ctx) { return new RuntimeScalar(1).getList(); }
    public static RuntimeList BZ_FINISH(RuntimeArray args, int ctx) { return new RuntimeScalar(2).getList(); }
    public static RuntimeList BZ_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_OK).getList(); }
    public static RuntimeList BZ_RUN_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_RUN_OK).getList(); }
    public static RuntimeList BZ_FLUSH_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_FLUSH_OK).getList(); }
    public static RuntimeList BZ_FINISH_OK(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_FINISH_OK).getList(); }
    public static RuntimeList BZ_STREAM_END(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_STREAM_END).getList(); }
    public static RuntimeList BZ_SEQUENCE_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_SEQUENCE_ERROR).getList(); }
    public static RuntimeList BZ_PARAM_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_PARAM_ERROR).getList(); }
    public static RuntimeList BZ_MEM_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_MEM_ERROR).getList(); }
    public static RuntimeList BZ_DATA_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_DATA_ERROR).getList(); }
    public static RuntimeList BZ_DATA_ERROR_MAGIC(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_DATA_ERROR_MAGIC).getList(); }
    public static RuntimeList BZ_IO_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_IO_ERROR).getList(); }
    public static RuntimeList BZ_UNEXPECTED_EOF(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_UNEXPECTED_EOF).getList(); }
    public static RuntimeList BZ_OUTBUFF_FULL(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_OUTBUFF_FULL).getList(); }
    public static RuntimeList BZ_CONFIG_ERROR(RuntimeArray args, int ctx) { return new RuntimeScalar(BZ_CONFIG_ERROR).getList(); }
}

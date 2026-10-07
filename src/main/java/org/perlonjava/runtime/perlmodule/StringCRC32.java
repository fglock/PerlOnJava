package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.parser.StringParser;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;

/** Java implementation of String::CRC32's XS entry point. */
public class StringCRC32 extends PerlModuleBase {

    public StringCRC32() {
        super("String::CRC32", false);
    }

    public static void initialize() {
        StringCRC32 module = new StringCRC32();
        try {
            module.registerMethod("crc32", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing String::CRC32 method: " + e.getMessage());
        }
        GlobalVariable.getGlobalVariable("String::CRC32::VERSION")
                .set(new RuntimeScalar("2.100"));
    }

    /** crc32($string_or_filehandle [, $initial_crc]) */
    public static RuntimeList crc32(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeScalar(0L).getList();
        }

        long seed = args.size() > 1 && args.get(1).getDefinedBoolean()
                ? args.get(1).getLong() & 0xFFFFFFFFL : 0L;
        RuntimeScalar actual = args.get(0).type == RuntimeScalarType.REFERENCE
                ? args.get(0).scalarDeref() : args.get(0);
        RuntimeIO filehandle = actual.getRuntimeIO();
        long crc = filehandle == null
                ? CompressZlib.crc32WithSeed(getScalarBytes(actual), seed)
                : crc32Filehandle(filehandle, seed);
        return new RuntimeScalar(crc).getList();
    }

    private static long crc32Filehandle(RuntimeIO filehandle, long seed) {
        long crc = seed;
        while (true) {
            RuntimeScalar chunk = filehandle.ioHandle.read(32768, StandardCharsets.ISO_8859_1);
            if (!chunk.getDefinedBoolean()) break;
            String text = chunk.toString();
            if (text.isEmpty()) break;
            StringParser.assertNoWideCharacters(text, "crc32");
            crc = CompressZlib.crc32WithSeed(
                    text.getBytes(StandardCharsets.ISO_8859_1), crc);
        }
        return crc;
    }

    private static byte[] getScalarBytes(RuntimeScalar actual) {
        String text = actual.toString();
        StringParser.assertNoWideCharacters(text, "crc32");
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.operators.ScalarOperators;
import org.perlonjava.runtime.operators.StringOperators;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;

/**
 * The BytesPragma class provides functionalities similar to the Perl bytes module.
 * When enabled, it forces string operations to work with bytes rather than characters.
 * Also provides bytes::length(), bytes::chr(), bytes::ord(), bytes::substr() as
 * callable subroutines (used by modules like Text::CSV_PP).
 */
public class BytesPragma extends PerlModuleBase {

    /**
     * Constructor for BytesPragma.
     * Initializes the module with the name "bytes".
     */
    public BytesPragma() {
        super("bytes");
    }

    /**
     * Static initializer to set up the Bytes module.
     */
    public static void initialize() {
        BytesPragma bytes = new BytesPragma();
        try {
            bytes.registerMethod("import", "useBytes", ";$");
            bytes.registerMethod("unimport", "noBytes", ";$");
            // Register bytes:: utility functions (callable as bytes::length($x) etc.)
            bytes.registerMethod("length", "bytesLength", "$");
            bytes.registerMethod("chr", "bytesChr", "$");
            bytes.registerMethod("ord", "bytesOrd", "$");
            bytes.registerMethod("substr", "bytesSubstr", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Bytes method: " + e.getMessage());
        }
        // Set $bytes::VERSION
        GlobalVariable.getGlobalVariable("bytes::VERSION").set(new RuntimeScalar("1.08"));
    }

    /**
     * Implements the 'use bytes' pragma.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList useBytes(RuntimeArray args, int ctx) {
        // Enable bytes mode by setting the HINT_BYTES flag
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.enableStrictOption(Strict.HINT_BYTES);
        }
        return new RuntimeList();
    }

    /**
     * Implements the 'no bytes' pragma.
     *
     * @param args Arguments passed to the pragma
     * @param ctx  Context
     * @return RuntimeList indicating success
     */
    public static RuntimeList noBytes(RuntimeArray args, int ctx) {
        // Disable bytes mode by clearing the HINT_BYTES flag
        ScopedSymbolTable currentScope = getCurrentScope();
        if (currentScope != null) {
            currentScope.disableStrictOption(Strict.HINT_BYTES);
        }
        return new RuntimeList();
    }

    /**
     * Implements bytes::length($string).
     * Returns the number of bytes in the UTF-8 encoding of the string.
     */
    public static RuntimeList bytesLength(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.size() > 0 ? args.get(0) : new RuntimeScalar();
        return StringOperators.lengthBytes(scalar).getList();
    }

    /**
     * Implements bytes::chr($codepoint).
     * Returns a byte character for the given code point (mod 256).
     */
    public static RuntimeList bytesChr(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.size() > 0 ? args.get(0) : new RuntimeScalar();
        return StringOperators.chrBytes(scalar).getList();
    }

    /**
     * Implements bytes::ord($string).
     * Returns the byte value of the first byte in the string.
     */
    public static RuntimeList bytesOrd(RuntimeArray args, int ctx) {
        RuntimeScalar scalar = args.size() > 0 ? args.get(0) : new RuntimeScalar();
        return ScalarOperators.ordBytes(scalar).getList();
    }

    /**
     * Implements bytes::substr($string, $offset, $length, $replacement).
     * Operates on the UTF-8 byte representation of the string.
     */
    public static RuntimeList bytesSubstr(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("Usage: bytes::substr(STRING, OFFSET [, LENGTH [, REPLACEMENT]])");
        }
        // Delegate to the standard substr but operating on bytes
        // Convert to byte string first, then do substr
        RuntimeScalar str = args.get(0);
        RuntimeScalar offset = args.get(1);
        RuntimeScalar length = args.size() > 2 ? args.get(2) : new RuntimeScalar();
        RuntimeScalar replacement = args.size() > 3 ? args.get(3) : null;

        // Get the UTF-8 bytes of the string
        byte[] bytes = str.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int off = offset.getInt();
        int len = length.getDefinedBoolean() ? length.getInt() : bytes.length - off;

        // Handle negative offset
        if (off < 0) off = bytes.length + off;
        if (off < 0) off = 0;
        if (off > bytes.length) off = bytes.length;
        if (len < 0) len = bytes.length - off + len;
        if (len < 0) len = 0;
        if (off + len > bytes.length) len = bytes.length - off;

        byte[] result = new byte[len];
        System.arraycopy(bytes, off, result, 0, len);
        return new RuntimeScalar(result).getList();
    }
}

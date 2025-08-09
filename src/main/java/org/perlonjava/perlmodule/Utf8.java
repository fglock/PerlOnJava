package org.perlonjava.perlmodule;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;
import org.perlonjava.symbols.ScopedSymbolTable;

import java.nio.charset.StandardCharsets;

import static org.perlonjava.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.runtime.RuntimeScalarType.STRING;

/**
 * The Utf8 class provides functionalities similar to the Perl utf8 pragma.
 */
public class Utf8 extends PerlModuleBase {

    /**
     * Constructor for Utf8.
     * Initializes the module with the name "utf8".
     */
    public Utf8() {
        super("utf8");
    }

    /**
     * Static initializer to set up the Utf8 module.
     */
    public static void initialize() {
        Utf8 utf8 = new Utf8();
        utf8.initializeExporter();
        utf8.defineExport("EXPORT_OK", "upgrade", "downgrade", "encode", "decode", "native_to_unicode", "unicode_to_native", "is_utf8", "valid");
        try {
            utf8.registerMethod("import", "useUtf8", ";$");
            utf8.registerMethod("unimport", "noUtf8", ";$");
            utf8.registerMethod("upgrade", "$");
            utf8.registerMethod("downgrade", "$;$");
            utf8.registerMethod("encode", "$");
            utf8.registerMethod("decode", "$");
            utf8.registerMethod("native_to_unicode", "nativeToUnicode", "$");
            utf8.registerMethod("unicode_to_native", "unicodeToNative", "$");
            utf8.registerMethod("is_utf8", "isUtf8", "$");
            utf8.registerMethod("valid", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Utf8 method: " + e.getMessage());
        }
    }

    /**
     * Enables the UTF8 pragma.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useUtf8(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        symbolTable.enableStrictOption(Strict.HINT_UTF8);
        return new RuntimeScalar().getList();
    }

    /**
     * Disables the UTF8 pragma.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList noUtf8(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();
        symbolTable.disableStrictOption(Strict.HINT_UTF8);
        return new RuntimeScalar().getList();
    }

    /**
     * Converts the internal representation of the string to UTF-8.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the number of octets necessary to represent the string as UTF-8.
     */
    public static RuntimeList upgrade(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for upgrade() method");
        }
        RuntimeScalar scalar = args.get(0);
        String string = scalar.toString();
        byte[] utf8Bytes = string.getBytes(StandardCharsets.UTF_8);
        return new RuntimeScalar(utf8Bytes.length).getList();
    }

    /**
     * Converts the internal representation of the string from UTF-8 to native encoding.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating success or failure.
     */
    public static RuntimeList downgrade(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for downgrade() method");
        }
        RuntimeScalar scalar = args.get(0);
        boolean failOk = args.size() == 2 && args.get(1).getBoolean();
        String string = scalar.toString();

        // Check if the string can be represented in ISO-8859-1
        try {
            // Convert the string to ISO-8859-1 bytes
            byte[] bytes = string.getBytes(StandardCharsets.ISO_8859_1);
            // Convert back to string to ensure the conversion is valid
            String decoded = new String(bytes, StandardCharsets.ISO_8859_1);

            // If the original string matches the decoded string, conversion is successful
            if (string.equals(decoded)) {
                // Ensure the UTF-8 flag is off by using the ISO-8859-1 encoding
                scalar.set(new String(bytes, StandardCharsets.ISO_8859_1));
                return new RuntimeScalar(true).getList();
            } else {
                // If the strings do not match, the conversion failed
                if (failOk) {
                    return new RuntimeScalar(false).getList();
                }
                throw new IllegalArgumentException("String contains characters that cannot be represented in ISO-8859-1");
            }
        } catch (Exception e) {
            if (failOk) {
                return new RuntimeScalar(false).getList();
            }
            throw new IllegalArgumentException("String cannot be represented in native encoding", e);
        }
    }


    /**
     * Converts the character sequence to the corresponding octet sequence in UTF-8.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList encode(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for encode() method");
        }
        RuntimeScalar scalar = args.get(0);
        String string = scalar.toString();
        byte[] utf8Bytes = string.getBytes(StandardCharsets.UTF_8);
        scalar.set(new String(utf8Bytes, StandardCharsets.ISO_8859_1));
        return new RuntimeScalar().getList();
    }

    /**
     * Attempts to convert the octet sequence encoded in UTF-8 to the corresponding character sequence.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating success or failure.
     */
    public static RuntimeList decode(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for decode() method");
        }
        RuntimeScalar scalar = args.get(0);
        String string = scalar.toString();
        try {
            byte[] bytes = string.getBytes(StandardCharsets.ISO_8859_1);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            scalar.set(decoded);
            return new RuntimeScalar(true).getList();
        } catch (Exception e) {
            return new RuntimeScalar(false).getList();
        }
    }

    /**
     * Converts a native code point to its Unicode equivalent.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the Unicode equivalent.
     */
    public static RuntimeList nativeToUnicode(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for native_to_unicode() method");
        }
        RuntimeScalar scalar = args.get(0);
        int codePoint = scalar.getInt();
        // Assuming ASCII platform, return the input as is.
        return new RuntimeScalar(codePoint).getList();
    }

    /**
     * Converts a Unicode code point to its native equivalent.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the native equivalent.
     */
    public static RuntimeList unicodeToNative(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for unicode_to_native() method");
        }
        RuntimeScalar scalar = args.get(0);
        int codePoint = scalar.getInt();
        // Assuming ASCII platform, return the input as is.
        return new RuntimeScalar(codePoint).getList();
    }

    /**
     * Tests whether the string is marked internally as encoded in UTF-8.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the string is UTF-8.
     */
    public static RuntimeList isUtf8(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for is_utf8() method");
        }
        RuntimeScalar scalar = args.get(0);
        return RuntimeScalarCache.getScalarBoolean(scalar.type == STRING).getList();
        
//        String string = scalar.toString();
//        CharsetDetector detector = new CharsetDetector();
//        detector.setText(string.getBytes());
//        CharsetMatch match = detector.detect();
//        boolean isUtf8 = match != null && "UTF-8".equalsIgnoreCase(match.getName());
//        return new RuntimeScalar(isUtf8).getList();
    }

    /**
     * Tests whether the string is in a consistent state regarding UTF-8.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the string is valid UTF-8.
     */
    public static RuntimeList valid(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for valid() method");
        }
        RuntimeScalar scalar = args.get(0);
        String string = scalar.toString();
        CharsetDetector detector = new CharsetDetector();
        detector.setText(string.getBytes());
        CharsetMatch match = detector.detect();
        boolean isValid = match != null && "UTF-8".equalsIgnoreCase(match.getName());
        return new RuntimeScalar(isValid).getList();
    }
}

package org.perlonjava.runtime.perlmodule;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.STRING;

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
            utf8.registerMethod("upgrade", null);
            utf8.registerMethod("downgrade", "$;$");
            utf8.registerMethod("encode", "$");
            utf8.registerMethod("decode", "$");
            utf8.registerMethod("native_to_unicode", "nativeToUnicode", "$");
            utf8.registerMethod("unicode_to_native", "unicodeToNative", "$");
            utf8.registerMethod("is_utf8", "isUtf8", "$");
            utf8.registerMethod("valid", "$");
            // Set $VERSION so CPAN.pm can detect our bundled version
            GlobalVariable.getGlobalVariable("utf8::VERSION").set(new RuntimeScalar("1.29"));
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
     * <p>In Perl, utf8::upgrade() ensures the UTF-8 flag is on for the scalar.
     * This affects how the string is interpreted internally:
     * <ul>
     *   <li>BYTE_STRING (UTF-8 flag off): bytes are interpreted as Latin-1 code points (0x00-0xFF).
     *       If the bytes form valid UTF-8, decode them to Unicode characters. Otherwise, keep
     *       Latin-1 interpretation (each byte becomes a character with that code point).</li>
     *   <li>STRING (UTF-8 flag on): already contains Unicode characters. This is a no-op;
     *       the string content is NOT modified. This is critical: a string like "\x{100}" (U+0100)
     *       must remain U+0100, not be corrupted to '?' or other replacement characters.</li>
     *   <li>Other types: convert to string and mark as STRING type.</li>
     * </ul>
     *
     * <p><strong>IMPORTANT:</strong> Do NOT use {@code string.getBytes(ISO_8859_1)} on strings
     * that may contain characters > 0xFF, as Java will replace unmappable characters with '?'.
     * For BYTE_STRING, extract raw byte values directly from char codes.</p>
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

        // Don't modify read-only scalars (e.g., string literals)
        if (!(scalar instanceof RuntimeScalarReadOnly)) {
            if (scalar.type == BYTE_STRING) {
                // BYTE_STRING → STRING: just flip the type flag without changing content.
                //
                // In Perl 5, utf8::upgrade() only changes the internal storage format
                // (from byte to UTF-8 encoded), but the character codepoints remain
                // identical. For example, bytes 0xE2, 0x82, 0xAC become characters
                // U+00E2, U+0082, U+00AC (NOT decoded as UTF-8 to U+20AC).
                //
                // NOTE: Some parts of the interpreter/compiler may construct a BYTE_STRING
                // that already contains Unicode code points > 0xFF (e.g. "\x{100}").
                // This is fine — we just flip the type and preserve the content as-is.
                scalar.set(string);
                scalar.type = STRING;
            } else if (scalar.type != STRING) {
                // Other types (INTEGER, DOUBLE, UNDEF, etc.): convert to string and mark as STRING.
                //
                // CRITICAL: We must call scalar.set(string) to ensure the scalar's internal
                // value is updated to the string representation. Just setting scalar.type = STRING
                // is NOT sufficient, as the scalar may still hold its original numeric/other value.
                //
                // Example: "\x{100}" may initially be stored as an INTEGER with value 256.
                // toString() returns "Ā" (U+0100), but the scalar's internal value is still 256.
                // We must call set(string) to store "Ā" as the actual string value.
                //
                // WARNING: Do NOT skip this set() call, as it will cause regressions where
                // utf8::upgrade() corrupts Unicode strings to wrong values (e.g., U+0100 -> U+0000).
                scalar.set(string);
                scalar.type = STRING;
            }
            // If scalar.type == STRING: already upgraded, do nothing.
            // The string content must NOT be modified - it already contains correct Unicode characters.
            // This is a no-op case and is critical for preserving Unicode strings like "\x{100}".
        }

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
                // Don't modify read-only scalars (e.g., string literals).
                // The string is already representable in ISO-8859-1, so the downgrade
                // is logically successful even if we can't modify the scalar in-place.
                if (!(scalar instanceof RuntimeScalarReadOnly)) {
                    // Ensure the UTF-8 flag is off by using the ISO-8859-1 encoding
                    scalar.set(new String(bytes, StandardCharsets.ISO_8859_1));
                    scalar.type = BYTE_STRING;
                }
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
        scalar.type = BYTE_STRING;
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

        // utf8::decode expects octet data (0-255). If the string contains
        // characters > 0xFF, it cannot be valid octet data — return false
        // without modifying the string.
        for (int i = 0; i < string.length(); i++) {
            if (string.charAt(i) > 0xFF) {
                return new RuntimeScalar(false).getList();
            }
        }

        try {
            // Safe: all chars are <= 0xFF, so no data loss with manual byte extraction
            byte[] bytes = new byte[string.length()];
            for (int i = 0; i < string.length(); i++) {
                bytes[i] = (byte) string.charAt(i);
            }
            // Use a strict UTF-8 decoder that throws on invalid sequences
            // instead of silently replacing with U+FFFD.  This matches Perl 5
            // behavior where utf8::decode returns FALSE for invalid UTF-8.
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            scalar.set(decoded.toString());
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
        return isUtf8(scalar) ? RuntimeScalarCache.scalarTrue.getList() : RuntimeScalarCache.scalarFalse.getList();
    }

    /**
     * Internal helper to check if a scalar has the UTF-8 flag set.
     * This is used by regex matching to determine whether to use Unicode semantics.
     *
     * @param scalar The scalar to check.
     * @return true if the scalar is a UTF-8 string (not BYTE_STRING), false otherwise.
     */
    public static boolean isUtf8(RuntimeScalar scalar) {
        // Resolve proxy types (ScalarSpecialVariable for $1, $&, etc.)
        if (scalar instanceof ScalarSpecialVariable sv) {
            scalar = sv.getValueAsScalar();
        }
        // Only STRING type has the UTF-8 flag set.
        // INTEGER, DOUBLE, UNDEF, REFERENCE etc. don't have the UTF-8 flag in Perl.
        return scalar.type == STRING;
    }

    /**
     * Tests whether the string is in a consistent state regarding UTF-8.
     *
     * <p>In Perl, utf8::valid() checks whether a string's internal state is consistent:
     * <ul>
     *   <li>For character strings (UTF-8 flag on): Returns true if all characters are valid
     *       Unicode code points (not surrogates, not > 0x10FFFF). Java strings inherently
     *       contain valid Unicode code points (possibly with surrogate pairs for astral
     *       characters), so this should generally return true.</li>
     *   <li>For byte strings (UTF-8 flag off): Returns true if the bytes form valid UTF-8.</li>
     * </ul>
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

        if (scalar.type == BYTE_STRING) {
            // For byte strings (UTF-8 flag off), Perl always returns true.
            // The bytes are not claiming to be UTF-8, so they are considered
            // valid in their native encoding (Latin-1/bytes).
            return RuntimeScalarCache.scalarTrue.getList();
        } else {
            // For character strings (UTF-8 flag on), check if all characters are valid
            // Unicode code points. Java strings contain UTF-16 code units, which
            // represent valid Unicode code points (including surrogate pairs for astral
            // characters). As long as surrogate pairs are properly formed, the string
            // is valid.
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    // High surrogate must be followed by low surrogate
                    if (i + 1 >= string.length() || !Character.isLowSurrogate(string.charAt(i + 1))) {
                        return RuntimeScalarCache.scalarFalse.getList();
                    }
                    i++; // Skip the low surrogate
                } else if (Character.isLowSurrogate(c)) {
                    // Low surrogate without preceding high surrogate is invalid
                    return RuntimeScalarCache.scalarFalse.getList();
                }
            }
            return RuntimeScalarCache.scalarTrue.getList();
        }
    }
}

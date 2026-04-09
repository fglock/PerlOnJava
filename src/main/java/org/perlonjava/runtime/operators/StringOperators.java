package org.perlonjava.runtime.operators;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.CaseMap;
import org.perlonjava.frontend.parser.NumberParser;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Iterator;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;

/**
 * A utility class that provides various string operations on {@link RuntimeScalar} objects.
 */
public class StringOperators {

    /**
     * Returns the length of the string representation of the given {@link RuntimeScalar}.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose length is to be calculated
     * @return a {@link RuntimeScalar} containing the length of the input as an integer
     */
    public static RuntimeScalar length(RuntimeScalar runtimeScalar) {
        // If the scalar is undefined, return undef
        if (!runtimeScalar.getDefinedBoolean()) {
            return RuntimeScalarCache.scalarUndef;
        }
        // Convert the RuntimeScalar to a string and return its length in codepoints
        String str = runtimeScalar.toString();
        return getScalarInt(str.codePointCount(0, str.length()));
    }

    /**
     * Returns the byte length of the string representation of the given {@link RuntimeScalar}.
     * This is used when 'use bytes' pragma is in effect.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose byte length is to be calculated
     * @return a {@link RuntimeScalar} containing the byte length of the input
     */
    public static RuntimeScalar lengthBytes(RuntimeScalar runtimeScalar) {
        // If the scalar is undefined, return undef
        if (!runtimeScalar.getDefinedBoolean()) {
            return RuntimeScalarCache.scalarUndef;
        }
        // In Perl 5, `use bytes; length(...)` returns the internal byte count:
        // - BYTE_STRING (SvUTF8=0 in Perl 5): stored as Latin-1, byte count == char count
        // - STRING (SvUTF8=1 in Perl 5): stored as UTF-8, byte count may be > char count
        //
        // In PerlOnJava:
        // - BYTE_STRING: string literals with chars <= 0xFF, byte data from pack("A",...), etc.
        // - STRING: pack("U",...), utf8::upgrade, literals with chars > 0xFF
        String str = runtimeScalar.toString();
        if (runtimeScalar.type == RuntimeScalarType.BYTE_STRING) {
            // Latin-1: 1 byte per character
            return getScalarInt(str.length());
        }
        // STRING type: return UTF-8 byte count
        try {
            return getScalarInt(str.getBytes(StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            return getScalarInt(str.codePointCount(0, str.length()));
        }
    }

    /**
     * Converts a string to its UTF-8 byte representation.
     * Each byte becomes a separate character in the range 0x00-0xFF.
     * This is used when 'use bytes' pragma is in effect for regex matching.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to convert
     * @return a {@link RuntimeScalar} containing the byte-level string
     */
    public static RuntimeScalar toBytesString(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Check if all characters are already in 0-255 range (ASCII/Latin-1)
        boolean needsConversion = false;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) > 0xFF) {
                needsConversion = true;
                break;
            }
        }
        if (!needsConversion) {
            return runtimeScalar;
        }
        // Convert to UTF-8 bytes, then create a string where each byte is a character
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append((char) (b & 0xFF));
        }
        return new RuntimeScalar(sb.toString());
    }

    /**
     * Helper to create a string result that preserves BYTE_STRING type from the source.
     */
    private static RuntimeScalar makeStringResult(String value, RuntimeScalar source) {
        RuntimeScalar result = new RuntimeScalar(value);
        if (source.type == RuntimeScalarType.BYTE_STRING) {
            result.type = RuntimeScalarType.BYTE_STRING;
        }
        return result;
    }

    /**
     * Escapes all non-alphanumeric characters in the string representation of the given {@link RuntimeScalar}.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be quoted
     * @return a {@link RuntimeScalar} with non-alphanumeric characters escaped
     */
    public static RuntimeScalar quotemeta(RuntimeScalar runtimeScalar) {
        StringBuilder quoted = new StringBuilder();
        // Iterate over each character in the string
        for (char c : runtimeScalar.toString().toCharArray()) {
            // If the character is alphanumeric or underscore, append it as is
            // Perl's quotemeta does NOT escape underscore (it's part of \w)
            if (Character.isLetterOrDigit(c) || c == '_') {
                quoted.append(c);
            } else {
                // Otherwise, escape it with a backslash
                quoted.append("\\").append(c);
            }
        }
        return makeStringResult(quoted.toString(), runtimeScalar);
    }

    /**
     * Performs full Unicode case folding on the string representation of the given {@link RuntimeScalar}.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be case folded
     * @return a {@link RuntimeScalar} with the case-folded string
     */
    public static RuntimeScalar fc(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Perform full Unicode case folding using ICU4J CaseMap
        // Note: We do NOT use NFKC normalization because Perl's fc() preserves
        // composed characters like ⅷ (U+2177), ⓚ (U+24DA), ǳ (U+01F3), ĳ (U+0133)
        // NFKC would decompose these to their ASCII equivalents, which is wrong.
        str = CaseMap.fold().apply(str);

        return makeStringResult(str, runtimeScalar);
    }

    /**
     * Performs full Unicode case folding under 'use bytes' pragma.
     * Under 'use bytes', operates on the UTF-8 bytes of the input, only folding ASCII bytes.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be case folded
     * @return a {@link RuntimeScalar} with the case-folded bytes
     */
    public static RuntimeScalar fcBytes(RuntimeScalar runtimeScalar) {
        // Under 'use bytes', we operate on the UTF-8 bytes of the input
        RuntimeScalar asBytes = toUtf8Bytes(runtimeScalar);
        // Case-fold only ASCII bytes (A-Z -> a-z), leave others unchanged
        return caseFoldBytesAsciiOnly(asBytes);
    }

    /**
     * Converts the string representation of the given {@link RuntimeScalar} to lowercase.
     * Uses ICU4J for full Unicode support.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be converted to lowercase
     * @return a {@link RuntimeScalar} with the lowercase string
     */
    public static RuntimeScalar lc(RuntimeScalar runtimeScalar) {
        // Convert the string to lowercase using ICU4J for proper Unicode handling
        String str = UCharacter.toLowerCase(runtimeScalar.toString());
        return makeStringResult(str, runtimeScalar);
    }

    /**
     * Converts to lowercase under 'use bytes' pragma.
     * Operates on the UTF-8 bytes of the input, only affecting ASCII.
     */
    public static RuntimeScalar lcBytes(RuntimeScalar runtimeScalar) {
        RuntimeScalar asBytes = toUtf8Bytes(runtimeScalar);
        return caseFoldBytesAsciiOnly(asBytes);
    }

    /**
     * Converts the first character of the string representation of the given {@link RuntimeScalar} to lowercase.
     * Uses ICU4J for full Unicode support.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose first character is to be converted to lowercase
     * @return a {@link RuntimeScalar} with the first character in lowercase
     */
    public static RuntimeScalar lcfirst(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Check if the string is empty
        if (str.isEmpty()) {
            return makeStringResult(str, runtimeScalar);
        }
        // Get the first code point and convert it to lowercase using ICU4J
        int firstCodePoint = str.codePointAt(0);
        int charCount = Character.charCount(firstCodePoint);
        String firstChar = str.substring(0, charCount);
        String rest = str.substring(charCount);
        String lowerFirst = UCharacter.toLowerCase(firstChar);
        return makeStringResult(lowerFirst + rest, runtimeScalar);
    }

    /**
     * Converts the string representation of the given {@link RuntimeScalar} to uppercase.
     * Uses ICU4J for full Unicode support.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be converted to uppercase
     * @return a {@link RuntimeScalar} with the uppercase string
     */
    public static RuntimeScalar uc(RuntimeScalar runtimeScalar) {
        // Convert the string to uppercase using ICU4J for proper Unicode handling
        String str = UCharacter.toUpperCase(runtimeScalar.toString());
        return makeStringResult(str, runtimeScalar);
    }

    /**
     * Converts the first character of the string representation of the given {@link RuntimeScalar} to titlecase.
     * Uses ICU4J for full Unicode support. Note: titlecase is different from uppercase for some characters
     * like digraphs (e.g., DŽ U+01C4 titlecases to Dž U+01C5, not DŽ).
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose first character is to be converted to titlecase
     * @return a {@link RuntimeScalar} with the first character in titlecase
     */
    public static RuntimeScalar ucfirst(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Check if the string is empty
        if (str.isEmpty()) {
            return new RuntimeScalar(str);
        }
        int firstCodePoint = str.codePointAt(0);
        int charCount = Character.charCount(firstCodePoint);
        String firstChar = str.substring(0, charCount);
        String rest = str.substring(charCount);
        // Try string-based API first for one-to-many mappings (e.g., U+0587 → U+0535 U+0582)
        String titleFirst = UCharacter.toTitleCase(Locale.ROOT, firstChar, null);
        if (titleFirst.equals(firstChar)) {
            // String API didn't change it (e.g., combining characters like U+0345).
            // Fall back to code-point API for simple titlecase mapping.
            int titleCodePoint = UCharacter.toTitleCase(firstCodePoint);
            if (titleCodePoint != firstCodePoint) {
                titleFirst = String.valueOf(Character.toChars(titleCodePoint));
            }
        }
        return makeStringResult(titleFirst + rest, runtimeScalar);
    }

    /**
     * Finds the index of the first occurrence of a substring in the string representation of the given {@link RuntimeScalar},
     * starting from a specified position.
     *
     * @param runtimeScalar the {@link RuntimeScalar} in which to search
     * @param substr        the substring to find
     * @param position      the position to start the search from
     * @return a {@link RuntimeScalar} containing the index of the first occurrence, or -1 if not found
     */
    public static RuntimeScalar index(RuntimeScalar runtimeScalar, RuntimeScalar substr, RuntimeScalar position) {
        String str = runtimeScalar.toString();
        String sub = substr.toString();
        int pos = position.type == RuntimeScalarType.UNDEF
                ? 0 : position.getInt(); // if position is not provided, start from 0

        // Bound the position to be within the valid range of the string
        if (pos < 0) {
            pos = 0;
        }

        // Special case for empty substring - it can be found at any valid position
        if (sub.isEmpty()) {
            // Empty string can be found at any position up to and including the length
            if (pos > str.length()) {
                return getScalarInt(str.length());
            }
            return getScalarInt(pos);
        }

        // For non-empty substring, position beyond string length returns -1
        if (pos >= str.length()) {
            return getScalarInt(-1);
        }

        // Find the index of the substring starting from the specified position
        int result = str.indexOf(sub, pos);

        // Return the index or -1 if not found
        return getScalarInt(result);
    }

    /**
     * Finds the index of the last occurrence of a substring in the string representation of the given {@link RuntimeScalar},
     * searching backwards from a specified position.
     *
     * @param runtimeScalar the {@link RuntimeScalar} in which to search
     * @param substr        the substring to find
     * @param position      the position to start the search from
     * @return a {@link RuntimeScalar} containing the index of the last occurrence, or -1 if not found
     */
    public static RuntimeScalar rindex(RuntimeScalar runtimeScalar, RuntimeScalar substr, RuntimeScalar position) {
        String str = runtimeScalar.toString();
        String sub = substr.toString();
        int pos = position.type == RuntimeScalarType.UNDEF
                ? str.length() : position.getInt(); // Default to search from the end of the string

        // Special case for empty substring - it can be found at any valid position
        if (sub.isEmpty()) {
            // For empty string, negative position returns 0
            if (pos < 0) {
                return getScalarInt(0);
            }
            // Bound position to string length
            if (pos > str.length()) {
                return getScalarInt(str.length());
            }
            return getScalarInt(pos);
        }

        // For non-empty substring, negative position returns -1
        if (pos < 0) {
            return getScalarInt(-1);
        }

        // Bound the position to be within the valid range of the string
        if (pos >= str.length()) {
            pos = str.length() - 1;
        }

        // Find the last index of the substring before or at the specified position
        int result = str.lastIndexOf(sub, pos);

        // Return the index or -1 if not found
        return getScalarInt(result);
    }

    public static RuntimeScalar stringConcat(RuntimeScalar runtimeScalar, RuntimeScalar b) {
        String aStr = runtimeScalar.toString();
        String bStr = b.toString();

        // In Perl, concatenation produces a UTF-8 string only if at least one
        // operand has the UTF-8 flag on (STRING type). Non-STRING types
        // (BYTE_STRING, INTEGER, DOUBLE, UNDEF) are all byte-compatible.
        boolean aIsUtf8 = runtimeScalar.type == RuntimeScalarType.STRING;
        boolean bIsUtf8 = b.type == RuntimeScalarType.STRING;

        if (aIsUtf8 || bIsUtf8) {
            return new RuntimeScalar(aStr + bStr);
        }

        // Neither operand is UTF-8 — produce BYTE_STRING result
        // Check if all chars fit in a byte (Latin-1)
        boolean safe = true;
        for (int i = 0; safe && i < aStr.length(); i++) {
            if (aStr.charAt(i) > 255) {
                safe = false;
            }
        }
        for (int i = 0; safe && i < bStr.length(); i++) {
            if (bStr.charAt(i) > 255) {
                safe = false;
            }
        }
        if (safe) {
            byte[] aBytes = aStr.getBytes(StandardCharsets.ISO_8859_1);
            byte[] bBytes = bStr.getBytes(StandardCharsets.ISO_8859_1);
            byte[] out = new byte[aBytes.length + bBytes.length];
            System.arraycopy(aBytes, 0, out, 0, aBytes.length);
            System.arraycopy(bBytes, 0, out, aBytes.length, bBytes.length);
            return new RuntimeScalar(out);
        }

        return new RuntimeScalar(aStr + bStr);
    }

    public static RuntimeScalar stringConcatWarnUninitialized(RuntimeScalar runtimeScalar, RuntimeScalar b) {
        // For tied variables, we must only FETCH once, then use the result for both
        // the definedness check and the actual concatenation.
        // First, resolve tied variables to get their actual values (triggers FETCH once per tied var)
        RuntimeScalar aResolved = (runtimeScalar.type == RuntimeScalarType.TIED_SCALAR) 
                ? runtimeScalar.tiedFetch() : runtimeScalar;
        RuntimeScalar bResolved = (b.type == RuntimeScalarType.TIED_SCALAR) 
                ? b.tiedFetch() : b;
        
        // Now check definedness on the resolved values (no additional FETCH)
        if (!aResolved.getDefinedBoolean() || !bResolved.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in concatenation (.)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        
        // Get string values from resolved scalars
        String aStr = aResolved.toString();
        String bStr = bResolved.toString();

        if (aResolved.type == RuntimeScalarType.STRING || bResolved.type == RuntimeScalarType.STRING) {
            return new RuntimeScalar(aStr + bStr);
        }

        if (aResolved.type == BYTE_STRING || bResolved.type == BYTE_STRING) {
            boolean aIsByte = aResolved.type == BYTE_STRING
                    || aResolved.type == RuntimeScalarType.UNDEF
                    || (aStr.isEmpty() && aResolved.type != RuntimeScalarType.STRING);
            boolean bIsByte = bResolved.type == BYTE_STRING
                    || bResolved.type == RuntimeScalarType.UNDEF
                    || (bStr.isEmpty() && bResolved.type != RuntimeScalarType.STRING);
            if (aIsByte && bIsByte) {
                boolean safe = true;
                for (int i = 0; safe && i < aStr.length(); i++) {
                    if (aStr.charAt(i) > 255) {
                        safe = false;
                        break;
                    }
                }
                for (int i = 0; safe && i < bStr.length(); i++) {
                    if (bStr.charAt(i) > 255) {
                        safe = false;
                        break;
                    }
                }
                if (safe) {
                    byte[] aBytes = aStr.getBytes(StandardCharsets.ISO_8859_1);
                    byte[] bBytes = bStr.getBytes(StandardCharsets.ISO_8859_1);
                    byte[] out = new byte[aBytes.length + bBytes.length];
                    System.arraycopy(aBytes, 0, out, 0, aBytes.length);
                    System.arraycopy(bBytes, 0, out, aBytes.length, bBytes.length);
                    return new RuntimeScalar(out);
                }
            }
        }

        return new RuntimeScalar(aStr + bStr);
    }

    public static RuntimeScalar chompScalar(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        if (str.isEmpty()) {
            return getScalarInt(0);
        }

        RuntimeScalar separatorScalar = getGlobalVariable("main::/");
        if (separatorScalar.type == RuntimeScalarType.UNDEF) {
            // Slurp mode: don't remove anything
            return getScalarInt(0);
        }

        String separator = separatorScalar.toString();
        int charsRemoved = 0;
        String originalStr = str;

        if (separator.isEmpty()) {
            // Paragraph mode: remove all trailing newlines
            int endIndex = str.length();
            while (endIndex > 0 && str.charAt(endIndex - 1) == '\n') {
                endIndex--;
                charsRemoved++;
            }
            if (charsRemoved > 0) {
                str = str.substring(0, endIndex);
            }
        } else if (!separator.equals("\0")) {
            // Normal mode: remove trailing separator
            if (str.endsWith(separator)) {
                str = str.substring(0, str.length() - separator.length());
                charsRemoved = separator.length();
            }
        }
        // Note: In slurp mode ($/ = undef) or fixed-length record mode, we don't remove anything

        // Always update the original scalar if we modified the string
        if (!str.equals(originalStr)) {
            boolean wasByteString = runtimeScalar.type == RuntimeScalarType.BYTE_STRING;
            runtimeScalar.set(str);
            if (wasByteString) {
                runtimeScalar.type = RuntimeScalarType.BYTE_STRING;
            }
        }

        return getScalarInt(charsRemoved);
    }

    public static RuntimeScalar chopScalar(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        if (str.isEmpty()) {
            return new RuntimeScalar();
        }

        // Handle Unicode properly by using code points instead of char units
        int lastCodePoint = str.codePointBefore(str.length());
        int lastCharSize = Character.charCount(lastCodePoint);

        String lastChar = str.substring(str.length() - lastCharSize);
        String remainingStr = str.substring(0, str.length() - lastCharSize);

        boolean wasByteString = runtimeScalar.type == RuntimeScalarType.BYTE_STRING;
        runtimeScalar.set(remainingStr);
        if (wasByteString) {
            runtimeScalar.type = RuntimeScalarType.BYTE_STRING;
        }
        return new RuntimeScalar(lastChar);
    }

    public static RuntimeScalar chr(RuntimeScalar runtimeScalar) {
        // Convert string type to number if necessary
        if (runtimeScalar.isString()) {
            runtimeScalar = NumberParser.parseNumber(runtimeScalar);
        }

        // Check for negative values BEFORE converting to int
        // because int conversion truncates towards zero (e.g., -0.1 becomes 0)
        boolean isNegative = false;
        if (runtimeScalar.type == RuntimeScalarType.DOUBLE) {
            double doubleValue = runtimeScalar.getDouble();
            if (Double.isInfinite(doubleValue) || Double.isNaN(doubleValue)) {
                String value = Double.isNaN(doubleValue) ? "NaN" :
                        (doubleValue > 0 ? "Inf" : "-Inf");
                throw new PerlCompilerException("Cannot chr " + value);
            }
            isNegative = doubleValue < 0;
        }

        int codePoint = runtimeScalar.getInt();

        // Handle negative values (check both int and original double)
        if (codePoint < 0 || isNegative) {
            codePoint = 0xFFFD;  // Unicode replacement character
        }

        // Perl's chr() accepts any non-negative integer value and creates a character
        // with that code point, even if it's not valid Unicode (surrogates, beyond 0x10FFFF).
        // Java's Character.isValidCodePoint() rejects these, so we need to handle them.

        // For values 0-0x10FFFF that Java accepts, use Java's built-in support
        if (Character.isValidCodePoint(codePoint) && codePoint <= 0x10FFFF) {
            RuntimeScalar res = new RuntimeScalar(new String(Character.toChars(codePoint)));
            // Only mark as BYTE_STRING for values 0-255
            if (codePoint <= 0xFF) {
                res.type = BYTE_STRING;
            }
            return res;
        }

        // For surrogates (0xD800-0xDFFF) and values beyond Unicode (> 0x10FFFF),
        // Perl still creates a character with that code point. We store it as a
        // special marker that will be properly encoded when converted to UTF-8.
        // For now, we create a string with the code point value, which will be
        // handled by the UTF-8 encoding logic in pack/unpack.

        // Create a character using the code point directly
        // Note: This may create invalid Unicode, but that's what Perl does
        if (codePoint <= 0x10FFFF) {
            // Surrogates: Java won't let us create these with Character.toChars,
            // but we can store the value for later UTF-8 encoding
            RuntimeScalar res = new RuntimeScalar(new String(new int[]{codePoint}, 0, 1));
            return res;
        }

        // For values beyond 0x10FFFF, Java's String can't represent them.
        // We need to store the code point value separately so UnpackState can encode it.
        // As a workaround, we'll store a special marker string with the code point embedded.
        // Format: "\uFFFD" + 4-byte big-endian int representation
        // This is a hack, but it allows us to preserve the value for unpack.
        RuntimeScalar res = new RuntimeScalar();
        res.type = RuntimeScalarType.STRING;
        // Store as a special format that UnpackState will recognize
        // Use a marker followed by the code point value
        res.value = "\uFFFD" + String.format("<%08X>", codePoint);
        return res;
    }

    /**
     * Returns a character from a code point when 'use bytes' pragma is in effect.
     * This treats the value as a byte value (0-255) and wraps negative values.
     *
     * @param runtimeScalar the {@link RuntimeScalar} containing the code point
     * @return a {@link RuntimeScalar} containing the corresponding character
     */
    public static RuntimeScalar chrBytes(RuntimeScalar runtimeScalar) {
        // Convert string type to number if necessary
        if (runtimeScalar.isString()) {
            runtimeScalar = NumberParser.parseNumber(runtimeScalar);
        }

        int codePoint = runtimeScalar.getInt();

        // Handle special double values
        if (runtimeScalar.type == RuntimeScalarType.DOUBLE) {
            double doubleValue = runtimeScalar.getDouble();
            if (Double.isInfinite(doubleValue) || Double.isNaN(doubleValue)) {
                String value = Double.isNaN(doubleValue) ? "NaN" :
                        (doubleValue > 0 ? "Inf" : "-Inf");
                throw new PerlCompilerException("Cannot chr " + value);
            }
        }

        // In bytes mode, wrap the value modulo 256
        codePoint = codePoint & 0xFF;

        // Create character from byte value
        RuntimeScalar res = new RuntimeScalar(String.valueOf((char) codePoint));
        res.type = BYTE_STRING;
        return res;
    }

    public static RuntimeScalar join(RuntimeScalar runtimeScalar, RuntimeBase list) {
        return joinInternal(runtimeScalar, list, true, false);
    }

    /**
     * Internal join implementation with optional warning control.
     * Used for both explicit join() calls and string interpolation.
     *
     * @param runtimeScalar      The separator
     * @param list               The list to join
     * @param warnOnUndef        Whether to warn about undef values
     * @param isStringInterpolation Whether this is a string interpolation (not an explicit join call)
     * @return The joined string
     */
    private static RuntimeScalar joinInternal(RuntimeScalar runtimeScalar, RuntimeBase list, boolean warnOnUndef,
                                               boolean isStringInterpolation) {
        // TODO - convert octet string back to unicode if needed

        // Collect the list elements first so we know the count before evaluating separator.
        // Perl 5 does not FETCH a tied separator when there are fewer than 2 elements.
        java.util.List<RuntimeScalar> elements = new java.util.ArrayList<>();
        Iterator<RuntimeScalar> iterator = list.iterator();
        while (iterator.hasNext()) {
            elements.add(iterator.next());
        }

        // Fast path: 0 elements -> empty string (check undef separator warning first)
        if (elements.isEmpty()) {
            if (warnOnUndef && runtimeScalar.type == RuntimeScalarType.UNDEF) {
                WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in join or string"),
                        RuntimeScalarCache.scalarEmptyString, "uninitialized");
            }
            return new RuntimeScalar("");
        }

        // Fast path: 1 element -> return that element (no separator evaluation needed)
        // Preserve BYTE_STRING type: in Perl, join doesn't upgrade to UTF-8 unless
        // an input has the UTF-8 flag on. Non-STRING types (INTEGER, DOUBLE, UNDEF)
        // are byte-compatible and should not trigger UTF-8 upgrade.
        if (elements.size() == 1) {
            RuntimeScalar scalar = elements.get(0);
            if (warnOnUndef && !isStringInterpolation && scalar.type == RuntimeScalarType.UNDEF) {
                WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in join or string"),
                        RuntimeScalarCache.scalarEmptyString, "uninitialized");
            }
            RuntimeScalar res = new RuntimeScalar(scalar.toString());
            if (scalar.type != RuntimeScalarType.STRING) {
                res.type = BYTE_STRING;
            }
            return res;
        }

        // 2+ elements: evaluate the separator
        if (warnOnUndef && runtimeScalar.type == RuntimeScalarType.UNDEF) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in join or string"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }

        String delimiter = runtimeScalar.toString();

        // In Perl, join produces a byte-string unless one of the inputs has
        // the UTF-8 flag on. Only STRING type has the flag; INTEGER, DOUBLE,
        // UNDEF, and BYTE_STRING are all byte-compatible.
        boolean hasUtf8 = runtimeScalar.type == RuntimeScalarType.STRING;

        // Join the elements
        StringBuilder sb = new StringBuilder();
        boolean start = true;
        for (RuntimeScalar scalar : elements) {
            if (start) {
                start = false;
            } else {
                sb.append(delimiter);
            }

            // Check if value is undef and generate warning (but not for string interpolation)
            if (warnOnUndef && !isStringInterpolation && scalar.type == RuntimeScalarType.UNDEF) {
                WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in join or string"),
                        RuntimeScalarCache.scalarEmptyString, "uninitialized");
            }

            if (scalar.type == RuntimeScalarType.STRING) {
                hasUtf8 = true;
            }
            sb.append(scalar);
        }
        RuntimeScalar res = new RuntimeScalar(sb.toString());
        if (!hasUtf8) {
            res.type = BYTE_STRING;
        }
        return res;
    }

    /**
     * Join for string interpolation - doesn't warn about undef values.
     * This is used internally by the compiler for string interpolation.
     *
     * @param runtimeScalar The separator (usually empty string)
     * @param list          The list to join
     * @return The joined string
     */
    public static RuntimeScalar joinForInterpolation(RuntimeScalar runtimeScalar, RuntimeBase list) {
        return joinInternal(runtimeScalar, list, false, true);
    }

    /**
     * Join without overload dispatch - used when {@code no overloading} pragma is active.
     * Calls {@code toStringNoOverload()} on each element instead of {@code toString()}.
     *
     * @param runtimeScalar The separator
     * @param list          The list to join
     * @return The joined string
     */
    public static RuntimeScalar joinNoOverload(RuntimeScalar runtimeScalar, RuntimeBase list) {
        String delimiter = runtimeScalar.toStringNoOverload();

        boolean isByteString = runtimeScalar.type == BYTE_STRING || delimiter.isEmpty();

        StringBuilder sb = new StringBuilder();

        Iterator<RuntimeScalar> iterator = list.iterator();
        boolean start = true;
        while (iterator.hasNext()) {
            if (start) {
                start = false;
            } else {
                sb.append(delimiter);
            }
            RuntimeScalar scalar = iterator.next();

            isByteString = isByteString && scalar.type == BYTE_STRING;
            sb.append(scalar.toStringNoOverload());
        }
        RuntimeScalar res = new RuntimeScalar(sb.toString());
        if (isByteString) {
            res.type = BYTE_STRING;
        }
        return res;
    }

    /**
     * String concatenation without overload dispatch - used when {@code no overloading} is active.
     * Calls {@code toStringNoOverload()} on both operands instead of {@code toString()}.
     */
    public static RuntimeScalar stringConcatNoOverload(RuntimeScalar runtimeScalar, RuntimeScalar b) {
        String aStr = runtimeScalar.toStringNoOverload();
        String bStr = b.toStringNoOverload();

        if (runtimeScalar.type == RuntimeScalarType.STRING || b.type == RuntimeScalarType.STRING) {
            return new RuntimeScalar(aStr + bStr);
        }

        if (runtimeScalar.type == BYTE_STRING || b.type == BYTE_STRING) {
            boolean aIsByte = runtimeScalar.type == BYTE_STRING
                    || runtimeScalar.type == RuntimeScalarType.UNDEF
                    || (aStr.isEmpty() && runtimeScalar.type != RuntimeScalarType.STRING);
            boolean bIsByte = b.type == BYTE_STRING
                    || b.type == RuntimeScalarType.UNDEF
                    || (bStr.isEmpty() && b.type != RuntimeScalarType.STRING);
            if (aIsByte && bIsByte) {
                boolean safe = true;
                for (int i = 0; safe && i < aStr.length(); i++) {
                    if (aStr.charAt(i) > 255) {
                        safe = false;
                        break;
                    }
                }
                for (int i = 0; safe && i < bStr.length(); i++) {
                    if (bStr.charAt(i) > 255) {
                        safe = false;
                        break;
                    }
                }
                if (safe) {
                    byte[] aBytes = aStr.getBytes(StandardCharsets.ISO_8859_1);
                    byte[] bBytes = bStr.getBytes(StandardCharsets.ISO_8859_1);
                    byte[] out = new byte[aBytes.length + bBytes.length];
                    System.arraycopy(aBytes, 0, out, 0, aBytes.length);
                    System.arraycopy(bBytes, 0, out, aBytes.length, bBytes.length);
                    return new RuntimeScalar(out);
                }
            }
        }

        return new RuntimeScalar(aStr + bStr);
    }

    /**
     * Helper method to convert a string to UTF-8 bytes representation.
     * Each byte becomes a character in the result string.
     */
    private static RuntimeScalar toUtf8Bytes(RuntimeScalar runtimeScalar) {
        // Under 'use bytes', BYTE_STRING already represents a sequence of octets.
        // Converting it to UTF-8 would expand bytes >= 0x80 into multi-byte sequences,
        // which breaks Perl's byte-semantics for lc/uc/fc/etc.
        if (runtimeScalar.type == BYTE_STRING) {
            return runtimeScalar;
        }

        String str = runtimeScalar.toString();
        byte[] utf8Bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new RuntimeScalar(utf8Bytes);
    }

    /**
     * Case-fold bytes, only affecting ASCII characters (A-Z -> a-z).
     * Non-ASCII bytes are left unchanged.
     */
    private static RuntimeScalar caseFoldBytesAsciiOnly(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        StringBuilder result = new StringBuilder(str.length());

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            // Only lowercase ASCII A-Z (0x41-0x5A)
            if (c >= 'A' && c <= 'Z') {
                result.append((char) (c + 32)); // Convert to lowercase
            } else {
                result.append(c);
            }
        }

        RuntimeScalar out = new RuntimeScalar(result.toString());
        out.type = BYTE_STRING;
        return out;
    }

    /**
     * Uppercase bytes, only affecting ASCII characters (a-z -> A-Z).
     * Non-ASCII bytes are left unchanged.
     */
    private static RuntimeScalar uppercaseBytesAsciiOnly(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        StringBuilder result = new StringBuilder(str.length());

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            // Only uppercase ASCII a-z (0x61-0x7A)
            if (c >= 'a' && c <= 'z') {
                result.append((char) (c - 32)); // Convert to uppercase
            } else {
                result.append(c);
            }
        }

        RuntimeScalar out = new RuntimeScalar(result.toString());
        out.type = BYTE_STRING;
        return out;
    }

    /**
     * Converts to uppercase under 'use bytes' pragma.
     * Operates on the UTF-8 bytes of the input, only affecting ASCII.
     */
    public static RuntimeScalar ucBytes(RuntimeScalar runtimeScalar) {
        RuntimeScalar asBytes = toUtf8Bytes(runtimeScalar);
        return uppercaseBytesAsciiOnly(asBytes);
    }

    /**
     * Converts first character to lowercase under 'use bytes' pragma.
     * Operates on the UTF-8 bytes of the input, only affecting ASCII.
     */
    public static RuntimeScalar lcfirstBytes(RuntimeScalar runtimeScalar) {
        RuntimeScalar asBytes = toUtf8Bytes(runtimeScalar);
        String str = asBytes.toString();
        if (str.isEmpty()) {
            return asBytes;
        }
        // Only lowercase first byte if it's ASCII A-Z
        char first = str.charAt(0);
        if (first >= 'A' && first <= 'Z') {
            RuntimeScalar out = new RuntimeScalar((char) (first + 32) + str.substring(1));
            out.type = BYTE_STRING;
            return out;
        }
        return asBytes;
    }

    /**
     * Converts first character to titlecase under 'use bytes' pragma.
     * Operates on the UTF-8 bytes of the input, only affecting ASCII.
     */
    public static RuntimeScalar ucfirstBytes(RuntimeScalar runtimeScalar) {
        RuntimeScalar asBytes = toUtf8Bytes(runtimeScalar);
        String str = asBytes.toString();
        if (str.isEmpty()) {
            return asBytes;
        }
        // Only uppercase first byte if it's ASCII a-z
        char first = str.charAt(0);
        if (first >= 'a' && first <= 'z') {
            RuntimeScalar out = new RuntimeScalar((char) (first - 32) + str.substring(1));
            out.type = BYTE_STRING;
            return out;
        }
        return asBytes;
    }
}

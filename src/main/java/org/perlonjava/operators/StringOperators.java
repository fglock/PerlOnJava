package org.perlonjava.operators;

import com.ibm.icu.text.CaseMap;
import com.ibm.icu.text.Normalizer2;
import org.perlonjava.parser.NumberParser;
import org.perlonjava.runtime.*;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarType.BYTE_STRING;

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
        // Convert the RuntimeScalar to a string and return its byte length
        String str = runtimeScalar.toString();
        try {
            return getScalarInt(str.getBytes(StandardCharsets.UTF_8).length);
        } catch (Exception e) {
            // If UTF-8 encoding fails, fall back to character count
            return getScalarInt(str.codePointCount(0, str.length()));
        }
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
            // If the character is alphanumeric, append it as is
            if (Character.isLetterOrDigit(c)) {
                quoted.append(c);
            } else {
                // Otherwise, escape it with a backslash
                quoted.append("\\").append(c);
            }
        }
        return new RuntimeScalar(quoted.toString());
    }

    /**
     * Performs full Unicode case folding on the string representation of the given {@link RuntimeScalar}.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be case folded
     * @return a {@link RuntimeScalar} with the case-folded string
     */
    public static RuntimeScalar fc(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Step 1: Normalize the string to NFKC form (Compatibility Composition)
        Normalizer2 normalizer = Normalizer2.getNFKCInstance();
        String normalized = normalizer.normalize(str);

        // Step 2: Perform full Unicode case folding using ICU4J CaseMap
        str = CaseMap.fold().apply(normalized);

        return new RuntimeScalar(str);
    }

    /**
     * Converts the string representation of the given {@link RuntimeScalar} to lowercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be converted to lowercase
     * @return a {@link RuntimeScalar} with the lowercase string
     */
    public static RuntimeScalar lc(RuntimeScalar runtimeScalar) {
        // Convert the string to lowercase
        return new RuntimeScalar(runtimeScalar.toString().toLowerCase());
    }

    /**
     * Converts the first character of the string representation of the given {@link RuntimeScalar} to lowercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose first character is to be converted to lowercase
     * @return a {@link RuntimeScalar} with the first character in lowercase
     */
    public static RuntimeScalar lcfirst(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Check if the string is empty
        if (str.isEmpty()) {
            return new RuntimeScalar(str);
        }
        // Convert the first character to lowercase and concatenate with the rest of the string
        return new RuntimeScalar(str.substring(0, 1).toLowerCase() + str.substring(1));
    }

    /**
     * Converts the string representation of the given {@link RuntimeScalar} to uppercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be converted to uppercase
     * @return a {@link RuntimeScalar} with the uppercase string
     */
    public static RuntimeScalar uc(RuntimeScalar runtimeScalar) {
        // Convert the string to uppercase
        return new RuntimeScalar(runtimeScalar.toString().toUpperCase());
    }

    /**
     * Converts the first character of the string representation of the given {@link RuntimeScalar} to uppercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose first character is to be converted to uppercase
     * @return a {@link RuntimeScalar} with the first character in uppercase
     */
    public static RuntimeScalar ucfirst(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Check if the string is empty
        if (str.isEmpty()) {
            return new RuntimeScalar(str);
        }
        // Convert the first character to uppercase and concatenate with the rest of the string
        return new RuntimeScalar(str.substring(0, 1).toUpperCase() + str.substring(1));
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
        return new RuntimeScalar(runtimeScalar + b.toString());
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
            runtimeScalar.set(str);
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

        runtimeScalar.set(remainingStr);
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

        // TODO - convert octet string back to unicode if needed

        // Check if separator is undef and generate warning
        if (runtimeScalar.type == RuntimeScalarType.UNDEF) {
            WarnDie.warn(new RuntimeScalar("Use of uninitialized value in join or string"),
                    RuntimeScalarCache.scalarEmptyString);
        }

        boolean isByteString = runtimeScalar.type == BYTE_STRING;

        String delimiter = runtimeScalar.toString();
        // Join the list into a string
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

            // Check if value is undef and generate warning
            if (scalar.type == RuntimeScalarType.UNDEF) {
                WarnDie.warn(new RuntimeScalar("Use of uninitialized value in join or string"),
                        RuntimeScalarCache.scalarEmptyString);
            }

            isByteString = isByteString && scalar.type == BYTE_STRING;
            sb.append(scalar);
        }
        RuntimeScalar res = new RuntimeScalar(sb.toString());
        if (isByteString) {
            res.type = BYTE_STRING;
        }
        return res;
    }
}

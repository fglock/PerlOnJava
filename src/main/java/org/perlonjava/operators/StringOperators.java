package org.perlonjava.operators;

import com.ibm.icu.text.CaseMap;
import com.ibm.icu.text.Normalizer2;
import org.perlonjava.parser.NumberParser;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

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
        // Convert the RuntimeScalar to a string and return its length in codepoints
        String str = runtimeScalar.toString();
        return getScalarInt(str.codePointCount(0, str.length()));
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
        } else if (pos >= str.length()) {
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

        // Bound the position to be within the valid range of the string
        if (pos >= str.length()) {
            pos = str.length() - 1;
        } else if (pos < 0) {
            return getScalarInt(-1);
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

        int codePoint = runtimeScalar.getInt(); // Get the integer representing the code point

        // Check if it's a double (which could be Inf or NaN)
        if (runtimeScalar.type == RuntimeScalarType.DOUBLE) {
            double doubleValue = runtimeScalar.getDouble();
            if (Double.isInfinite(doubleValue) || Double.isNaN(doubleValue)) {
                String value = Double.isNaN(doubleValue) ? "NaN" :
                        (doubleValue > 0 ? "Inf" : "-Inf");
                throw new PerlCompilerException("Cannot chr " + value);
            }
            if (doubleValue < 0) {
                codePoint = 0xFFFD;  // Unicode replacement character
            }
        } else {
            if (codePoint < 0) {
                codePoint = 0xFFFD;  // Unicode replacement character
            }
        }

        // For code points 0-255, create a single-byte string
        if (codePoint <= 0xFF) {
            RuntimeScalar res = new RuntimeScalar(String.valueOf((char) codePoint));
            res.type = BYTE_STRING;
            return res;
        }

        // For valid Unicode code points in BMP (excluding surrogates)
        if (codePoint <= 0xFFFF && (codePoint < 0xD800 || codePoint > 0xDFFF)) {
            return new RuntimeScalar(String.valueOf((char) codePoint));
        }

        // For valid Unicode code points outside BMP (use surrogate pairs)
        if (codePoint >= 0x10000 && codePoint <= 0x10FFFF) {
            return new RuntimeScalar(new String(Character.toChars(codePoint)));
        }

        // For surrogates and invalid Unicode, we need to create a byte string
        // that represents what Perl would create (UTF-8 encoding)
        // This is tricky in Java because we can't store invalid UTF-16 in a String

        // For now, let's create a string that when unpacked with U0 (H2)*
        // will produce the expected UTF-8 bytes
        byte[] utf8Bytes;

        if (codePoint <= 0x7FF) {
            // 2-byte UTF-8
            utf8Bytes = new byte[]{
                    (byte)(0xC0 | (codePoint >> 6)),
                    (byte)(0x80 | (codePoint & 0x3F))
            };
        } else if (codePoint <= 0xFFFF) {
            // 3-byte UTF-8 (includes surrogates)
            utf8Bytes = new byte[]{
                    (byte)(0xE0 | (codePoint >> 12)),
                    (byte)(0x80 | ((codePoint >> 6) & 0x3F)),
                    (byte)(0x80 | (codePoint & 0x3F))
            };
        } else if (codePoint <= 0x1FFFFF) {
            // 4-byte UTF-8
            utf8Bytes = new byte[]{
                    (byte)(0xF0 | (codePoint >> 18)),
                    (byte)(0x80 | ((codePoint >> 12) & 0x3F)),
                    (byte)(0x80 | ((codePoint >> 6) & 0x3F)),
                    (byte)(0x80 | (codePoint & 0x3F))
            };
        } else if (codePoint <= 0x3FFFFFF) {
            // 5-byte UTF-8
            utf8Bytes = new byte[]{
                    (byte)(0xF8 | (codePoint >> 24)),
                    (byte)(0x80 | ((codePoint >> 18) & 0x3F)),
                    (byte)(0x80 | ((codePoint >> 12) & 0x3F)),
                    (byte)(0x80 | ((codePoint >> 6) & 0x3F)),
                    (byte)(0x80 | (codePoint & 0x3F))
            };
        } else {
            // 6-byte UTF-8
            utf8Bytes = new byte[]{
                    (byte)(0xFC | (codePoint >> 30)),
                    (byte)(0x80 | ((codePoint >> 24) & 0x3F)),
                    (byte)(0x80 | ((codePoint >> 18) & 0x3F)),
                    (byte)(0x80 | ((codePoint >> 12) & 0x3F)),
                    (byte)(0x80 | ((codePoint >> 6) & 0x3F)),
                    (byte)(0x80 | (codePoint & 0x3F))
            };
        }

        // Convert UTF-8 bytes to a string where each byte becomes a char
        // This is what Perl does internally for invalid Unicode
        char[] chars = new char[utf8Bytes.length];
        for (int i = 0; i < utf8Bytes.length; i++) {
            chars[i] = (char)(utf8Bytes[i] & 0xFF);
        }

        RuntimeScalar res = new RuntimeScalar(new String(chars));
        res.type = BYTE_STRING;
        return res;
    }

    public static RuntimeScalar join(RuntimeScalar runtimeScalar, RuntimeBase list) {

        // TODO - convert octet string back to unicode if needed

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
            isByteString = isByteString && scalar.type == BYTE_STRING;
            sb.append(scalar.toString());
        }
        RuntimeScalar res = new RuntimeScalar(sb.toString());
        if (isByteString) {
            res.type = BYTE_STRING;
        }
        return res;
    }
}

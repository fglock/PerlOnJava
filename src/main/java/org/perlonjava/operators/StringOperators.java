package org.perlonjava.operators;

import com.ibm.icu.text.CaseMap;
import com.ibm.icu.text.Normalizer2;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

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
        // Convert the RuntimeScalar to a string and return its length as a RuntimeScalar
        return getScalarInt(runtimeScalar.toString().length());
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

        if (charsRemoved > 0) {
            runtimeScalar.set(str);
        }
        return getScalarInt(charsRemoved);
    }

    public static RuntimeScalar chopScalar(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        if (str.isEmpty()) {
            return new RuntimeScalar();
        }
        String lastChar = str.substring(str.length() - 1);
        runtimeScalar.set(str.substring(0, str.length() - 1));
        return new RuntimeScalar(lastChar);
    }

    public static RuntimeScalar chr(RuntimeScalar runtimeScalar) {
        int codePoint = runtimeScalar.getInt(); // Get the integer representing the code point

        // Convert the code point to a char array (to handle both BMP and non-BMP)
        char[] chars = Character.toChars(codePoint);

        // Create a string from the char array
        return new RuntimeScalar(String.valueOf(chars));
    }
}

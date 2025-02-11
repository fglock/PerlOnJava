package org.perlonjava.runtime;

import org.perlonjava.parser.NumberParser;

import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * Utility class for scalar operations in the Perlon Java runtime.
 */
public class ScalarUtils {

    // make a string printable
    public static String printable(String string) {
        if (string == null) {
            return "null";
        }
        if (string.isEmpty()) {
            return "empty";
        }

        StringBuilder result = new StringBuilder();
        for (char c : string.toCharArray()) {
            if (Character.isISOControl(c)) {
                result.append(String.format("\\x%02X", (int) c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Increments a string value based on specific rules.
     *
     * <p>This method implements a string increment operation that follows these rules:
     * <ul>
     *   <li>For single-character strings:
     *     <ul>
     *       <li>Digits '0' to '8' are incremented to the next digit</li>
     *       <li>'9' is incremented to '10'</li>
     *       <li>Letters 'A' to 'Y' and 'a' to 'y' are incremented to the next letter</li>
     *       <li>'Z' is incremented to 'AA', 'z' is incremented to 'aa'</li>
     *     </ul>
     *   </li>
     *   <li>For multi-character strings:
     *     <ul>
     *       <li>Increment is applied to the last character and carried over if necessary</li>
     *       <li>Carry-over follows the same rules as single-character increments</li>
     *       <li>If carry-over reaches the start of the string, a new character is prepended</li>
     *     </ul>
     *   </li>
     * </ul>
     * </p>
     *
     * @param str The input string to be incremented
     * @return The incremented string
     * @throws PerlCompilerException if the input string is null or empty
     */
    public static String incrementPlainString(String str) {
        if (str == null || str.isEmpty()) {
            return str; // Consider throwing an PerlCompilerException instead
        }

        char lastChar = str.charAt(str.length() - 1);

        if ((lastChar >= '0' && lastChar <= '8') ||
                (lastChar >= 'A' && lastChar <= 'Y') ||
                (lastChar >= 'a' && lastChar <= 'y')) {
            return str.substring(0, str.length() - 1) + (char) (lastChar + 1);
        } else {
            StringBuilder result = new StringBuilder(str);
            int i = result.length() - 1;
            boolean carry = true;

            while (i >= 0 && carry) {
                char c = result.charAt(i);
                if ((c >= '0' && c < '9') || (c >= 'A' && c < 'Z') || (c >= 'a' && c < 'z')) {
                    result.setCharAt(i, (char) (c + 1));
                    carry = false;
                } else if (c == '9') {
                    result.setCharAt(i, '0');
                } else if (c == 'Z') {
                    result.setCharAt(i, 'A');
                } else if (c == 'z') {
                    result.setCharAt(i, 'a');
                } else {
                    result.setCharAt(i, '1');
                    carry = false;
                }
                i--;
            }

            if (carry) {
                if (result.charAt(0) == '0') {
                    result.insert(0, '1');
                } else if (result.charAt(0) == 'A') {
                    result.insert(0, 'A');
                } else if (result.charAt(0) == 'a') {
                    result.insert(0, 'a');
                }
            }

            return result.toString();
        }
    }

    /**
     * Checks if the given string represents a valid integer.
     *
     * <p>This method determines whether the input string can be parsed as an integer.
     * It returns true if the string represents a valid integer, and false otherwise.</p>
     *
     * <p>The method considers the following cases:
     * <ul>
     *   <li>Returns false for null or empty strings</li>
     *   <li>Returns true for strings that can be parsed as integers</li>
     *   <li>Returns false for strings that cannot be parsed as integers (including decimals,
     *       non-numeric characters, etc.)</li>
     * </ul>
     * </p>
     *
     * @param str The string to be checked for integer representation
     * @return true if the string represents a valid integer, false otherwise
     */
    public static boolean isInteger(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static boolean looksLikeNumber(RuntimeScalar runtimeScalar) {
        switch (runtimeScalar.type) {
            case INTEGER:
            case DOUBLE:
                return true;
            case STRING:
                String str = runtimeScalar.toString().trim();
                if (str.isEmpty()) {
                    return false;
                }
                // Check for Inf and NaN
                if (str.equalsIgnoreCase("Inf") || str.equalsIgnoreCase("Infinity") || str.equalsIgnoreCase("NaN")) {
                    return true;
                }
                // Check for decimal (integer or float)
                try {
                    Double.parseDouble(str);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            case UNDEF:
                return false;
            case GLOB:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
            case CODE:
                // These types don't look like numbers in Perl
                return false;
            default:
                throw new PerlCompilerException("Unexpected value: " + runtimeScalar.type);
        }
    }

    /**
     * Helper method to autoincrement a String variable
     *
     * @param runtimeScalar The scalar to be autoincremented
     * @return The autoincremented scalar
     */
    public static RuntimeScalar stringIncrement(RuntimeScalar runtimeScalar) {
        // Retrieve the current value as a String
        String str = (String) runtimeScalar.value;

        // Check if the string is empty
        if (str.isEmpty()) {
            // If empty, set the value to 1 and update type to INTEGER
            runtimeScalar.value = 1;
            runtimeScalar.type = INTEGER; // RuntimeScalarType is an enum that holds different scalar types
            return runtimeScalar; // Return the current instance
        }

        // Get the first character of the string
        char c = str.charAt(0);

        // Check if the first character is a letter (either uppercase or lowercase)
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
            runtimeScalar.value = incrementPlainString(str);
            return runtimeScalar; // Return the current instance after increment
        }

        // Handle numeric increment: parse the number and increment it
        runtimeScalar.set(NumberParser.parseNumber(runtimeScalar)); // parseNumber parses the current string to a number
        return runtimeScalar.preAutoIncrement(); // preAutoIncrement handles the actual incrementing logic
    }

    /**
     * This is used by evalbytes to assert that the operand contains only byte characters.
     *
     * @param str The string to check
     */
    public static void assertBytes(RuntimeScalar str) {
        for (char c : str.toString().toCharArray()) {
            if (c > 255) {
                throw new IllegalArgumentException("evalbytes operand contains non-byte characters.");
            }
        }
    }
}
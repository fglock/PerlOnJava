package org.perlonjava.runtime;

/**
 * Utility class for scalar operations in the Perlon Java runtime.
 */
public class ScalarUtils {

    /**
     * Increments a string value based on specific rules.
     *
     * <p>This method implements a custom string increment operation that follows these rules:
     * <ul>
     *   <li>For single-character strings:
     *     <ul>
     *       <li>Digits '0' to '8' are incremented to the next digit</li>
     *       <li>'9' becomes "10"</li>
     *       <li>Letters 'A' to 'Y' and 'a' to 'y' are incremented to the next letter</li>
     *       <li>'Z' becomes "AA"</li>
     *       <li>'z' becomes "aa"</li>
     *       <li>Any other character becomes "1"</li>
     *     </ul>
     *   </li>
     *   <li>For multi-character strings, the increment is applied to the last character,
     *       with carry-over to preceding characters if necessary</li>
     * </ul>
     * </p>
     *
     * @param s The input string to be incremented
     * @return The incremented string
     */
    static String stringIncrement(String s) {
        StringBuilder result = new StringBuilder(s);
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

    /**
     * Increments a plain string value based on specific rules.
     *
     * <p>This method implements a string increment operation that follows these rules:
     * <ul>
     *   <li>For single-character strings:
     *     <ul>
     *       <li>Digits '0' to '8' are incremented to the next digit</li>
     *       <li>Letters 'A' to 'Y' and 'a' to 'y' are incremented to the next letter</li>
     *       <li>For '9', 'Z', 'z', or any other character, it delegates to {@link #stringIncrement(String)}</li>
     *     </ul>
     *   </li>
     *   <li>For multi-character strings, the increment is applied to the last character,
     *       with complex cases handled by {@link #stringIncrement(String)}</li>
     * </ul>
     * </p>
     *
     * @param str The input string to be incremented
     * @return The incremented string
     */
    public static String incrementPlainString(String str) {
        if (str == null || str.isEmpty()) {
            return str; // Consider throwing an IllegalArgumentException instead
        }

        char lastChar = str.charAt(str.length() - 1);

        if ((lastChar >= '0' && lastChar <= '8') ||
            (lastChar >= 'A' && lastChar <= 'Y') ||
            (lastChar >= 'a' && lastChar <= 'y')) {
            return str.substring(0, str.length() - 1) + (char) (lastChar + 1);
        } else {
            return stringIncrement(str);
        }
    }}
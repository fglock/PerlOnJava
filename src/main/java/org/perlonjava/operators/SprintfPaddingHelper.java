package org.perlonjava.operators;

/**
 * Helper class for sprintf padding operations.
 *
 * This class provides utilities for applying width, precision, and
 * zero-padding to formatted strings according to sprintf semantics.
 */
public class SprintfPaddingHelper {

    /**
     * Apply width formatting to a string.
     *
     * @param str   The string to format
     * @param width The desired width
     * @param flags Format flags (contains '-' for left-align, '0' for zero-pad)
     * @return The padded string
     */
    public static String applyWidth(String str, int width, String flags) {
        if (width <= 0 || str.length() >= width) {
            return str;
        }

        boolean leftAlign = flags.contains("-");
        boolean zeroPad = flags.contains("0") && !leftAlign;

        if (leftAlign) {
            return padRight(str, width);
        } else if (zeroPad) {
            return applyZeroPadding(str, width);
        } else {
            return padLeft(str, width);
        }
    }

    /**
     * Apply precision to a vector element.
     *
     * Vector precision works by zero-padding numeric values
     * while preserving any prefixes (sign, 0x, etc.).
     *
     * @param formatted The formatted value
     * @param precision The desired precision
     * @param flags     Format flags
     * @return The value with precision applied
     */
    public static String applyVectorPrecision(String formatted, int precision, String flags) {
        if (precision <= 0) return formatted;

        // Extract prefix and numeric parts
        PrefixedNumber parts = extractPrefixedNumber(formatted);

        // Apply precision padding to the numeric part
        if (parts.number.length() < precision) {
            int padWidth = precision - parts.number.length();
            parts.number = "0".repeat(padWidth) + parts.number;
        }

        return parts.prefix + parts.number;
    }

    /**
     * Pad a string on the left with spaces.
     */
    public static String padLeft(String str, int width) {
        return padLeft(str, width, ' ');
    }

    /**
     * Pad a string on the left with a specified character.
     */
    public static String padLeft(String str, int width, char padChar) {
        if (str.length() >= width) return str;
        return String.valueOf(padChar).repeat(width - str.length()) + str;
    }

    /**
     * Pad a string on the right with spaces.
     */
    public static String padRight(String str, int width) {
        if (str.length() >= width) return str;
        return str + " ".repeat(width - str.length());
    }

    /**
     * Apply zero padding to a formatted number.
     *
     * Zero padding goes after sign/prefix but before the numeric value.
     * For example: -42 with width 6 becomes -00042
     *              0xFF with width 8 becomes 0x0000FF
     *
     * @param str   The formatted string
     * @param width The desired width
     * @return The zero-padded string
     */
    public static String applyZeroPadding(String str, int width) {
        if (str.length() >= width) return str;

        PrefixedNumber parts = extractPrefixedNumber(str);

        int totalLength = parts.prefix.length() + parts.number.length();
        int padLength = width - totalLength;

        if (padLength > 0) {
            return parts.prefix + "0".repeat(padLength) + parts.number;
        }

        return str;
    }

    /**
     * Extract prefix (sign and/or base indicator) and numeric parts from a string.
     */
    private static PrefixedNumber extractPrefixedNumber(String str) {
        PrefixedNumber result = new PrefixedNumber();
        result.number = str;

        // Extract sign if present
        if (str.startsWith("-") || str.startsWith("+") || str.startsWith(" ")) {
            result.prefix = str.substring(0, 1);
            result.number = str.substring(1);
        }

        // Extract base prefix if present
        if (result.number.startsWith("0x") || result.number.startsWith("0X") ||
            result.number.startsWith("0b") || result.number.startsWith("0B")) {
            result.prefix += result.number.substring(0, 2);
            result.number = result.number.substring(2);
        } else if (result.number.startsWith("0") && result.number.length() > 1 &&
                   Character.isDigit(result.number.charAt(1))) {
            // Octal prefix - but only treat as prefix for formatting purposes
            // if explicitly requested
        }

        return result;
    }

    /**
     * Container for a number split into prefix and numeric parts.
     */
    private static class PrefixedNumber {
        String prefix = "";
        String number = "";
    }
}
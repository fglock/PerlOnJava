package org.perlonjava.operators;

import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeScalar;

/**
 * Handles vector string formatting for sprintf operations.
 *
 * <p>Vector formatting (indicated by the 'v' flag) treats the input as a sequence
 * of values and formats each one according to the conversion specifier, joining
 * them with dots. This class supports two types of vector input:
 * <ul>
 *   <li>Version strings - dotted numeric strings like "5.10.1"</li>
 *   <li>Byte strings - regular strings where each character is treated as a byte value</li>
 * </ul>
 *
 * <p>Example vector formats:
 * <pre>
 *   sprintf "%vd", "ABC"      # "65.66.67"
 *   sprintf "%vx", "ABC"      # "41.42.43"
 *   sprintf "%v.2x", "ABC"    # "41.42.43" (with precision)
 *   sprintf "%vd", "5.10.1"   # "5.10.1" (version string)
/**
  * </pre>
  */
public class SprintfVectorFormatter {

    /**
      * Format a vector string with custom separator support.
      */
    public String formatVectorString(RuntimeScalar value, String flags, int width,
                                int precision, char conversionChar, String separator) {
    // Handle version objects specially
    if (value.isBlessed()) {
        String className = ReferenceOperators.ref(value).toString();

        if (className.equals("version")) {
            RuntimeHash versionObj = value.hashDeref();
            // Use the original representation for sprintf
            RuntimeScalar originalScalar = versionObj.get("original");
            if (originalScalar.getDefinedBoolean()) {
                String originalStr = originalScalar.toString();
                // System.err.println("DEBUG: Version object detected, using original: " + originalStr);

                // If original starts with 'v', it's a v-string format
                if (originalStr.startsWith("v")) {
                    // Remove the 'v' and format as version vector
                    return formatVersionVector(originalStr.substring(1), flags, width, precision, conversionChar, separator);
                } else {
                    // It's a decimal version string
                    return formatVersionVector(originalStr, flags, width, precision, conversionChar, separator);
                }
            }
        }
    }

    String str = value.toString();

    // Handle version objects or dotted numeric strings
    if (str.matches("\\d+(\\.\\d+)*")) {
        return formatVersionVector(str, flags, width, precision, conversionChar, separator);
    }

    // Handle regular strings (byte-by-byte)
    return formatByteVector(str, flags, width, precision, conversionChar, separator);
}

    // Keep the original method for backward compatibility
    public String formatVectorString(RuntimeScalar value, String flags, int width,
                                    int precision, char conversionChar) {
        return formatVectorString(value, flags, width, precision, conversionChar, ".");
    }

  /**
    * Format a version-style vector (dotted numeric string).
    *
    * <p>Version strings like "5.10.1" are split on dots and each numeric
    * component is formatted individually. This is commonly used for
    * version number display.
    *
    * @param versionStr The version string to format
    * @param flags Format flags
    * @param width Field width for the entire result
    * @param precision Precision for each element
    * @param conversionChar Conversion character
    * @return The formatted version vector
    */
  private String formatVersionVector(String versionStr, String flags, int width,
                                   int precision, char conversionChar) {
      return formatVersionVector(versionStr, flags, width, precision, conversionChar, ".");
  }

    private String formatVersionVector(String versionStr, String flags, int width,
                                       int precision, char conversionChar, String separator) {
        String[] parts = versionStr.split("\\.");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result.append(separator);

            // For vector formats, + and space flags only apply to first element
            String elementFlags = flags;
            if (i > 0) {
                elementFlags = flags.replace("+", "").replace(" ", "");
            }

            try {
                int intValue = Integer.parseInt(parts[i]);
                String formatted = formatVectorValue(intValue, elementFlags, width, precision, conversionChar);
                result.append(formatted);
            } catch (NumberFormatException e) {
                result.append(parts[i]);
            }
        }

        return result.toString();
    }

    /**
      * Format a byte vector (string treated as sequence of bytes).
      *
     * <p>Each character in the string is converted to its byte value (0-255)
     * and formatted according to the conversion specifier. The results are
     * joined with dots.
     *
     * @param str The string to treat as a byte sequence
     * @param flags Format flags
     * @param width Field width for the entire result
     * @param precision Precision for each byte
     * @param conversionChar Conversion character
     * @return The formatted byte vector
     */
    private String formatByteVector(String str, String flags, int width,
                                    int precision, char conversionChar, String separator) {
        if (str.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        int[] codePoints = str.codePoints().toArray();

        for (int i = 0; i < codePoints.length; i++) {
            if (i > 0) result.append(separator);

            int value = codePoints[i];

            // For vector formats, + and space flags only apply to first element
            String elementFlags = flags;
            if (i > 0) {
                // Remove + and space flags for non-first elements
                elementFlags = flags.replace("+", "").replace(" ", "");
            }

            String formatted = formatVectorValue(value, elementFlags, width, precision, conversionChar);
            result.append(formatted);
        }

        return result.toString();
    }

    /**
     * Format a single vector element value.
     *
     * <p>This method formats an individual numeric value according to the
     * conversion character, applying the appropriate base conversion and
     * prefix handling.
     *
     * @param byteValue The numeric value to format (0-255 for bytes)
     * @param flags Format flags
     * @param precision Precision for this element
     * @param conversionChar Conversion character
     * @return The formatted element
     */
    private String formatVectorValue(int byteValue, String flags, int width, int precision,
                               char conversionChar) {
        String formatted = switch (conversionChar) {
            case 'd', 'i' -> formatVectorDecimal(byteValue, flags);
            case 'o' -> formatVectorOctal(byteValue, flags);
            case 'x' -> formatVectorHex(byteValue, flags, false);
            case 'X' -> formatVectorHex(byteValue, flags, true);
            case 'b', 'B' -> formatVectorBinary(byteValue, flags, conversionChar);
            default -> String.valueOf(byteValue);
        };

        // Apply precision first
        formatted = SprintfPaddingHelper.applyVectorPrecision(formatted, precision, flags);

        // For vector formats, if precision is specified, ignore the '0' flag
        String widthFlags = flags;
        if (precision >= 0 && flags.contains("0")) {
            widthFlags = flags.replace("0", "");
        }

        // Then apply width to the individual element
        return SprintfPaddingHelper.applyWidth(formatted, width, widthFlags);
    }

    /**
     * Format vector element as decimal.
     *
     * <p>Formats the value as a decimal number, applying sign flags
     * (+ or space) if specified.
     *
     * @param byteValue The value to format
     * @param flags Format flags
     * @return The formatted decimal string
     */
    private String formatVectorDecimal(int byteValue, String flags) {
        String formatted = String.valueOf(byteValue);

        // Apply sign flags for ALL numbers (not just positive)
        if (flags.contains("+")) {
            formatted = "+" + formatted;  // Always add + for positive numbers
        } else if (flags.contains(" ")) {
            formatted = " " + formatted;  // Always add space for positive numbers
        }

        return formatted;
    }

    /**
     * Format vector element as octal.
     *
     * <p>Formats the value as an octal number. The # flag adds a leading
     * zero for non-zero values.
     *
     * @param byteValue The value to format
     * @param flags Format flags
     * @return The formatted octal string
     */
    private String formatVectorOctal(int byteValue, String flags) {
        String formatted = Integer.toOctalString(byteValue);

        // Add octal prefix if # flag is set and value is non-zero
        if (flags.contains("#") && byteValue != 0) {
            formatted = "0" + formatted;
        }

        return formatted;
    }

    /**
     * Format vector element as hexadecimal.
     *
     * <p>Formats the value as a hexadecimal number. The # flag adds a
     * "0x" or "0X" prefix for non-zero values.
     *
     * @param byteValue The value to format
     * @param flags Format flags
     * @param uppercase Whether to use uppercase letters (A-F)
     * @return The formatted hexadecimal string
     */
    private String formatVectorHex(int byteValue, String flags, boolean uppercase) {
        String formatted = Integer.toHexString(byteValue);

        if (uppercase) {
            formatted = formatted.toUpperCase();
        }

        // Add hex prefix if # flag is set and value is non-zero
        if (flags.contains("#") && byteValue != 0) {
            formatted = (uppercase ? "0X" : "0x") + formatted;
        }

        return formatted;
    }

    /**
     * Format vector element as binary.
     *
     * <p>Formats the value as a binary number. The # flag adds a
     * "0b" or "0B" prefix for non-zero values.
     *
     * @param byteValue The value to format
     * @param flags Format flags
     * @param conversionChar 'b' for lowercase prefix, 'B' for uppercase
     * @return The formatted binary string
     */
    private String formatVectorBinary(int byteValue, String flags, char conversionChar) {
        String formatted = Integer.toBinaryString(byteValue);

        // Add binary prefix if # flag is set and value is non-zero
        if (flags.contains("#") && byteValue != 0) {
            formatted = (conversionChar == 'B' ? "0B" : "0b") + formatted;
        }

        return formatted;
    }
}
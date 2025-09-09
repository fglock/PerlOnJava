package org.perlonjava.operators.sprintf;

import org.perlonjava.operators.ReferenceOperators;
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
 * /**
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

                    // For version objects created with version->new(), use the original
                    // This preserves the exact format (e.g., "1.2" not "1.2.0")
                    if (originalStr.matches("\\d+(\\.\\d+)*")) {
                        return formatVersionVector(originalStr, flags, width, precision, conversionChar, separator);
                    }

                    // For v-strings (e.g., "v1.2.3"), remove the 'v'
                    if (originalStr.startsWith("v")) {
                        return formatVersionVector(originalStr.substring(1), flags, width, precision, conversionChar, separator);
                    }
                }
            }
        }

        String str = value.toString();

        // Handle version objects or dotted numeric strings
        // ONLY treat as version if it contains dots!
        if (str.contains(".") && str.matches("\\d+(\\.\\d+)*")) {
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
     * @param versionStr     The version string to format
     * @param flags          Format flags
     * @param width          Field width for the entire result
     * @param precision      Precision for each element
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
     * @param str            The string to treat as a byte sequence
     * @param flags          Format flags
     * @param width          Field width for the entire result
     * @param precision      Precision for each byte
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
                elementFlags = flags.replace("+", "").replace(" ", "");
            }

            //

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
     * @param byteValue      The numeric value to format (0-255 for bytes)
     * @param flags          Format flags
     * @param precision      Precision for this element
     * @param conversionChar Conversion character
     * @return The formatted element
     */
    private String formatVectorValue(int byteValue, String flags, int width, int precision,
                                     char conversionChar) {
        // First format the base value
        String formatted = switch (conversionChar) {
            case 'd', 'i' -> String.valueOf(byteValue);
            case 'o' -> Integer.toOctalString(byteValue);
            case 'x' -> Integer.toHexString(byteValue);
            case 'X' -> Integer.toHexString(byteValue).toUpperCase();
            case 'b', 'B' -> Integer.toBinaryString(byteValue);
            default -> String.valueOf(byteValue);
        };

        // Handle precision 0 with value 0
        if (precision == 0 && byteValue == 0 && "diouxXbB".indexOf(conversionChar) >= 0) {
            formatted = "";
            // But for octal with # flag, it's "0"
            if (conversionChar == 'o' && flags.contains("#")) {
                formatted = "0";
            }
        } else if (precision > 0) {
            // Apply precision (minimum digits)
            if (formatted.length() < precision) {
                formatted = "0".repeat(precision - formatted.length()) + formatted;
            }
        }

        // Apply prefixes ONLY for non-zero values
        if (flags.contains("#") && byteValue != 0) {  // Check original value, not formatted string
            switch (conversionChar) {
                case 'o':
                    if (!formatted.startsWith("0")) {
                        formatted = "0" + formatted;
                    }
                    break;
                case 'x':
                    formatted = "0x" + formatted;
                    break;
                case 'X':
                    formatted = "0X" + formatted;
                    break;
                case 'b':
                    formatted = "0b" + formatted;
                    break;
                case 'B':
                    formatted = "0B" + formatted;
                    break;
            }
        }
        // Note: NO prefix for zero values even with # flag (except octal special case above)

        // Apply sign flags for decimal
        if ((conversionChar == 'd' || conversionChar == 'i') && byteValue >= 0) {
            if (flags.contains("+")) {
                formatted = "+" + formatted;
            } else if (flags.contains(" ")) {
                formatted = " " + formatted;
            }
        }

        // Apply width with appropriate padding
        if (width > 0 && formatted.length() < width) {
            if (flags.contains("-")) {
                // Left align
                formatted = formatted + " ".repeat(width - formatted.length());
            } else if (flags.contains("0") && precision < 0) {
                // Zero pad (only if no precision specified)
                // Need to handle signs and prefixes
                String prefix = "";
                String number = formatted;

                // Extract prefix
                if (formatted.startsWith("+") || formatted.startsWith("-") || formatted.startsWith(" ")) {
                    prefix = formatted.substring(0, 1);
                    number = formatted.substring(1);
                } else if (formatted.startsWith("0x") || formatted.startsWith("0X") ||
                        formatted.startsWith("0b") || formatted.startsWith("0B")) {
                    prefix = formatted.substring(0, 2);
                    number = formatted.substring(2);
                }

                // Apply zero padding
                int padWidth = width - prefix.length() - number.length();
                if (padWidth > 0) {
                    formatted = prefix + "0".repeat(padWidth) + number;
                }
            } else {
                // Space pad
                formatted = " ".repeat(width - formatted.length()) + formatted;
            }
        }

        return formatted;
    }

    /**
     * Format vector element as decimal.
     *
     * <p>Formats the value as a decimal number, applying sign flags
     * (+ or space) if specified.
     *
     * @param byteValue The value to format
     * @param flags     Format flags
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
     * @param flags     Format flags
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
     * @param flags     Format flags
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
     * @param byteValue      The value to format
     * @param flags          Format flags
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
package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.PerlCompilerException;

/**
 * Handles numeric formatting for sprintf operations.
 *
 * <p>This class contains all the logic for formatting numeric values including:
 * <ul>
 *   <li>Integers (decimal, octal, hexadecimal, binary)</li>
 *   <li>Floating-point numbers (fixed, exponential, general)</li>
 *   <li>Special values (NaN, Infinity)</li>
 *   <li>Unsigned integers</li>
 * </ul>
 *
 * <p>The formatter handles various format flags and modifiers according to
 * Perl's sprintf semantics, including sign handling, alternate forms,
 * zero-padding, and precision.
 */
public class SprintfNumericFormatter {

    /**
     * Format special floating-point values (NaN, Inf).
     *
     * <p>Special values are formatted as:
     * <ul>
     *   <li>NaN for Not-a-Number</li>
     *   <li>Inf for positive infinity</li>
     *   <li>-Inf for negative infinity</li>
     * </ul>
     *
     * @param value The double value to check and format
     * @param flags Format flags (may include + for sign)
     * @param width Field width for padding
     * @param conversion The conversion character (throws error for 'c')
     * @return The formatted special value string
     * @throws PerlCompilerException if trying to format special value with %c
     */
    public String formatSpecialValue(double value, String flags, int width, char conversion) {
        String result = Double.isNaN(value) ? "NaN" : (value > 0 ? "Inf" : "-Inf");

        // Special values with %c should throw an error
        if (conversion == 'c') {
            throw new PerlCompilerException("Cannot printf " + result + " with 'c'");
        }

        // Apply + flag for positive infinity
        if (flags.contains("+") && result.equals("Inf")) {
            result = "+" + result;
        }

        // Apply width formatting
        return SprintfPaddingHelper.applyWidth(result, width, flags);
    }

    /**
     * Format an integer value with specified base and flags.
     *
     * <p>This method handles the core integer formatting logic for all bases.
     * It applies precision (minimum digits), prefix (0 for octal, 0x for hex),
     * sign handling, and width formatting.
     *
     * @param value     The integer value to format
     * @param flags     Format flags (-, +, space, #, 0)
     * @param width     Field width
     * @param precision Precision (minimum digits)
     * @param base      Numeric base (8, 10, 16)
     * @param usePrefix Whether to use prefix (0 for octal, 0x for hex)
     * @return The formatted string
     */
    public String formatInteger(long value, String flags, int width, int precision,
                               int base, boolean usePrefix) {
        String result;
        boolean negative = value < 0 && base == 10;
        long absValue = negative ? -value : value;

        // For non-decimal bases, treat as unsigned
        if (base != 10 && value < 0) {
            // Convert to unsigned representation
            result = switch (base) {
                case 8 -> Long.toOctalString(value);
                case 16 -> Long.toHexString(value);
                default -> Long.toString(absValue, base);
            };
        } else {
            // Convert to string in the specified base
            result = switch (base) {
                case 8 -> Long.toOctalString(absValue);
                case 16 -> Long.toHexString(absValue);
                default -> Long.toString(absValue);
            };
        }

        // Apply precision (zero-padding for minimum digits)
        if (precision >= 0) {
            // Special case: precision 0 with value 0 produces empty string
            if (precision == 0 && value == 0) {
                result = "";
                // But # flag with octal still shows "0"
                if (usePrefix && base == 8) {
                    result = "0";
                }
            } else if (result.length() < precision) {
                result = SprintfPaddingHelper.padLeft(result, precision, '0');
            }
        }

        // Add prefix if needed (only for non-zero values)
        if (usePrefix && value != 0 && !result.isEmpty()) {
            if (base == 8 && !result.startsWith("0")) {
                result = "0" + result;
            } else if (base == 16) {
                result = "0x" + result;
            }
        }

        // Add sign only for base 10
        if (base == 10) {
            if (negative) {
                result = "-" + result;
            } else if (flags.contains("+")) {
                result = "+" + result;
            } else if (flags.contains(" ")) {
                result = " " + result;
            }
        }

        // Apply width with appropriate padding
        // IMPORTANT: If precision was specified, remove '0' flag
        String widthFlags = flags;
        if (precision >= 0 && widthFlags.contains("0")) {
            widthFlags = widthFlags.replace("0", "");
        }
        return SprintfPaddingHelper.applyWidth(result, width, widthFlags);
    }

    /**
     * Format an unsigned integer value.
     *
     * <p>Treats the value as unsigned, using Long.toUnsignedString for conversion.
     * This is used for %u and %U format specifiers.
     *
     * @param value The value to format as unsigned
     * @param flags Format flags
     * @param width Field width
     * @param precision Minimum digits
     * @return The formatted unsigned integer string
     */
    public String formatUnsigned(RuntimeScalar value, String flags, int width,
                                 int precision) {
        long longValue = value.getLong();
        String result = Long.toUnsignedString(longValue);

        // Apply precision (zero-padding)
        if (precision >= 0) {
            if (precision == 0 && longValue == 0) {
                result = "";
            } else if (result.length() < precision) {
                result = SprintfPaddingHelper.padLeft(result, precision, '0');
            }
        }

        // Apply width
        return SprintfPaddingHelper.applyWidth(result, width, flags);
    }

    /**
     * Format an octal value.
     *
     * <p>Formats the value in base 8. The + and space flags are removed
     * as they don't apply to octal formatting.
     *
     * @param value The value to format as octal
     * @param flags Format flags (+ and space are ignored)
     * @param width Field width
     * @param precision Minimum digits
     * @return The formatted octal string
     */
    public String formatOctal(long value, String flags, int width, int precision) {
        // Remove + and space flags for octal (they don't apply)
        String cleanFlags = flags.replace("+", "").replace(" ", "");
        return formatInteger(value, cleanFlags, width, precision, 8, flags.contains("#"));
    }

    /**
     * Format a hexadecimal value.
     *
     * <p>Formats the value in base 16. The + and space flags are removed
     * as they don't apply to hexadecimal formatting. The uppercase parameter
     * determines whether to use uppercase letters (A-F) or lowercase (a-f).
     *
     * @param value The value to format as hexadecimal
     * @param flags Format flags (+ and space are ignored)
     * @param width Field width
     * @param precision Minimum digits
     * @param uppercase Whether to use uppercase letters
     * @return The formatted hexadecimal string
     */
    public String formatHex(long value, String flags, int width, int precision, boolean uppercase) {
        // Remove + and space flags for hex
        String cleanFlags = flags.replace("+", "").replace(" ", "");
        String result = formatInteger(value, cleanFlags, width, precision, 16, flags.contains("#"));

        if (uppercase) {
            // Convert to uppercase, including the 0x prefix if present
            result = result.toUpperCase();
        }

        return result;
    }

    /**
     * Format a binary value.
     *
     * <p>Formats the value in base 2. The # flag adds a 0b or 0B prefix.
     *
     * @param value The value to format as binary
     * @param flags Format flags
     * @param width Field width
     * @param precision Minimum digits
     * @param conversion 'b' for lowercase prefix, 'B' for uppercase
     * @return The formatted binary string
     */
    public String formatBinary(long value, String flags, int width, int precision, char conversion) {
        String result;

        // For binary format, treat as unsigned
        if (value < 0) {
            result = Long.toBinaryString(value);
        } else {
            result = Long.toBinaryString(value);
        }

        // Apply precision (zero-padding)
        if (precision >= 0) {
            if (precision == 0 && value == 0) {
                result = "";
                // Note: # flag with binary does NOT show "0" (unlike octal)
            } else if (result.length() < precision) {
                result = SprintfPaddingHelper.padLeft(result, precision, '0');
            }
        }

        // Add prefix if needed
        if (flags.contains("#") && value != 0 && !result.isEmpty()) {
            String prefix = (conversion == 'B') ? "0B" : "0b";
            result = prefix + result;
        }

        // Apply width
        return SprintfPaddingHelper.applyWidth(result, width, flags);
    }

    /**
     * Format a floating-point value.
     *
     * <p>Handles all floating-point format conversions including:
     * <ul>
     *   <li>%f, %F - Fixed-point notation</li>
     *   <li>%e, %E - Exponential notation</li>
     *   <li>%g, %G - General format (chooses between f and e)</li>
     *   <li>%a, %A - Hexadecimal floating-point</li>
     * </ul>
     *
     * @param value The floating-point value to format
     * @param flags Format flags
     * @param width Field width
     * @param precision Decimal places (default 6 if not specified)
     * @param conversion The conversion character
     * @return The formatted floating-point string
     */
    public String formatFloatingPoint(double value, String flags, int width,
                                     int precision, char conversion) {
        if (precision < 0) {
            precision = 6;  // Default precision for floating point
        }

        // Handle special case of -0 flag combination which is invalid in Java
        String cleanFlags = flags.replace("-0", "-").replace("0-", "-");
        if (cleanFlags.contains("-") && cleanFlags.contains("0")) {
            cleanFlags = cleanFlags.replace("0", "");
        }

        // Special handling for %g to remove trailing zeros
        if ((conversion == 'g' || conversion == 'G')) {
            return formatGFloatingPoint(value, cleanFlags, width, precision, conversion);
        }

        // Build format string for String.format
        StringBuilder format = new StringBuilder("%");
        if (cleanFlags.contains("-")) format.append("-");
        if (cleanFlags.contains("+")) format.append("+");
        if (cleanFlags.contains(" ")) format.append(" ");
        if (cleanFlags.contains("0")) format.append("0");
        if (cleanFlags.contains("#")) format.append("#");

        if (width > 0) format.append(width);
        format.append(".").append(precision).append(conversion);

        String result = String.format(format.toString(), value);
        // Perl uses 'Inf' instead of Java's 'Infinity'
        result = result.replace("Infinity", "Inf");
        return result;
    }

    /**
     * Special formatting for %g and %G conversions.
     *
     * <p>The %g format chooses between fixed and exponential notation based on
     * the magnitude and precision. It also removes trailing zeros unless the
     * # flag is specified. With the # flag, trailing zeros are kept and a
     * decimal point is always included.
     *
     * @param value The value to format
     * @param flags Format flags (# flag handled specially)
     * @param width Field width
     * @param precision Significant digits
     * @param conversion 'g' or 'G'
     * @return The formatted string
     */
    private String formatGFloatingPoint(double value, String flags, int width,
                                      int precision, char conversion) {
        boolean useAlternateForm = flags.contains("#");
        String cleanFlags = flags.replace("#", ""); // Remove # flag for Java's formatter

        // Build the format string
        StringBuilder format = new StringBuilder("%");
        if (cleanFlags.contains("-")) format.append("-");
        if (cleanFlags.contains("+")) format.append("+");
        if (cleanFlags.contains(" ")) format.append(" ");
        if (cleanFlags.contains("0")) format.append("0");

        if (width > 0) format.append(width);
        format.append(".").append(precision).append(conversion);

        String result = String.format(format.toString(), value);
        result = result.replace("Infinity", "Inf");

        // Handle the # flag behavior for %g
        if (useAlternateForm) {
            // With #, we keep trailing zeros and ensure decimal point
            result = ensureAlternateForm(result, precision);
        } else {
            // Without # flag, remove trailing zeros for %g (normal behavior)
            result = removeTrailingZeros(result);
        }

        return result;
    }

    /**
     * Ensure alternate form formatting for %g with # flag.
     *
     * <p>This method ensures that:
     * <ul>
     *   <li>A decimal point is always present</li>
     *   <li>Trailing zeros are added to match the precision</li>
     * </ul>
     *
     * @param result The initial formatted string
     * @param precision The desired precision
     * @return The string with alternate form applied
     */
    private String ensureAlternateForm(String result, int precision) {
        // Check if result has 'e' or 'E' (exponential notation)
        int eIndex = result.indexOf('e');
        if (eIndex == -1) eIndex = result.indexOf('E');

        if (eIndex != -1) {
            // Exponential notation - ensure decimal point before 'e'
            String mantissa = result.substring(0, eIndex);
            String exponent = result.substring(eIndex);

            if (!mantissa.contains(".")) {
                // Add decimal point and zeros
                mantissa += ".";
                // Add zeros to match precision
                int digitCount = mantissa.replaceAll("[^0-9]", "").length();
                if (digitCount < precision) {
                    mantissa += "0".repeat(precision - digitCount);
                }
            } else {
                // Ensure we have enough decimal places
                int totalDigits = mantissa.replaceAll("[^0-9]", "").length();
                if (totalDigits < precision) {
                    mantissa += "0".repeat(precision - totalDigits);
                }
            }
            result = mantissa + exponent;
        } else {
            // Regular notation
            if (!result.contains(".")) {
                // Add decimal point
                result += ".";
            }

            // Count significant digits
            String cleanResult = result.replaceAll("[^0-9.]", ""); // Remove sign
            int significantDigits = cleanResult.replace(".", "").length();

            // Add trailing zeros to reach precision
            if (significantDigits < precision) {
                result += "0".repeat(precision - significantDigits);
            }
        }

        return result;
    }

    /**
     * Remove trailing zeros from a formatted floating-point number.
     *
     * <p>This is the normal behavior for %g format without the # flag.
     * Trailing zeros after the decimal point are removed, and the decimal
     * point itself is removed if no fractional part remains.
     *
     * @param result The formatted string
     * @return The string with trailing zeros removed
     */
    private String removeTrailingZeros(String result) {
        if (!result.contains("e") && !result.contains("E")) {
            // Remove trailing zeros after decimal point
            result = result.replaceAll("(\\.\\d*?)0+$", "$1");
            // Remove trailing decimal point if no fractional part remains
            result = result.replaceAll("\\.$", "");
        }
        return result;
    }
}

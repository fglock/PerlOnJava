package org.perlonjava.operators;

import org.perlonjava.runtime.*;

/**
 * Main formatter for sprintf operations.
 *
 * This class serves as the primary dispatcher for formatting values according to
 * sprintf format specifiers. It delegates numeric formatting to SprintfNumericFormatter
 * and vector formatting to SprintfVectorFormatter.
 *
 * <p>Handles the following format conversions directly:
 * <ul>
 *   <li>%s - String formatting</li>
 *   <li>%c - Character formatting</li>
 *   <li>%p - Pointer formatting</li>
 *   <li>%n - Position specifier (throws exception)</li>
 * </ul>
 *
 * @see SprintfNumericFormatter for numeric format conversions
 * @see SprintfVectorFormatter for vector format conversions
 */
public class SprintfValueFormatter {

    /** Formatter instance for numeric conversions */
    private final SprintfNumericFormatter numericFormatter = new SprintfNumericFormatter();

    /** Formatter instance for vector conversions */
    private final SprintfVectorFormatter vectorFormatter = new SprintfVectorFormatter();

    /**
     * Format a value according to the given format specifier.
     *
     * <p>This method examines the conversion character and delegates to the appropriate
     * formatter. For vector formats (when the 'v' flag is set), it uses the vector
     * formatter. Numeric conversions are handled by the numeric formatter, while
     * string, character, and pointer conversions are handled directly.
     *
     * @param value      The value to format
     * @param flags      Format flags (-, +, space, #, 0)
     * @param width      Field width (0 if not specified)
     * @param precision  Precision (-1 if not specified)
     * @param conversion Conversion character (d, s, f, etc.)
     * @return The formatted string
     * @throws PerlCompilerException if %n specifier is used
     */
    public String formatValue(RuntimeScalar value, String flags, int width,
                              int precision, char conversion) {
        // Check for special floating-point values first
        double doubleValue = value.getDouble();
        if (Double.isInfinite(doubleValue) || Double.isNaN(doubleValue)) {
            return numericFormatter.formatSpecialValue(doubleValue, flags, width, conversion);
        }

        // Dispatch to appropriate formatter based on conversion type
        return switch (conversion) {
            // Numeric conversions - delegate to numeric formatter
            case 'd', 'i' -> numericFormatter.formatInteger(value.getLong(), flags, width, precision, 10, false);
            case 'u' -> numericFormatter.formatUnsigned(value, flags, width, precision);
            case 'o' -> numericFormatter.formatOctal(value.getLong(), flags, width, precision);
            case 'x' -> numericFormatter.formatHex(value.getLong(), flags, width, precision, false);
            case 'X' -> numericFormatter.formatHex(value.getLong(), flags, width, precision, true);
            case 'b', 'B' -> numericFormatter.formatBinary(value.getLong(), flags, width, precision, conversion);
            case 'e', 'E', 'g', 'G', 'a', 'A' ->
                    numericFormatter.formatFloatingPoint(value.getDouble(), flags, width, precision, conversion);
            case 'f', 'F' -> numericFormatter.formatFloatingPoint(value.getDouble(), flags, width, precision, 'f');

            // String and character conversions - handle directly
            case 'c' -> formatCharacter(value, flags, width);
            case 's' -> formatString(value.toString(), flags, width, precision);
            case 'p' -> formatPointer(value);
            case 'n' -> throw new PerlCompilerException("%n specifier not supported");

            // Uppercase variants (synonyms)
            case 'D' -> numericFormatter.formatInteger(value.getLong(), flags, width, precision, 10, false);
            case 'O' -> numericFormatter.formatOctal(value.getLong(), flags, width, precision);
            case 'U' -> numericFormatter.formatUnsigned(value, flags, width, precision);

            default -> {
                // For any other character, it's an invalid conversion
                // This includes digits (0-9) and other invalid characters
                // Return empty string to match Perl behavior for invalid formats
                yield "";
            }
        };
    }

    /**
     * Format a vector string (used with %v flag).
     *
     * <p>Vector formatting treats the input as a sequence of values (bytes or numbers)
     * and formats each one according to the conversion specifier, joining them
     * with dots. This method delegates to the SprintfVectorFormatter.
     *
     * @param value      The value to format as a vector
     * @param flags      Format flags
     * @param width      Field width
     * @param precision  Precision for each vector element
     * @param conversionChar Conversion character
     * @return The formatted vector string
     */
    public String formatVectorString(RuntimeScalar value, String flags, int width,
                                     int precision, char conversionChar) {
        return vectorFormatter.formatVectorString(value, flags, width, precision, conversionChar);
    }

    /**
     * Format a character value.
     *
     * <p>Converts the numeric value to a character and applies width formatting.
     * For %c with zero flag, pads with '0' characters instead of spaces.
     *
     * @param value The value to convert to character
     * @param flags Format flags
     * @param width Field width
     * @return The formatted character string
     */
    private String formatCharacter(RuntimeScalar value, String flags, int width) {
        long longValue = value.getLong();
        char c = (char) longValue;
        String result = String.valueOf(c);

        // Apply width - for %c, zero padding means padding with '0' characters
        if (width > 0) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && !leftAlign;

            if (leftAlign) {
                result = String.format("%-" + width + "s", result);
            } else if (zeroPad) {
                // For %c with zero flag, pad with '0' characters (not space characters)
                result = SprintfPaddingHelper.padLeft(result, width, '0');
            } else {
                result = String.format("%" + width + "s", result);
            }
        }

        return result;
    }

    /**
     * Format a string value.
     *
     * <p>Applies precision (truncation) and width formatting to the string.
     * Precision specifies the maximum number of characters to include.
     *
     * @param value The string value to format
     * @param flags Format flags
     * @param width Field width
     * @param precision Maximum characters to include (-1 for no limit)
     * @return The formatted string
     */
    private String formatString(String value, String flags, int width, int precision) {
        // Apply precision (truncate string)
        if (precision >= 0 && value.length() > precision) {
            value = value.substring(0, precision);
        }

        // Apply width
        return SprintfPaddingHelper.applyWidth(value, width, flags);
    }

    /**
     * Format a pointer value (%p).
     *
     * <p>In Perl, %p formats the value as a hexadecimal number without
     * any prefix or special formatting.
     *
     * @param value The value to format as a pointer
     * @return The hexadecimal representation
     */
    private String formatPointer(RuntimeScalar value) {
        return String.format("%x", value.getLong());
    }
}

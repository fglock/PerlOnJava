package org.perlonjava.frontend.astnode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Represents a numeric format field in Perl format templates.
 * Numeric fields format numeric values with specific padding and decimal places:
 * - @### : Integer with padding (right-justified)
 * - @##.## : Decimal with specific decimal places
 * - @###.### : Mixed integer and decimal formatting
 */
public class NumericFormatField extends FormatField {
    /**
     * Number of decimal places (0 for integer fields)
     */
    public final int decimalPlaces;

    /**
     * Number of integer digits
     */
    public final int integerDigits;

    /**
     * Whether this field has decimal places
     */
    public final boolean hasDecimal;

    /** Whether the integer portion uses Perl's leading-zero picture glyph. */
    public final boolean zeroPad;

    /**
     * Constructor for NumericFormatField.
     *
     * @param width          The total width of the field
     * @param startPosition  The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular field (@)
     * @param integerDigits  Number of integer digits
     * @param decimalPlaces  Number of decimal places (0 for integer fields)
     */
    public NumericFormatField(int width, int startPosition, boolean isSpecialField,
                              int integerDigits, int decimalPlaces) {
        this(width, startPosition, isSpecialField, integerDigits, decimalPlaces, false);
    }

    public NumericFormatField(int width, int startPosition, boolean isSpecialField,
                              int integerDigits, int decimalPlaces, boolean zeroPad) {
        this(width, startPosition, isSpecialField, integerDigits, decimalPlaces,
                zeroPad, decimalPlaces > 0);
    }

    public NumericFormatField(int width, int startPosition, boolean isSpecialField,
                              int integerDigits, int decimalPlaces, boolean zeroPad,
                              boolean hasDecimal) {
        super(width, startPosition, isSpecialField);
        this.integerDigits = integerDigits;
        this.decimalPlaces = decimalPlaces;
        this.hasDecimal = hasDecimal;
        this.zeroPad = zeroPad;
    }

    /**
     * Format a value according to this numeric field's specifications.
     *
     * @param value The value to format
     * @return The formatted string
     */
    @Override
    public String formatValue(Object value) {
        if (value == null) {
            return " ".repeat(width);
        }

        // Convert value to number
        double numValue;
        try {
            if (value instanceof Number) {
                numValue = ((Number) value).doubleValue();
            } else {
                String strValue = value.toString().trim();
                if (strValue.isEmpty()) {
                    return " ".repeat(width);
                }
                numValue = Double.parseDouble(strValue);
            }
        } catch (NumberFormatException e) {
            // If not a valid number, return spaces
            return " ".repeat(width);
        }

        // Perl's picture width includes the @/^ sigil.  A negative sign uses
        // one of those positions; Java DecimalFormat instead treats its zero
        // pattern as a digit minimum and overflows pictures such as @0##.
        BigDecimal rounded = BigDecimal.valueOf(numValue)
                .setScale(decimalPlaces, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        BigDecimal absolute = rounded.abs();
        String plain = absolute.setScale(decimalPlaces, RoundingMode.UNNECESSARY).toPlainString();
        int dot = plain.indexOf('.');
        String integerPart = dot >= 0 ? plain.substring(0, dot) : plain;
        String fractionalPart = dot >= 0 ? plain.substring(dot + 1) : "";
        int signWidth = negative ? 1 : 0;
        int integerWidth = width - signWidth - (hasDecimal ? decimalPlaces + 1 : 0);
        if (integerPart.length() > integerWidth || integerWidth < 1) {
            return "#".repeat(width);
        }
        if (zeroPad) {
            integerPart = "0".repeat(integerWidth - integerPart.length()) + integerPart;
        }
        String formatted = (negative ? "-" : "") + integerPart
                + (hasDecimal ? "." + fractionalPart : "");
        if (formatted.length() > width) {
            return "#".repeat(width);
        }
        return " ".repeat(width - formatted.length()) + formatted;
    }

    @Override
    public String toString() {
        return "NumericFormatField{" +
                "width=" + width +
                ", startPosition=" + startPosition +
                ", integerDigits=" + integerDigits +
                ", decimalPlaces=" + decimalPlaces +
                ", hasDecimal=" + hasDecimal +
                ", isSpecialField=" + isSpecialField +
                '}';
    }
}

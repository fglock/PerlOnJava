package org.perlonjava.astnode;

import java.text.DecimalFormat;
import java.text.NumberFormat;

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

    /**
     * Constructor for NumericFormatField.
     *
     * @param width The total width of the field
     * @param startPosition The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular field (@)
     * @param integerDigits Number of integer digits
     * @param decimalPlaces Number of decimal places (0 for integer fields)
     */
    public NumericFormatField(int width, int startPosition, boolean isSpecialField, 
                             int integerDigits, int decimalPlaces) {
        super(width, startPosition, isSpecialField);
        this.integerDigits = integerDigits;
        this.decimalPlaces = decimalPlaces;
        this.hasDecimal = decimalPlaces > 0;
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
        
        // Create format pattern
        StringBuilder pattern = new StringBuilder();
        
        // Add integer part padding
        for (int i = 0; i < integerDigits; i++) {
            pattern.append("#");
        }
        
        // Add decimal part if needed
        if (hasDecimal && decimalPlaces > 0) {
            pattern.append(".");
            for (int i = 0; i < decimalPlaces; i++) {
                pattern.append("0");
            }
        }
        
        // Format the number
        DecimalFormat formatter = new DecimalFormat(pattern.toString());
        String formatted = formatter.format(numValue);
        
        // Right-justify within the field width
        if (formatted.length() > width) {
            // Truncate if too long (show asterisks to indicate overflow)
            return "*".repeat(width);
        } else if (formatted.length() < width) {
            // Pad with spaces on the left (right-justify)
            int padding = width - formatted.length();
            return " ".repeat(padding) + formatted;
        } else {
            return formatted;
        }
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

package org.perlonjava.astnode;

/**
 * Represents a text format field in Perl format templates.
 * Text fields format string values with specific justification:
 * - @<<< : Left-justified
 * - @>>> : Right-justified  
 * - @||| : Center-justified
 */
public class TextFormatField extends FormatField {
    /**
     * Justification type for the text field
     */
    public enum Justification {
        LEFT,    // @<<<
        RIGHT,   // @>>>
        CENTER   // @|||
    }
    
    /**
     * The justification for this text field
     */
    public final Justification justification;

    /**
     * Constructor for TextFormatField.
     *
     * @param width The width of the field
     * @param startPosition The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular field (@)
     * @param justification The text justification
     */
    public TextFormatField(int width, int startPosition, boolean isSpecialField, Justification justification) {
        super(width, startPosition, isSpecialField);
        this.justification = justification;
    }

    /**
     * Format a value according to this text field's specifications.
     *
     * @param value The value to format
     * @return The formatted string
     */
    @Override
    public String formatValue(Object value) {
        String text = value != null ? value.toString() : "";
        
        // Truncate if too long
        if (text.length() > width) {
            text = text.substring(0, width);
        }
        
        // Apply justification
        return switch (justification) {
            case LEFT -> String.format("%-" + width + "s", text);
            case RIGHT -> String.format("%" + width + "s", text);
            case CENTER -> {
                int padding = width - text.length();
                int leftPad = padding / 2;
                int rightPad = padding - leftPad;
                yield " ".repeat(leftPad) + text + " ".repeat(rightPad);
            }
        };
    }

    @Override
    public String toString() {
        return "TextFormatField{" +
                "width=" + width +
                ", startPosition=" + startPosition +
                ", justification=" + justification +
                ", isSpecialField=" + isSpecialField +
                '}';
    }
}

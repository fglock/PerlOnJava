package org.perlonjava.frontend.astnode;

/**
 * Represents a text format field in Perl format templates.
 * Text fields format string values with specific justification:
 * - @<<< : Left-justified
 * - @>>> : Right-justified
 * - @||| : Center-justified
 */
public class TextFormatField extends FormatField {
    /**
     * The justification for this text field
     */
    public final Justification justification;

    /**
     * Constructor for TextFormatField.
     *
     * @param width          The width of the field
     * @param startPosition  The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular field (@)
     * @param justification  The text justification
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

        // Ordinary text pictures render one physical record.  Newlines are
        // separators in the source value, rather than characters to embed in
        // the rendered field (multiline @* and ^* fields handle those values
        // separately).
        int newline = text.indexOf('\n');
        int carriageReturn = text.indexOf('\r');
        int lineEnd = newline >= 0 && carriageReturn >= 0 ? Math.min(newline, carriageReturn)
                : Math.max(newline, carriageReturn);
        if (lineEnd >= 0) {
            text = text.substring(0, lineEnd);
        }

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

    /**
     * Justification type for the text field
     */
    public enum Justification {
        LEFT,    // @<<<
        RIGHT,   // @>>>
        CENTER   // @|||
    }
}

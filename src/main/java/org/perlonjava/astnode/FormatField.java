package org.perlonjava.astnode;

/**
 * Base class for format field definitions in Perl format templates.
 * Format fields define how values should be formatted and positioned in output lines.
 * <p>
 * Field types include:
 * - Text fields: @<<< (left), @>>> (right), @||| (center)
 * - Numeric fields: @### (numeric), @##.## (decimal)
 * - Special fields: @* (multiline), ^* (fill mode)
 */
public abstract class FormatField {
    /**
     * The width of the field in characters
     */
    public final int width;

    /**
     * The starting position of the field in the line
     */
    public final int startPosition;

    /**
     * Whether this is a regular field (@) or special field (^)
     */
    public final boolean isSpecialField;

    /**
     * Constructor for FormatField.
     *
     * @param width          The width of the field
     * @param startPosition  The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular field (@)
     */
    public FormatField(int width, int startPosition, boolean isSpecialField) {
        this.width = width;
        this.startPosition = startPosition;
        this.isSpecialField = isSpecialField;
    }

    /**
     * Abstract method to format a value according to this field's specifications.
     *
     * @param value The value to format
     * @return The formatted string
     */
    public abstract String formatValue(Object value);

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "width=" + width +
                ", startPosition=" + startPosition +
                ", isSpecialField=" + isSpecialField +
                '}';
    }
}

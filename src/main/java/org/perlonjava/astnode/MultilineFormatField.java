package org.perlonjava.astnode;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a multiline format field in Perl format templates.
 * Multiline fields handle text that spans multiple lines:
 * - @* : Multiline field (consumes entire value)
 * - ^* : Fill mode field (consumes words until line is full, continues on next format execution)
 */
public class MultilineFormatField extends FormatField {
    /**
     * Type of multiline field
     */
    public enum MultilineType {
        CONSUME_ALL,  // @* - consume entire value
        FILL_MODE     // ^* - fill mode, consume words until full
    }
    
    /**
     * The type of multiline field
     */
    public final MultilineType multilineType;

    /**
     * Constructor for MultilineFormatField.
     *
     * @param width The width of the field (usually 1 for * fields)
     * @param startPosition The starting position in the line
     * @param isSpecialField Whether this is a special field (^) or regular field (@)
     * @param multilineType The type of multiline behavior
     */
    public MultilineFormatField(int width, int startPosition, boolean isSpecialField, MultilineType multilineType) {
        super(width, startPosition, isSpecialField);
        this.multilineType = multilineType;
    }

    /**
     * Format a value according to this multiline field's specifications.
     * Note: Multiline fields require special handling during format execution
     * as they may consume partial values and affect subsequent format calls.
     *
     * @param value The value to format
     * @return The formatted string for this line
     */
    @Override
    public String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        
        String text = value.toString();
        
        switch (multilineType) {
            case CONSUME_ALL:
                // @* consumes the entire value
                return text;
                
            case FILL_MODE:
                // ^* fills the available space with words
                // This is a simplified implementation - full implementation
                // requires tracking remaining text across format executions
                return fillWords(text, width);
                
            default:
                return text;
        }
    }
    
    /**
     * Fill words into the available space.
     * This is a simplified version - the full implementation would need
     * to track state across multiple format executions.
     * 
     * @param text The text to fill from
     * @param availableWidth The available width
     * @return The filled text for this line
     */
    private String fillWords(String text, int availableWidth) {
        if (text.isEmpty()) {
            return "";
        }
        
        String[] words = text.split("\\s+");
        StringBuilder result = new StringBuilder();
        
        for (String word : words) {
            if (result.length() == 0) {
                // First word
                if (word.length() <= availableWidth) {
                    result.append(word);
                } else {
                    // Word too long, truncate
                    result.append(word, 0, availableWidth);
                    break;
                }
            } else {
                // Check if we can add another word
                int neededSpace = result.length() + 1 + word.length();
                if (neededSpace <= availableWidth) {
                    result.append(" ").append(word);
                } else {
                    // Can't fit more words
                    break;
                }
            }
        }
        
        return result.toString();
    }
    
    /**
     * Check if this field has consumed all available text.
     * This would be used during format execution to determine
     * if more format calls are needed.
     * 
     * @param originalText The original text
     * @param consumedText The text that was consumed
     * @return true if all text has been consumed
     */
    public boolean hasConsumedAll(String originalText, String consumedText) {
        if (multilineType == MultilineType.CONSUME_ALL) {
            return true; // @* always consumes everything
        } else {
            // ^* may have remaining text
            return consumedText.length() >= originalText.length();
        }
    }

    @Override
    public String toString() {
        return "MultilineFormatField{" +
                "width=" + width +
                ", startPosition=" + startPosition +
                ", multilineType=" + multilineType +
                ", isSpecialField=" + isSpecialField +
                '}';
    }
}

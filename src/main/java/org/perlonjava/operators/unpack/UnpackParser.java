package org.perlonjava.operators.unpack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.operators.pack.PackParser;
import org.perlonjava.operators.FormatModifierValidator;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser utilities for unpack template processing.
 * This class contains methods for parsing various elements of unpack templates.
 */
public class UnpackParser {

    /**
     * Skip comment in template starting from current position
     * Comments start with '#' and continue to end of line or end of template
     * 
     * @param template The template string
     * @param position Current position in template
     * @return New position after skipping comment
     */
    public static int skipComment(String template, int position) {
        // Skip to end of line or end of template
        while (position + 1 < template.length() && template.charAt(position + 1) != '\n') {
            position++;
        }
        return position;
    }

    /**
     * Parse modifiers for unpack format character and validate them
     * 
     * @param template The template string
     * @param position Current position in template (should point to format character)
     * @return New position after parsing modifiers
     */
    public static int parseAndValidateModifiers(String template, int position) {
        char formatChar = template.charAt(position);
        List<Character> modifiers = new ArrayList<>();
        int i = position;
        
        // Parse modifiers that follow the format character
        while (i + 1 < template.length()) {
            char modifier = template.charAt(i + 1);
            if (modifier == '<' || modifier == '>' || modifier == '!') {
                modifiers.add(modifier);
                i++;
            } else {
                break;
            }
        }
        
        // Validate modifiers using centralized validator
        if (!modifiers.isEmpty()) {
            FormatModifierValidator.validateFormatModifiers(formatChar, modifiers, "unpack");
        }
        
        return i;
    }

    /**
     * Parse repeat count from template at given position
     * Handles numeric counts, star notation, and bracket notation
     * 
     * @param template The template string
     * @param position Current position in template
     * @return ParsedCount object with count, hasStar flag, and end position
     */
    public static ParsedCount parseRepeatCount(String template, int position) {
        int count = 1;
        boolean isStarCount = false;
        int i = position;

        // First, parse and validate any modifiers (<, >, !) after the format character
        // and detect modifiers present
        boolean hasShriek = false;
        boolean hasLittleEndian = false;
        boolean hasBigEndian = false;
        while (i + 1 < template.length()) {
            char modifier = template.charAt(i + 1);
            if (modifier == '<' || modifier == '>' || modifier == '!') {
                if (modifier == '!') {
                    hasShriek = true;
                } else if (modifier == '<') {
                    hasLittleEndian = true;
                } else if (modifier == '>') {
                    hasBigEndian = true;
                }
                i++;
            } else {
                break;
            }
        }
        
        // Validate modifiers using centralized validator
        char formatChar = template.charAt(position);
        List<Character> modifiers = new ArrayList<>();
        int tempI = position;
        while (tempI + 1 < template.length()) {
            char modifier = template.charAt(tempI + 1);
            if (modifier == '<' || modifier == '>' || modifier == '!') {
                modifiers.add(modifier);
                tempI++;
            } else {
                break;
            }
        }
        if (!modifiers.isEmpty()) {
            FormatModifierValidator.validateFormatModifiers(formatChar, modifiers, "unpack");
        }

        if (i + 1 < template.length()) {
            char nextChar = template.charAt(i + 1);
            if (nextChar == '*') {
                isStarCount = true;
                i++;
            } else if (nextChar == '[') {
                // Parse repeat count in brackets [n] or [template]
                int j = i + 2;
                int bracketDepth = 1;

                // Find the matching ']'
                while (j < template.length() && bracketDepth > 0) {
                    char ch = template.charAt(j);
                    if (ch == '[') bracketDepth++;
                    else if (ch == ']') bracketDepth--;
                    if (bracketDepth > 0) j++;
                }

                if (j >= template.length()) {
                    throw new PerlCompilerException("No group ending character ']' found in template");
                }

                String bracketContent = template.substring(i + 2, j).trim();

                // Check if it's a numeric count or a template
                if (bracketContent.matches("\\d+")) {
                    // Simple numeric count
                    count = Integer.parseInt(bracketContent);
                } else {
                    // Template-based count - calculate the packed size of the template
                    // DEBUG: Template-based repeat count [" + bracketContent + "] - using default count 1
                    // For now, just use count = 1 to avoid errors
                    count = 1;
                    // TODO: Implement pack size calculation for the template
                }

                i = j; // Position at ']'
            } else if (Character.isDigit(nextChar)) {
                int j = i + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                String countStr = template.substring(i + 1, j);
                count = Integer.parseInt(countStr);
                i = j - 1;
            }
        }

        return new ParsedCount(count, isStarCount, i, hasShriek, hasLittleEndian, hasBigEndian);
    }

    /**
     * Record to hold parsed count information
     */
    public record ParsedCount(int count, boolean isStarCount, int endPosition, boolean hasShriek, boolean hasLittleEndian, boolean hasBigEndian) {
    }
}

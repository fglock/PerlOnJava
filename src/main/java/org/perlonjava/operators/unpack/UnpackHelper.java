package org.perlonjava.operators.unpack;

import org.perlonjava.operators.Unpack;
import org.perlonjava.operators.UnpackState;
import org.perlonjava.operators.pack.PackHelper;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;
import java.util.Stack;

public class UnpackHelper {

    /**
     * Process a slash construct (e.g., "S/A*", "n/(...)")
     *
     * @return the new position after processing the construct
     */
    public static int processSlashConstruct(String template, int position, char numericFormat,
                                            UnpackState state, List<RuntimeBase> values,
                                            boolean startsWithU, java.util.Stack<Boolean> modeStack) {

        // DEBUG: Processing slash construct " + numericFormat + "/ at position " + position

        // Handle string formats as count formats for slash constructs
        if (numericFormat == 'A' || numericFormat == 'a' || numericFormat == 'Z') {
            // For string formats used as count, we need to parse the count from the template
            // position points to the format character (e.g., 'a' in "a3/A")
            // We need to extract the count (e.g., 3 from "a3")
            ParsedCount countInfo = parseRepeatCount(template, position + 1);
            int formatCount = countInfo.count;
            
            // For string formats used as count, read as string and convert to integer
            FormatHandler stringHandler = Unpack.getHandler(numericFormat, startsWithU);
            int initialSize = values.size();
            stringHandler.unpack(state, values, formatCount, countInfo.hasStar);  // Unpack with the parsed count

            // Determine the slash count. If there wasn't enough data to read the count,
            // Perl returns no values and does not throw; treat count as 0.
            int slashCount = 0;
            if (values.size() > initialSize) {
                // Get the count value from the string
                RuntimeBase lastValue = values.getLast();
                String countStr = lastValue.toString().trim();
                try {
                    slashCount = Integer.parseInt(countStr);
                } catch (NumberFormatException e) {
                    slashCount = 0; // If not a valid number, use 0
                }
                values.removeLast(); // Remove the count value
            }

            // Continue with the rest of the processing...
            // Find the slash position
            int slashPos = PackHelper.checkForSlashConstruct(template, position);
            if (slashPos == -1) {
                throw new PerlCompilerException("Internal error: slash construct detection failed");
            }

            // Move to after the '/'
            int i = slashPos + 1;

            // Skip whitespace after '/'
            while (i < template.length() && Character.isWhitespace(template.charAt(i))) {
                i++;
            }

            if (i >= template.length()) {
                throw new PerlCompilerException("Code missing after '/'");
            }

            char stringFormat = template.charAt(i);

            // Check if it's a group
            if (stringFormat == '(') {
                // Find the matching closing parenthesis
                int closePos = UnpackHelper.findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                }

                // Extract group content
                String groupContent = template.substring(i + 1, closePos);

                // Process the group slashCount times
                for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                    UnpackGroupProcessor.processGroupContent(groupContent, state, values, 1, startsWithU, modeStack);
                }

                return closePos;
            } else {
                // Any valid format can follow '/', not just string types
                FormatHandler formatHandler = Unpack.getHandler(stringFormat, startsWithU);
                if (formatHandler == null) {
                    // DEBUG: No handler found for format '" + stringFormat + "'
                    throw new PerlCompilerException("'/' must be followed by a valid format or group");
                }

                // Parse optional count/star after the format
                boolean hasStarAfterSlash = false;
                if (i + 1 < template.length() && template.charAt(i + 1) == '*') {
                    hasStarAfterSlash = true;
                    i++; // Move to the '*'
                }

                // Unpack the format with the count
                // Only unpack if slashCount > 0 (if count was 0 due to insufficient data, don't unpack)
                if (slashCount > 0 || hasStarAfterSlash) {
                    formatHandler.unpack(state, values, slashCount, hasStarAfterSlash);
                }

                return i;
            }
        } else {
            // Original numeric format processing
            // First, unpack the numeric format to get the count
            FormatHandler handler = Unpack.getHandler(numericFormat, startsWithU);
            int initialSize = values.size();
            handler.unpack(state, values, 1, false);  // Attempt to unpack just one for the count

            // Determine the slash count. If there wasn't enough data to read the count,
            // Perl returns no values and does not throw; treat count as 0.
            int slashCount = 0;
            if (values.size() > initialSize) {
                RuntimeBase lastValue = values.getLast();
                try {
                    slashCount = ((RuntimeScalar) lastValue).getInt();
                } catch (Exception ignored) {
                    slashCount = 0;
                }
                values.removeLast(); // Remove the count value
            }
            // DEBUG: Got slash count: " + slashCount

            // Find the slash position
            int slashPos = PackHelper.checkForSlashConstruct(template, position);
            if (slashPos == -1) {
                throw new PerlCompilerException("Internal error: slash construct detection failed");
            }

            // Move to after the '/'
            int i = slashPos + 1;

            // Skip whitespace after '/'
            while (i < template.length() && Character.isWhitespace(template.charAt(i))) {
                i++;
            }

            if (i >= template.length()) {
                throw new PerlCompilerException("Code missing after '/'");
            }

            char stringFormat = template.charAt(i);
            // DEBUG: String format after '/' is: '" + stringFormat + "'

            // Check if it's a group
            if (stringFormat == '(') {
                // Find the matching closing parenthesis
                int closePos = UnpackHelper.findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                }

                // Extract group content
                String groupContent = template.substring(i + 1, closePos);

                // Process the group slashCount times
                for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                    UnpackGroupProcessor.processGroupContent(groupContent, state, values, 1, startsWithU, modeStack);
                }

                return closePos;
            } else {
                // Any valid format can follow '/', not just string types
                FormatHandler formatHandler = Unpack.getHandler(stringFormat, startsWithU);
                if (formatHandler == null) {
                    // DEBUG: No handler found for format '" + stringFormat + "'
                    throw new PerlCompilerException("'/' must be followed by a valid format or group");
                }

                // Parse optional count/star after the format
                boolean hasStarAfterSlash = false;
                if (i + 1 < template.length() && template.charAt(i + 1) == '*') {
                    hasStarAfterSlash = true;
                    i++; // Move to the '*'
                }

                // Unpack the format with the count
                // Only unpack if slashCount > 0 (if count was 0 due to insufficient data, don't unpack)
                if (slashCount > 0 || hasStarAfterSlash) {
                    formatHandler.unpack(state, values, slashCount, hasStarAfterSlash);
                }
                return i;
            }
        }
    }

    /**
     * Parse repeat count from template at given position
     *
     * @return a ParsedCount object with count, hasStar flag, and end position
     */
    public static ParsedCount parseRepeatCount(String template, int position) {
        int count = 1;
        boolean hasStar = false;
        int i = position;

        // Skip any '!' modifiers first
        while (i < template.length() && template.charAt(i) == '!') {
            i++;
        }

        if (i < template.length()) {
            char nextChar = template.charAt(i);
            if (nextChar == '*') {
                hasStar = true;
                i++;
            } else if (nextChar == '[') {
                // Parse [n] style count
                int j = i + 1;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                if (j < template.length() && template.charAt(j) == ']') {
                    count = Integer.parseInt(template.substring(i + 1, j));
                    i = j + 1;
                }
            } else if (Character.isDigit(nextChar)) {
                int j = i;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                count = Integer.parseInt(template.substring(i, j));
                i = j;
            }
        }

        return new ParsedCount(count, hasStar, i);
    }

    public record ParsedCount(int count, boolean hasStar, int endPosition) {
    }

    /**
     * Calculate the remaining count for star notation based on format and state
     */
    public static int getRemainingCount(UnpackState state, char format, boolean startsWithU) {
        switch (format) {
            case 'C':
            case 'U':
                if (state.isCharacterMode() || (format == 'U' && startsWithU)) {
                    return state.remainingCodePoints();
                } else {
                    return state.remainingBytes();
                }
            case 'a':
            case 'A':
            case 'Z':
                return state.isCharacterMode() ? state.remainingCodePoints() : state.remainingBytes();
            case 'b':
            case 'B':
            case '%':
                return state.isCharacterMode() ? state.remainingCodePoints() * 8 : state.remainingBytes() * 8;
            case 'h':
            case 'H':
                return state.isCharacterMode() ? state.remainingCodePoints() * 2 : state.remainingBytes() * 2;
            default:
                FormatHandler handler = Unpack.handlers.get(format);
                if (handler != null) {
                    int size = handler.getFormatSize();
                    if (size > 0) {
                        return state.remainingBytes() / size;
                    }
                }
                return Integer.MAX_VALUE; // Let the handler decide
        }
    }

    /**
     * Find the matching closing parenthesis for a group
     */
    public static int findMatchingParen(String template, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < template.length(); i++) {
            if (template.charAt(i) == '(') depth++;
            else if (template.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Check if group content has conflicting endianness modifiers
     */
    public static boolean hasConflictingEndianness(String groupContent, char groupEndian) {
        // Check if the group content has endianness modifiers that conflict with the group's endianness
        for (int i = 0; i < groupContent.length(); i++) {
            char c = groupContent.charAt(i);
            if ((c == '<' && groupEndian == '>') || (c == '>' && groupEndian == '<')) {
                // Check if this is actually a modifier (follows a format that supports it)
                if (i > 0) {
                    char prevChar = groupContent.charAt(i - 1);
                    if ("sSiIlLqQjJfFdDpP".indexOf(prevChar) >= 0) {
                        return true;
                    }
                }
            }
            // Also check for nested groups with conflicting endianness
            if (c == '(') {
                int closePos = findMatchingParen(groupContent, i);
                if (closePos != -1 && closePos + 1 < groupContent.length()) {
                    char nestedEndian = groupContent.charAt(closePos + 1);
                    if ((nestedEndian == '<' && groupEndian == '>') ||
                            (nestedEndian == '>' && groupEndian == '<')) {
                        return true;
                    }
                }
                i = closePos; // Skip the nested group
            }
        }
        return false;
    }
}
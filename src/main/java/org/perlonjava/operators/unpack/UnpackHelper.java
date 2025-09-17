package org.perlonjava.operators.unpack;

import org.perlonjava.operators.Unpack;
import org.perlonjava.operators.UnpackState;
import org.perlonjava.operators.pack.PackHelper;
import org.perlonjava.runtime.*;
import java.util.List;

public class UnpackHelper {

    /**
     * Process a slash construct (e.g., "S/A*", "n/(...)")
     * @return the new position after processing the construct
     */
    public static int processSlashConstruct(String template, int position, char numericFormat,
                                           UnpackState state, List<RuntimeBase> values,
                                           boolean startsWithU, java.util.Stack<Boolean> modeStack) {

        System.err.println("DEBUG: Processing slash construct " + numericFormat + "/ at position " + position);

        // Handle string formats as count formats for slash constructs
        if (numericFormat == 'A' || numericFormat == 'a' || numericFormat == 'Z') {
            // For string formats used as count, read as string and convert to integer
            FormatHandler stringHandler = Unpack.getHandler(numericFormat, startsWithU);
            stringHandler.unpack(state, values, 1, false);  // Always unpack just one for the count

            // Get the count value from the string
            RuntimeBase lastValue = values.getLast();
            String countStr = ((RuntimeScalar) lastValue).toString().trim();
            int slashCount;
            try {
                slashCount = Integer.parseInt(countStr);
            } catch (NumberFormatException e) {
                slashCount = 0; // If not a valid number, use 0
            }
            values.removeLast(); // Remove the count value

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
                int closePos = Unpack.findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                }

                // Extract group content
                String groupContent = template.substring(i + 1, closePos);

                // Process the group slashCount times
                for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                    Groups.processGroup(groupContent, state, values, 1, startsWithU, modeStack);
                }

                return closePos;
            } else if (stringFormat == 'a' || stringFormat == 'A' || stringFormat == 'Z') {
                // Parse optional count/star after string format
                boolean hasStarAfterSlash = false;
                if (i + 1 < template.length() && template.charAt(i + 1) == '*') {
                    hasStarAfterSlash = true;
                    i++; // Move to the '*'
                }

                // Unpack the string with the count
                FormatHandler stringFormatHandler = Unpack.handlers.get(stringFormat);
                stringFormatHandler.unpack(state, values, slashCount, hasStarAfterSlash);

                return i;
            } else {
                throw new PerlCompilerException("'/' must be followed by a string type or group");
            }
        } else {
            // Original numeric format processing
            // First, unpack the numeric format to get the count
            FormatHandler handler = Unpack.getHandler(numericFormat, startsWithU);
            handler.unpack(state, values, 1, false);  // Always unpack just one for the count

            // Get the count value
            RuntimeBase lastValue = values.getLast();
            int slashCount = ((RuntimeScalar) lastValue).getInt();
            values.removeLast(); // Remove the count value

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
                int closePos = Unpack.findMatchingParen(template, i);
                if (closePos == -1) {
                    throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                }

                // Extract group content
                String groupContent = template.substring(i + 1, closePos);

                // Process the group slashCount times
                for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                    Groups.processGroup(groupContent, state, values, 1, startsWithU, modeStack);
                }

                return closePos;
            } else if (stringFormat == 'a' || stringFormat == 'A' || stringFormat == 'Z') {
                // Parse optional count/star after string format
                boolean hasStarAfterSlash = false;
                if (i + 1 < template.length() && template.charAt(i + 1) == '*') {
                    hasStarAfterSlash = true;
                    i++; // Move to the '*'
                }

                // Unpack the string with the count
                FormatHandler stringHandler = Unpack.handlers.get(stringFormat);
                stringHandler.unpack(state, values, slashCount, hasStarAfterSlash);

                return i;
            } else {
                throw new PerlCompilerException("'/' must be followed by a string type or group");
            }
        }
    }

    /**
     * Parse repeat count from template at given position
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

    public static class ParsedCount {
        public final int count;
        public final boolean hasStar;
        public final int endPosition;

        public ParsedCount(int count, boolean hasStar, int endPosition) {
            this.count = count;
            this.hasStar = hasStar;
            this.endPosition = endPosition;
        }
    }
}
package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.Unpack;
import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.operators.pack.GroupEndiannessHelper;
import org.perlonjava.runtime.operators.pack.PackHelper;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Unified processor for handling group constructs in unpack templates.
 * This class combines both group syntax parsing and group content processing
 * to provide a complete solution for Perl's parenthesized group functionality.
 *
 * <p>This class manages the parsing and processing of parenthesized groups in unpack templates,
 * including nested groups, repeat counts, and special modifiers.</p>
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Parse group syntax and extract group content</li>
 *   <li>Handle group repeat counts (numeric, *, and template-based)</li>
 *   <li>Process nested groups recursively</li>
 *   <li>Manage mode changes (C0/U0) within groups</li>
 *   <li>Handle slash constructs (N/X format) within groups</li>
 *   <li>Handle special positioning and counting operations</li>
 * </ul>
 */
public class UnpackGroupProcessor {

    /**
     * Parse and process a parenthesized group from the template.
     * This method handles the complete group syntax including parsing modifiers,
     * repeat counts, and delegating to recursive unpack calls.
     *
     * @param template       The full template string
     * @param position       Current position at the opening '('
     * @param state          The unpack state
     * @param values         List to append unpacked values to
     * @param startsWithU    Whether template starts with U
     * @param modeStack      Stack for tracking mode changes
     * @param unpackFunction Function to call for recursive unpacking
     * @return New position after processing the group
     */
    public static int parseGroupSyntax(String template, int position, UnpackState state,
                                       List<RuntimeBase> values, boolean startsWithU,
                                       Stack<Boolean> modeStack, UnpackFunction unpackFunction) {
        // Find matching closing parenthesis
        int closePos = UnpackHelper.findMatchingParen(template, position);
        if (closePos == -1) {
            throw new PerlCompilerException("unpack: unmatched parenthesis in template");
        }

        // Extract group content
        String groupContent = template.substring(position + 1, closePos);

        // Validate that group doesn't start with a count
        if (!groupContent.isEmpty()) {
            char firstChar = groupContent.charAt(0);
            if (firstChar == '*' || Character.isDigit(firstChar) || firstChar == '[') {
                throw new PerlCompilerException("()-group starts with a count in unpack");
            }
        }

        // Check for endianness modifier after the group
        char groupEndian = ' '; // default: no specific endianness
        int nextPos = closePos + 1;

        // Parse modifiers after ')'
        while (nextPos < template.length()) {
            char nextChar = template.charAt(nextPos);
            if (nextChar == '<' || nextChar == '>') {
                if (groupEndian == ' ') {
                    groupEndian = nextChar;
                }
                nextPos++;
            } else if (nextChar == '!') {
                nextPos++;
            } else {
                break;
            }
        }

        // Check for conflicting endianness within the group
        if (groupEndian != ' ' && UnpackHelper.hasConflictingEndianness(groupContent, groupEndian)) {
            throw new PerlCompilerException("Can't use '" + groupEndian + "' in a group with different byte-order in unpack");
        }

        // Apply group-level endianness to the content if specified
        if (groupEndian != ' ') {
            groupContent = GroupEndiannessHelper.applyGroupEndianness(groupContent, groupEndian);
        }

        // Check for repeat count after closing paren
        int groupRepeatCount = 1;

        if (nextPos < template.length()) {
            char nextChar = template.charAt(nextPos);
            if (nextChar == '*') {
                // Repeat until end of data
                groupRepeatCount = Integer.MAX_VALUE;
                nextPos++;
            } else if (nextChar == '[') {
                // Parse repeat count in brackets [n] or [template]
                int j = nextPos + 1;
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

                String bracketContent = template.substring(nextPos + 1, j);

                // Check if it's a numeric count or a template
                if (bracketContent.matches("\\d+")) {
                    // Simple numeric count
                    groupRepeatCount = Integer.parseInt(bracketContent);
                } else {
                    // Template-based count - calculate the packed size of the template
                    // DEBUG: Template-based repeat count [" + bracketContent + "] - not yet implemented
                    // For now, just use count = 1 to avoid errors
                    groupRepeatCount = 1;
                    // TODO: Implement pack size calculation for the template
                }

                nextPos = j + 1; // Move past ']'
            } else if (Character.isDigit(nextChar)) {
                // Parse numeric repeat count
                int j = nextPos;
                while (j < template.length() && Character.isDigit(template.charAt(j))) {
                    j++;
                }
                groupRepeatCount = Integer.parseInt(template.substring(nextPos, j));
                nextPos = j;
            }
        }

        // Apply group-level endianness to the content if specified
        String effectiveContent = groupContent;
        if (groupEndian != ' ') {
            effectiveContent = GroupEndiannessHelper.applyGroupEndianness(groupContent, groupEndian);
        }

        // Push current mode onto stack
        modeStack.push(state.isCharacterMode());

        // Process the group by calling unpack recursively for each repeat
        for (int rep = 0; rep < groupRepeatCount; rep++) {
            // Check if we have more data
            if (rep > 0 && state.remainingBytes() == 0 && !state.isCharacterMode()) {
                break;
            }
            if (rep > 0 && state.remainingCodePoints() == 0 && state.isCharacterMode()) {
                break;
            }

            // Save position before unpacking to detect infinite loops
            int positionBefore = state.getPosition();

            // Push group baseline for this repetition
            state.pushGroupBase();
            
            try {
                // Call unpack recursively with the group template
                RuntimeList groupResult = unpackFunction.unpack(effectiveContent, state, startsWithU, modeStack);

                // Add all unpacked values to the output
                values.addAll(groupResult.elements);
            } finally {
                // Always pop group baseline, even if an exception occurs
                state.popGroupBase();
            }

            // For * groups, stop if no progress was made (prevents infinite loops)
            if (groupRepeatCount == Integer.MAX_VALUE) {
                int positionAfter = state.getPosition();
                if (positionAfter == positionBefore) {
                    // No data consumed - stop to prevent infinite loop
                    break;
                }
            }
        }

        // Restore mode from stack
        if (!modeStack.isEmpty()) {
            boolean savedMode = modeStack.pop();
            if (savedMode) {
                state.switchToCharacterMode();
            } else {
                state.switchToByteMode();
            }
        }

        return nextPos - 1; // Return position to be incremented by main loop
    }

    /**
     * Process a slash construct with a group (e.g., "n/(a4)*")
     *
     * @param template    The template string
     * @param position    Current position at the opening '('
     * @param slashCount  The count from the numeric format before '/'
     * @param state       The unpack state
     * @param values      List to append values to
     * @param startsWithU Whether template starts with U
     * @param modeStack   Stack for mode tracking
     * @return New position after processing
     */
    public static int processSlashGroup(String template, int position, int slashCount,
                                        UnpackState state, List<RuntimeBase> values,
                                        boolean startsWithU, Stack<Boolean> modeStack) {
        // Find the matching closing parenthesis
        int closePos = UnpackHelper.findMatchingParen(template, position);
        if (closePos == -1) {
            throw new PerlCompilerException("unpack: unmatched parenthesis in template");
        }

        // Extract group content
        String groupContent = template.substring(position + 1, closePos);

        // Process the group slashCount times
        for (int slashRep = 0; slashRep < slashCount; slashRep++) {
            processGroupContent(groupContent, state, values, 1, startsWithU, modeStack);
        }

        return closePos;
    }

    /**
     * Process the content of a group template with the specified repeat count.
     *
     * <p>This method handles the core logic for processing Perl unpack groups, which are
     * template sections enclosed in parentheses that can be repeated. The method supports:</p>
     *
     * <ul>
     *   <li><strong>Repeat counts:</strong> Groups can be repeated a specific number of times
     *       or indefinitely with '*'</li>
     *   <li><strong>Nested groups:</strong> Groups can contain other groups, creating hierarchical
     *       template structures</li>
     *   <li><strong>Mode preservation:</strong> The method saves and restores the current unpacking
     *       mode (character vs byte) around group processing</li>
     *   <li><strong>Slash constructs:</strong> Handles patterns like "n/a*" where a numeric value
     *       determines the count for subsequent string operations</li>
     *   <li><strong>Position tracking:</strong> Monitors data consumption to handle '*' repeat
     *       counts and prevent infinite loops</li>
     * </ul>
     *
     * @param groupTemplate The template string for the group content (without outer parentheses)
     * @param state         The current unpack state containing data buffer and position information
     * @param values        The list to append unpacked values to
     * @param repeatCount   The number of times to repeat the group (Integer.MAX_VALUE for '*')
     * @param startsWithU   Whether the overall template starts with 'U' (affects default mode)
     * @param modeStack     Stack for tracking nested mode changes
     */
    public static void processGroupContent(String groupTemplate, UnpackState state, List<RuntimeBase> values,
                                           int repeatCount, boolean startsWithU, Stack<Boolean> modeStack) {
        // Save current mode
        boolean savedMode = state.isCharacterMode();

        // Track position history to detect cycles (for infinite loop prevention)
        List<Integer> positionHistory = new ArrayList<>();

        for (int rep = 0; rep < repeatCount; rep++) {
            // If we're at the end of data and not on first iteration, stop
            if (rep > 0 && state.remainingBytes() == 0) {
                break;
            }

            // Save position before processing group (for detecting progress with *)
            int positionBefore;
            if (state.isCharacterMode()) {
                positionBefore = state.getCurrentCodePointIndex();
            } else {
                positionBefore = state.getTotalLength() - state.remainingBytes();
            }

            // DEBUG: Group iteration " + rep + " starting at position " + positionBefore
            int valuesBeforeGroup = values.size();

            // Establish group-relative baseline for this repetition
            state.pushGroupBase();

            // Process the group content
            for (int j = 0; j < groupTemplate.length(); j++) {
                char format = groupTemplate.charAt(j);

                // Skip whitespace
                if (Character.isWhitespace(format)) {
                    continue;
                }

                // Handle commas (skip with warning)
                if (format == ',') {
                    // WARNING: Invalid type ',' in unpack
                    continue;
                }

                // Handle endianness modifiers that might appear after certain formats
                if ((format == '<' || format == '>') && j > 0) {
                    // This is likely a modifier for the previous format, skip it
                    continue;
                }

                // Handle '!' modifier that might appear after certain formats
                if (format == '!' && j > 0) {
                    // This is likely a modifier for the previous format, skip it
                    // Also skip any following digit or '*' (e.g., !2, !4, !8, !*)
                    if (j + 1 < groupTemplate.length()) {
                        char nextChar = groupTemplate.charAt(j + 1);
                        if (Character.isDigit(nextChar) || nextChar == '*') {
                            j++; // Skip the digit or '*' as well
                        }
                    }
                    continue;
                }

                // Handle nested groups
                if (format == '(') {
                    // Find the matching closing parenthesis within the group
                    int closePos = UnpackHelper.findMatchingParen(groupTemplate, j);
                    if (closePos == -1) {
                        throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                    }

                    // Extract nested group content
                    String nestedGroupContent = groupTemplate.substring(j + 1, closePos);

                    // Check for modifiers and repeat count after the nested group
                    int nestedNextPos = closePos + 1;
                    int nestedRepeatCount = 1;

                    // Parse modifiers after ')'
                    while (nestedNextPos < groupTemplate.length()) {
                        char nextChar = groupTemplate.charAt(nestedNextPos);
                        if (nextChar == '<' || nextChar == '>' || nextChar == '!') {
                            nestedNextPos++;
                        } else if (nextChar == '*') {
                            nestedRepeatCount = Integer.MAX_VALUE;
                            nestedNextPos++;
                            break;
                        } else if (Character.isDigit(nextChar)) {
                            int k = nestedNextPos;
                            while (k < groupTemplate.length() && Character.isDigit(groupTemplate.charAt(k))) {
                                k++;
                            }
                            nestedRepeatCount = Integer.parseInt(groupTemplate.substring(nestedNextPos, k));
                            nestedNextPos = k;
                            break;
                        } else {
                            break;
                        }
                    }

                    // Process the nested group
                    processGroupContent(nestedGroupContent, state, values, nestedRepeatCount, startsWithU, modeStack);

                    // Move past the nested group
                    j = nestedNextPos - 1; // -1 because the loop will increment j
                    continue;
                }

                // Check if this numeric format is part of a '/' construct
                if (PackHelper.isNumericFormat(format) || format == 'Z' || format == 'A' || format == 'a') {
                    int slashPos = PackHelper.checkForSlashConstruct(groupTemplate, j);
                    if (slashPos != -1) {
                        // First, unpack the numeric format to get the count
                        FormatHandler handler = Unpack.getHandler(format, startsWithU);
                        handler.unpack(state, values, 1, false);  // Always unpack just one for the count

                        // Get the count value
                        RuntimeBase lastValue = values.getLast();
                        int slashCount = ((RuntimeScalar) lastValue).getInt();
                        values.removeLast(); // Remove the count value

                        // Move to after the '/'
                        j = slashPos + 1;

                        // Skip whitespace after '/'
                        while (j < groupTemplate.length() && Character.isWhitespace(groupTemplate.charAt(j))) {
                            j++;
                        }

                        if (j >= groupTemplate.length()) {
                            throw new PerlCompilerException("Code missing after '/'");
                        }

                        char stringFormat = groupTemplate.charAt(j);

                        if (stringFormat == '(') {
                            int closePos = UnpackHelper.findMatchingParen(groupTemplate, j);
                            if (closePos == -1) {
                                throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                            }

                            String nestedGroupContent = groupTemplate.substring(j + 1, closePos);

                            for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                                processGroupContent(nestedGroupContent, state, values, 1, startsWithU, modeStack);
                            }

                            j = closePos;
                            continue;
                        } else {
                            // Any valid format can follow '/', not just string types
                            FormatHandler formatHandler = Unpack.getHandler(stringFormat, startsWithU);
                            if (formatHandler == null) {
                                // DEBUG: No handler found for format '" + stringFormat + "'
                                throw new PerlCompilerException("'/' must be followed by a valid format or group");
                            }

                            // Parse optional count/star after the format
                            // In slash constructs, '*' means "use the slash count", not "all remaining"
                            if (j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '*') {
                                j++; // Move to the '*'
                            }

                            // Unpack the format with the count
                            // DEBUG: Unpacking format '" + stringFormat + "' " + slashCount + " times
                            // Always use slashCount in slash constructs, never "all remaining"
                            formatHandler.unpack(state, values, slashCount, false);

                            continue;
                        }
                    }
                }

                // Handle '/' for counted strings within groups
                if (format == '/') {
                    if (values.isEmpty()) {
                        throw new PerlCompilerException("'/' must follow a numeric type");
                    }

                    // Get the count from the last unpacked value
                    RuntimeBase lastValue = values.getLast();
                    int slashCount = ((RuntimeScalar) lastValue).getInt();
                    values.removeLast(); // Remove the count value

                    // Get the string format that follows '/'
                    j++;
                    if (j >= groupTemplate.length()) {
                        throw new PerlCompilerException("Code missing after '/'");
                    }
                    char stringFormat = groupTemplate.charAt(j);

                    // Check if it's a nested group
                    if (stringFormat == '(') {
                        // Find the matching closing parenthesis within the group
                        int closePos = UnpackHelper.findMatchingParen(groupTemplate, j);
                        if (closePos == -1) {
                            throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                        }

                        // Extract nested group content
                        String nestedGroupContent = groupTemplate.substring(j + 1, closePos);

                        // Process the nested group slashCount times
                        for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                            processGroupContent(nestedGroupContent, state, values, 1, startsWithU, modeStack);
                        }

                        j = closePos;
                        continue;
                    } else {
                        // Original code for string formats
                        if (stringFormat != 'a' && stringFormat != 'A' && stringFormat != 'Z') {
                            throw new PerlCompilerException("'/' must be followed by a string type");
                        }

                        // Parse optional count/star after string format
                        // In slash constructs, '*' means "use the slash count", not "all remaining"
                        if (j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '*') {
                            j++; // Move to the '*'
                        }

                        // Unpack the string with the count from the previous numeric value
                        // Always use slashCount in slash constructs, never "all remaining"
                        FormatHandler stringHandler = Unpack.getHandler(stringFormat, startsWithU);
                        stringHandler.unpack(state, values, slashCount, false);

                        continue;
                    }
                }

                // Check for explicit mode modifiers
                if (format == 'C' && j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '0') {
                    state.switchToCharacterMode();
                    j++; // Skip the '0'
                    continue;
                } else if (format == 'U' && j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '0') {
                    state.switchToByteMode();
                    j++; // Skip the '0'
                    continue;
                }

                // Parse count
                int count = 1;
                boolean isStarCount = false;

                if (j + 1 < groupTemplate.length()) {
                    char nextChar = groupTemplate.charAt(j + 1);
                    if (nextChar == '*') {
                        isStarCount = true;
                        j++;
                        count = UnpackHelper.getRemainingCount(state, format, startsWithU);
                    } else if (nextChar == '[') {
                        // Parse repeat count in brackets [n]
                        int k = j + 2;
                        while (k < groupTemplate.length() && Character.isDigit(groupTemplate.charAt(k))) {
                            k++;
                        }
                        if (k >= groupTemplate.length() || groupTemplate.charAt(k) != ']') {
                            throw new PerlCompilerException("No group ending character ']' found in template");
                        }
                        String countStr = groupTemplate.substring(j + 2, k);
                        count = Integer.parseInt(countStr);
                        j = k; // Position at ']'
                    } else if (Character.isDigit(nextChar)) {
                        int k = j + 1;
                        while (k < groupTemplate.length() && Character.isDigit(groupTemplate.charAt(k))) {
                            k++;
                        }
                        String countStr = groupTemplate.substring(j + 1, k);
                        count = Integer.parseInt(countStr);
                        j = k - 1;
                    }
                }

                // Get handler and unpack
                FormatHandler handler = Unpack.getHandler(format, startsWithU);
                if (handler != null) {
                    handler.unpack(state, values, count, isStarCount);
                } else {
                    throw new PerlCompilerException("unpack: unsupported format character: " + format);
                }
            }

            // Check for progress to prevent infinite loops with '*' repeat count
            if (repeatCount == Integer.MAX_VALUE) {
                int positionAfter;
                if (state.isCharacterMode()) {
                    positionAfter = state.getCurrentCodePointIndex();
                } else {
                    positionAfter = state.getTotalLength() - state.remainingBytes();
                }

                int valuesAfterGroup = values.size();

                // Check for position cycling (e.g., @ format moving position backward)
                if (positionHistory.contains(positionAfter)) {
                    // DEBUG: Position cycle detected at position " + positionAfter + ", stopping infinite loop
                    break;
                }
                positionHistory.add(positionAfter);

                // If no progress was made (no data consumed and no values added), stop
                if (positionAfter == positionBefore && valuesAfterGroup == valuesBeforeGroup) {
                    // DEBUG: No progress made in group iteration, stopping infinite loop
                    break;
                }

                // Limit position history size to prevent memory issues
                if (positionHistory.size() > 1000) {
                    positionHistory.remove(0);
                }
            }
            
            // Pop the group baseline after processing this repetition
            state.popGroupBase();
        }

        // Restore mode after processing all repetitions
        if (savedMode) {
            state.switchToCharacterMode();
        } else {
            state.switchToByteMode();
        }
    }

    /**
     * Interface for unpack operations to avoid circular dependencies.
     * This allows UnpackGroupProcessor to call back to the main unpack method.
     * <p>
     * The function receives:
     * - template: The unpack template for the group
     * - state: The current UnpackState (position will be advanced)
     * - startsWithU: Whether the original template starts with U
     * - modeStack: Stack for tracking mode changes
     * <p>
     * Returns: List of unpacked values
     */
    @FunctionalInterface
    public interface UnpackFunction {
        RuntimeList unpack(String template, UnpackState state, boolean startsWithU, Stack<Boolean> modeStack);
    }
}

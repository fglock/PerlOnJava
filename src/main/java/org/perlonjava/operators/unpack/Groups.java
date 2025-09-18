package org.perlonjava.operators.unpack;

import org.perlonjava.operators.Unpack;
import org.perlonjava.operators.UnpackState;
import org.perlonjava.operators.pack.PackHelper;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * The Groups class handles the processing of grouped template patterns in Perl's unpack operation.
 * 
 * <p>In Perl's unpack function, parentheses are used to create groups that can be repeated.
 * For example, the template "(a4 n)*" would unpack pairs of a 4-character string followed
 * by a network-order short integer, repeating until all data is consumed.</p>
 * 
 * <p>This class provides functionality to:</p>
 * <ul>
 *   <li>Process grouped template patterns with repeat counts</li>
 *   <li>Handle nested groups within groups</li>
 *   <li>Manage mode switching (character vs byte mode) within groups</li>
 *   <li>Process slash constructs (e.g., "n/a*" - unpack a count then that many strings)</li>
 *   <li>Handle special positioning and counting operations</li>
 * </ul>
 * 
 * <p>The class maintains state consistency across group iterations and ensures proper
 * handling of various Perl unpack template modifiers and special cases.</p>
 * 
 * @see org.perlonjava.operators.Unpack
 * @see org.perlonjava.operators.UnpackState
 */
public class Groups {
    
    /**
     * Processes a grouped template pattern with the specified repeat count.
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
     * <p><strong>Template Format Examples:</strong></p>
     * <pre>
     * "(a4 n)3"     - Unpack 3 pairs of 4-char string + network short
     * "(C n/a*)*"   - Repeatedly unpack: byte, then count, then that many strings
     * "(a4 (n C)2)" - Nested groups: 4-char string, then 2 pairs of short+byte
     * </pre>
     * 
     * <p><strong>Special Handling:</strong></p>
     * <ul>
     *   <li><strong>Progress detection:</strong> For '*' repeat counts, stops if no data
     *       is consumed in an iteration to prevent infinite loops</li>
     *   <li><strong>Mode switching:</strong> Handles C0/U0 mode switches within groups</li>
     *   <li><strong>Error handling:</strong> Validates parentheses matching and template syntax</li>
     * </ul>
     * 
     * @param groupTemplate The template string for the group content (without outer parentheses)
     * @param state The current unpack state containing data buffer and position information
     * @param values The list to append unpacked values to
     * @param repeatCount The number of times to repeat the group (Integer.MAX_VALUE for '*')
     * @param startsWithU Whether the overall template starts with 'U' (affects default mode)
     * @param modeStack Stack for tracking nested mode changes (currently unused but reserved
     *                  for future nested mode handling)
     * 
     * @throws PerlCompilerException if the template contains syntax errors such as:
     *         <ul>
     *           <li>Unmatched parentheses in nested groups</li>
     *           <li>Invalid format characters</li>
     *           <li>Malformed slash constructs</li>
     *           <li>Missing code after '/' operators</li>
     *         </ul>
     * 
     * @see #processGroup(String, UnpackState, List, int, boolean, Stack) for recursive processing
     * @see org.perlonjava.operators.Unpack#findMatchingParen(String, int) for parentheses matching
     * @see org.perlonjava.operators.pack.PackHelper#isNumericFormat(char) for format validation
     */
    public static void processGroup(String groupTemplate, UnpackState state, List<RuntimeBase> values,
                                    int repeatCount, boolean startsWithU, Stack<Boolean> modeStack) {
        // Save current mode
        boolean savedMode = state.isCharacterMode();

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

            System.err.println("DEBUG: Group iteration " + rep + " starting at position " + positionBefore);
            int valuesBeforeGroup = values.size();

            // Process the group content
            for (int j = 0; j < groupTemplate.length(); j++) {
                char format = groupTemplate.charAt(j);

                // Skip whitespace
                if (Character.isWhitespace(format)) {
                    continue;
                }

                // NEW: Handle commas (skip with warning)
                if (format == ',') {
                    System.err.println("WARNING: Invalid type ',' in unpack");
                    continue;
                }

                // Handle nested groups
                if (format == '(') {
                    // Find the matching closing parenthesis within the group
                    int closePos = Unpack.findMatchingParen(groupTemplate, j);
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
                            int endPos = nestedNextPos;
                            while (endPos < groupTemplate.length() && Character.isDigit(groupTemplate.charAt(endPos))) {
                                endPos++;
                            }
                            nestedRepeatCount = Integer.parseInt(groupTemplate.substring(nestedNextPos, endPos));
                            nestedNextPos = endPos;
                            break;
                        } else {
                            break;
                        }
                    }

                    // Process the nested group with its repeat count
                    processGroup(nestedGroupContent, state, values, nestedRepeatCount, startsWithU, modeStack);

                    // Move past the nested group and its modifiers/count
                    j = nestedNextPos - 1; // -1 because loop will increment
                    continue;
                }

                // Check for mode modifiers
                if (format == 'C' && j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '0') {
                    state.switchToCharacterMode();
                    j++; // Skip the '0'
                    continue;
                } else if (format == 'U' && j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '0') {
                    state.switchToByteMode();
                    j++; // Skip the '0'
                    continue;
                }

                // NEW: Check if this numeric format is part of a '/' construct
                if (PackHelper.isNumericFormat(format)) {
                    int slashPos = PackHelper.checkForSlashConstruct(groupTemplate, j);
                    if (slashPos != -1) {
                        System.err.println("DEBUG: Detected slash construct " + format + "/ at position " + j + " in group");

                        // Unpack the numeric format to get the count
                        FormatHandler handler = Unpack.getHandler(format, startsWithU);
                        handler.unpack(state, values, 1, false);

                        // Get the count value
                        RuntimeBase lastValue = values.getLast();
                        int slashCount = ((RuntimeScalar) lastValue).getInt();
                        values.removeLast();

                        // Move to after the '/'
                        j = slashPos + 1;

                        // Skip whitespace
                        while (j < groupTemplate.length() && Character.isWhitespace(groupTemplate.charAt(j))) {
                            j++;
                        }

                        if (j >= groupTemplate.length()) {
                            throw new PerlCompilerException("Code missing after '/'");
                        }

                        char stringFormat = groupTemplate.charAt(j);

                        if (stringFormat == '(') {
                            int closePos = Unpack.findMatchingParen(groupTemplate, j);
                            if (closePos == -1) {
                                throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                            }

                            String nestedGroupContent = groupTemplate.substring(j + 1, closePos);

                            for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                                processGroup(nestedGroupContent, state, values, 1, startsWithU, modeStack);
                            }

                            j = closePos;
                        } else if (stringFormat == 'a' || stringFormat == 'A' || stringFormat == 'Z') {
                            boolean hasStarAfterSlash = false;
                            if (j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '*') {
                                hasStarAfterSlash = true;
                                j++;
                            }

                            FormatHandler stringHandler = Unpack.handlers.get(stringFormat);
                            stringHandler.unpack(state, values, slashCount, hasStarAfterSlash);
                        } else {
                            throw new PerlCompilerException("'/' must be followed by a string type or group");
                        }

                        continue;
                    }
                }

                // Handle @ format
                if (format == '@') {
                    System.err.println("DEBUG: Found @ in group at position " + j);
                    // Parse count for @
                    int atCount = 0;
                    boolean atHasStar = false;

                    if (j + 1 < groupTemplate.length()) {
                        char nextChar = groupTemplate.charAt(j + 1);
                        if (nextChar == '*') {
                            atHasStar = true;
                            j++;
                        } else if (Character.isDigit(nextChar)) {
                            int k = j + 1;
                            while (k < groupTemplate.length() && Character.isDigit(groupTemplate.charAt(k))) {
                                k++;
                            }
                            atCount = Integer.parseInt(groupTemplate.substring(j + 1, k));
                            j = k - 1;
                        }
                    }

                    // Get handler and unpack
                    FormatHandler handler = Unpack.getHandler('@', startsWithU);
                    if (handler != null) {
                        handler.unpack(state, values, atCount, atHasStar);
                    }
                    continue;
                }

                // Handle '/' for counted strings
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
                        int closePos = Unpack.findMatchingParen(groupTemplate, j);
                        if (closePos == -1) {
                            throw new PerlCompilerException("unpack: unmatched parenthesis in template");
                        }

                        // Extract nested group content
                        String nestedGroupContent = groupTemplate.substring(j + 1, closePos);

                        // Process the nested group slashCount times
                        for (int slashRep = 0; slashRep < slashCount; slashRep++) {
                            processGroup(nestedGroupContent, state, values, 1, startsWithU, modeStack);
                        }

                        // Move past the nested group
                        j = closePos + 1;
                        continue;
                    }

                    if (stringFormat != 'a' && stringFormat != 'A' && stringFormat != 'Z') {
                        throw new PerlCompilerException("'/' must be followed by a string type");
                    }

                    // Parse optional count/star after string format
                    boolean hasStarAfterSlash = false;
                    if (j + 1 < groupTemplate.length() && groupTemplate.charAt(j + 1) == '*') {
                        hasStarAfterSlash = true;
                        j++;
                    }

                    // Unpack the string with the count
                    FormatHandler stringHandler = Unpack.handlers.get(stringFormat);
                    stringHandler.unpack(state, values, hasStarAfterSlash ? slashCount : 1, false);
                    continue;
                }

                // First, parse any modifiers (<, >, !) after the format character
                boolean hasShriek = false;
                int modifierEnd = j;
                while (modifierEnd + 1 < groupTemplate.length() &&
                        (groupTemplate.charAt(modifierEnd + 1) == '!' ||
                                groupTemplate.charAt(modifierEnd + 1) == '<' ||
                                groupTemplate.charAt(modifierEnd + 1) == '>')) {
                    if (groupTemplate.charAt(modifierEnd + 1) == '!') {
                        hasShriek = true;
                    }
                    modifierEnd++; // Skip modifiers
                }

                // Update j to skip past consumed modifiers
                j = modifierEnd;

                // Parse count
                int count = 1;
                boolean isStarCount = false;

                if (j + 1 < groupTemplate.length()) {
                    char nextChar = groupTemplate.charAt(j + 1);
                    if (nextChar == '*') {
                        isStarCount = true;
                        j++;
                        count = Unpack.getRemainingCount(state, format, startsWithU);
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
                        count = Integer.parseInt(groupTemplate.substring(j + 1, k));
                        j = k - 1;
                    }
                }

                // Get handler and unpack
                FormatHandler handler = Unpack.getHandler(format, startsWithU);
                if (handler != null) {
                    // Special handling for '.' with '!' modifier
                    if (format == '.' && hasShriek) {
                        handler = new DotShriekFormatHandler();
                    }
                    handler.unpack(state, values, count, isStarCount);
                } else {
                    throw new PerlCompilerException("unpack: unsupported format character: " + format);
                }
            }

            // DEBUG: Show what values were extracted in this iteration
            System.err.println("DEBUG: Group iteration " + rep + " complete, extracted " +
                    (values.size() - valuesBeforeGroup) + " values");
            if (values.size() > valuesBeforeGroup) {
                List<String> extracted = new ArrayList<>();
                for (int idx = valuesBeforeGroup; idx < values.size(); idx++) {
                    extracted.add(values.get(idx).toString());
                }
                System.err.println("DEBUG: Values: " + extracted);
            }

            // Check if we made progress (for * repeat count)
            if (repeatCount == Integer.MAX_VALUE) {
                int positionAfter;
                if (state.isCharacterMode()) {
                    positionAfter = state.getCurrentCodePointIndex();
                } else {
                    positionAfter = state.getTotalLength() - state.remainingBytes();
                }

                // If we've consumed all data, stop
                if (state.remainingBytes() == 0 && positionAfter >= state.getTotalLength()) {
                    System.err.println("DEBUG: All data consumed, stopping group repetition");
                    break;
                }

                // If no progress was made, stop
                if (positionAfter == positionBefore) {
                    System.err.println("DEBUG: No progress made in group with * repeat, stopping");
                    break;
                }
            }
        }

        // Restore mode
        if (savedMode) {
            state.switchToCharacterMode();
        } else {
            state.switchToByteMode();
        }
    }
}

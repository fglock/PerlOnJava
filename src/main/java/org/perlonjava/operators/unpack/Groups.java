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

public class Groups {
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

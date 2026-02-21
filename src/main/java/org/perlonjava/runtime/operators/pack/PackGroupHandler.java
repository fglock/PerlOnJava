package org.perlonjava.runtime.operators.pack;

import org.perlonjava.runtime.operators.Pack;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * PackGroupHandler handles complex pack template constructs including groups and slash constructs.
 *
 * <p>This class is responsible for processing:</p>
 * <ul>
 *   <li>Parenthesized groups with repeat counts and modifiers</li>
 *   <li>Slash constructs (N/X format) for length-prefixed data</li>
 *   <li>Value index tracking for complex template structures</li>
 * </ul>
 *
 * <p>Groups allow applying repeat counts and modifiers to multiple format characters,
 * while slash constructs enable packing data with length prefixes calculated dynamically.</p>
 *
 * @see Pack
 * @see PackParser
 * @see PackHelper
 */
public class PackGroupHandler {
    /**
     * Enable trace output for pack group operations.
     * Set to true to debug group nesting and processing.
     */
    private static final boolean TRACE_PACK = false;
    
    // Thread-local to track group nesting depth
    private static final ThreadLocal<Integer> nestingDepth = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_NESTING_DEPTH = 100;

    /**
     * Handles a group in the template string, starting from the given position.
     *
     * <p>Groups are enclosed in parentheses and can have modifiers and repeat counts.
     * This method processes the group content according to the specified repeat count
     * and handles special cases like backup operations within groups.</p>
     *
     * @param template     The template string
     * @param openPos      The starting position of the group (points to '(')
     * @param values       The list of values to pack
     * @param output       The output stream
     * @param valueIndex   The current index in the values list
     * @param byteMode     The current byte mode
     * @param byteModeUsed Whether byte mode has been used
     * @param hasUnicodeInNormalMode Whether Unicode has been used in normal mode
     * @return GroupResult containing the position after the group and updated value index
     * @throws PerlCompilerException if parentheses are unmatched or endianness conflicts
     */
    public static GroupResult handleGroup(String template, int openPos, List<RuntimeScalar> values,
                                          PackBuffer output, int valueIndex,
                                          boolean byteMode, boolean byteModeUsed, boolean hasUnicodeInNormalMode) {
        /**
         * Track recursion depth to prevent stack overflow from deeply nested groups.
         * 
         * Example that should fail:
         *   pack( "(" x 105 . "A" . ")" x 105 )
         * 
         * This creates 105 levels of nesting. Perl limits this to ~100 levels.
         * Without this check, Java would eventually throw StackOverflowError.
         * 
         * Implementation uses ThreadLocal to:
         * - Support multiple threads packing simultaneously
         * - Maintain separate depth counters per thread
         * - Automatically reset when thread ends
         */
        int currentDepth = nestingDepth.get() + 1;
        
        if (TRACE_PACK) {
            System.err.println("TRACE PackGroupHandler.handleGroup:");
            System.err.println("  depth: " + currentDepth);
            System.err.println("  openPos: " + openPos);
            System.err.println("  template: [" + template + "]");
            System.err.flush();
        }
        
        if (currentDepth > MAX_NESTING_DEPTH) {
            throw new PerlCompilerException("Too deeply nested ()-groups in pack");
        }
        nestingDepth.set(currentDepth);
        
        try {
            return handleGroupInternal(template, openPos, values, output, valueIndex, byteMode, byteModeUsed, hasUnicodeInNormalMode);
        } finally {
            // Always decrement depth when exiting, even if an exception occurred
            nestingDepth.set(currentDepth - 1);
        }
    }
    
    private static GroupResult handleGroupInternal(String template, int openPos, List<RuntimeScalar> values,
                                          PackBuffer output, int valueIndex,
                                          boolean byteMode, boolean byteModeUsed, boolean hasUnicodeInNormalMode) {
        // Find matching closing parenthesis
        int closePos = PackHelper.findMatchingParen(template, openPos);
        if (closePos == -1) {
            throw new PerlCompilerException("pack: unmatched parenthesis in template");
        }

        // Extract group content
        String groupContent = template.substring(openPos + 1, closePos);
        
        if (TRACE_PACK) {
            System.err.println("TRACE PackGroupHandler.handleGroupInternal:");
            System.err.println("  positions: " + openPos + " to " + closePos);
            System.err.println("  groupContent: [" + groupContent + "]");
            System.err.flush();
        }

        // Validate that group doesn't start with a count
        if (!groupContent.isEmpty()) {
            char firstChar = groupContent.charAt(0);
            if (firstChar == '*' || Character.isDigit(firstChar) || firstChar == '[') {
                throw new PerlCompilerException("()-group starts with a count in pack");
            }
        }

        // Parse group modifiers and repeat count
        GroupInfo groupInfo = PackParser.parseGroupInfo(template, closePos);

        // Check for conflicting endianness within the group
        if (groupInfo.endian != ' ' && PackHelper.hasConflictingEndianness(groupContent, groupInfo.endian)) {
            throw new PerlCompilerException("Can't use '" + groupInfo.endian + "' in a group with different byte-order in pack");
        }

        // Process the group content repeatedly
        for (int rep = 0; rep < groupInfo.repeatCount; rep++) {
            Pack.pushGroupBase(output.size());
            try {
                // Special case: if group starts with X, handle it directly on parent buffer
                if (groupContent.startsWith("X")) {
                    // Handle X directly on the parent's output
                    int xCount = 1;
                    int xPos = 1;

                    // Check for count after X
                    if (xPos < groupContent.length() && Character.isDigit(groupContent.charAt(xPos))) {
                        int j = xPos;
                        while (j < groupContent.length() && Character.isDigit(groupContent.charAt(j))) {
                            j++;
                        }
                        xCount = Integer.parseInt(groupContent.substring(xPos, j));
                        xPos = j;
                    }

                    // Backup in parent buffer using ControlPackHandler
                    ControlPackHandler backupHandler = new ControlPackHandler('X');
                    backupHandler.pack(values, valueIndex, xCount, false, new ParsedModifiers(), output);

                    // Process the rest of the group normally
                    if (xPos < groupContent.length()) {
                        String remainingContent = groupContent.substring(xPos);
                        
                        // Pack directly into the parent buffer
                        Pack.PackResult result = Pack.packInto(remainingContent, values, valueIndex, output, byteMode, hasUnicodeInNormalMode);
                        valueIndex = result.valueIndex();
                        byteMode = result.byteMode();
                        byteModeUsed = byteModeUsed || result.byteModeUsed();
                        hasUnicodeInNormalMode = result.hasUnicodeInNormalMode();
                    }
                } else {
                    // Normal group processing
                    // Apply group-level endianness to the content if specified
                    String effectiveContent = groupContent;
                    if (groupInfo.endian != ' ') {
                        // Apply endianness modifier to each format in the group
                        // This ensures the group's endianness applies to all formats inside
                        effectiveContent = GroupEndiannessHelper.applyGroupEndianness(groupContent, groupInfo.endian);
                    }

                    // Collect values for this group iteration
                    int groupValueCount = PackHelper.countValuesNeeded(groupContent);
                    // DEBUG: group '" + groupContent + "' needs " + groupValueCount + " values, valueIndex=" + valueIndex + ", remaining=" + (values.size() - valueIndex)

                    // Handle * groups
                    if (groupInfo.repeatCount == Integer.MAX_VALUE) {
                        if (groupValueCount == Integer.MAX_VALUE || values.size() - valueIndex < groupValueCount) {
                            break;
                        }
                    }

                    // Pack directly into the parent buffer instead of creating a new buffer.
                    // This allows '.' and '@' inside groups to operate on the parent buffer's position.
                    Pack.PackResult result = Pack.packInto(effectiveContent, values, valueIndex, output, byteMode, hasUnicodeInNormalMode);
                    valueIndex = result.valueIndex();
                    byteMode = result.byteMode();
                    byteModeUsed = byteModeUsed || result.byteModeUsed();
                    hasUnicodeInNormalMode = result.hasUnicodeInNormalMode();
                }
            } finally {
                Pack.popGroupBase();
            }
        }

        return new GroupResult(groupInfo.endPosition - 1, valueIndex, byteMode, byteModeUsed, hasUnicodeInNormalMode); // -1 because loop will increment
    }

    /**
     * Returns the index in the values list after processing a group.
     *
     * <p>This method calculates how many values were consumed by a group
     * based on the group content and repeat count. It's used to update
     * the value index correctly after group processing.</p>
     *
     * @param template          The template string
     * @param groupEndPos       The ending position of the group (points to ')')
     * @param values            The list of values to pack
     * @param currentValueIndex The current index in the values list
     * @return The index after the group
     */
    public static int getValueIndexAfterGroup(String template, int groupEndPos, List<RuntimeScalar> values, int currentValueIndex) {
        // Find the group start
        int depth = 1;
        int groupStart = groupEndPos;
        while (groupStart >= 0 && depth > 0) {
            if (template.charAt(groupStart) == ')') depth++;
            else if (template.charAt(groupStart) == '(') depth--;
            groupStart--;
        }
        groupStart++; // Adjust to point to '('

        String groupContent = template.substring(groupStart + 1, groupEndPos);
        GroupInfo groupInfo = PackParser.parseGroupInfo(template, groupEndPos);

        int valuesPerIteration = PackHelper.countValuesNeeded(groupContent);
        if (valuesPerIteration == Integer.MAX_VALUE || groupInfo.repeatCount == Integer.MAX_VALUE) {
            // For *, consume as many complete iterations as possible
            while (currentValueIndex + valuesPerIteration <= values.size()) {
                currentValueIndex += valuesPerIteration;
            }
        } else {
            currentValueIndex += valuesPerIteration * groupInfo.repeatCount;
        }

        return currentValueIndex;
    }

    /**
     * Handles a slash construct in pack templates.
     *
     * <p>Slash constructs (N/X format) allow using the result of one format
     * as the count for another format. For example, "n/a*" packs a short
     * containing the length, followed by that many characters.</p>
     *
     * <p>This method handles both string formats (a, A, Z, U) and numeric
     * formats after the slash, with proper length calculation and data packing.</p>
     *
     * @param template     The template string
     * @param position     The starting position of the slash construct
     * @param slashPos     The position of the slash character
     * @param format       The format character before the slash
     * @param values       The list of values to pack
     * @param valueIndex   The current index in the values list
     * @param output       The output stream
     * @param modifiers    The modifiers for the format
     * @param byteMode     The current byte mode
     * @param byteModeUsed Whether byte mode has been used
     * @param hasUnicodeInNormalMode Whether Unicode has been used in normal mode
     * @return GroupResult containing template position and updated value index
     * @throws PerlCompilerException if slash construct is malformed
     */
    public static GroupResult handleSlashConstruct(String template, int position, int slashPos, char format,
                                                   List<RuntimeScalar> values, int valueIndex,
                                                   PackBuffer output, ParsedModifiers modifiers,
                                                   boolean byteMode, boolean byteModeUsed, boolean hasUnicodeInNormalMode) {
        // DEBUG: handling " + format + "/ construct at position " + position

        // Skip whitespace after '/'
        int stringPos = slashPos + 1;
        while (stringPos < template.length() && Character.isWhitespace(template.charAt(stringPos))) {
            stringPos++;
        }

        if (stringPos >= template.length()) {
            throw new PerlCompilerException("Code missing after '/'");
        }

        char stringFormat = template.charAt(stringPos);
        // DEBUG: string format after '/' is '" + stringFormat + "'

        // Check if '/' is followed by a repeat count (which is invalid)
        if (stringFormat == '*' || (stringFormat >= '0' && stringFormat <= '9') || stringFormat == '[') {
            throw new PerlCompilerException("'/' does not take a repeat count");
        }

        // Parse string count
        ParsedCount stringCountInfo = PackParser.parseRepeatCount(template, stringPos);
        // In Perl, N/S without explicit count means "pack all remaining values" (like N/S*)
        // If no count was specified, endPosition stays at stringPos
        boolean noCountSpecified = (stringCountInfo.endPosition == stringPos);
        int stringCount = stringCountInfo.hasStar || noCountSpecified ? -1 : stringCountInfo.count;
        int endPos = stringCountInfo.endPosition;

        // Get the string value - handle missing arguments gracefully
        RuntimeScalar strValue;
        if (valueIndex >= values.size()) {
            // No more arguments - use empty string for string formats, 0 for numeric
            if (stringFormat == 'a' || stringFormat == 'A' || stringFormat == 'Z' || stringFormat == 'U') {
                strValue = new RuntimeScalar("");
            } else {
                strValue = new RuntimeScalar(0);
            }
        } else {
            strValue = values.get(valueIndex);
        }

        String str = strValue.toString();

        // Determine what to pack
        byte[] dataToWrite;
        int lengthToWrite;

        if (stringFormat == 'U') {
            // For U format, handle Unicode specially
            if (stringCount >= 0) {
                // Specific count requested - take first N characters
                int actualCount = Math.min(stringCount, str.length());
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < actualCount; k++) {
                    sb.append(str.charAt(k));
                }
                str = sb.toString();
                dataToWrite = str.getBytes(StandardCharsets.UTF_8);
                lengthToWrite = actualCount;
            } else {
                // Use full string
                dataToWrite = str.getBytes(StandardCharsets.UTF_8);
                lengthToWrite = str.length();
            }
        } else if (stringFormat == 'a' || stringFormat == 'A' || stringFormat == 'Z') {
            // Handle string formats (a, A, Z).
            // Use PackWriter.writeString() so fixed-width Z<count> correctly reserves space for
            // a trailing NUL within the field (e.g. Z3 -> 2 bytes + "\0").
            int effectiveCount;
            if (stringCount >= 0) {
                effectiveCount = stringCount;
            } else {
                byte[] strBytes = byteMode
                        ? str.getBytes(StandardCharsets.ISO_8859_1)
                        : str.getBytes(StandardCharsets.UTF_8);
                effectiveCount = strBytes.length;
                if (stringFormat == 'Z') {
                    effectiveCount++; // Include null terminator in count
                }
            }

            PackBuffer tmp = new PackBuffer();
            PackWriter.writeString(tmp, str, effectiveCount, stringFormat, byteMode);
            dataToWrite = tmp.toByteArray();
            lengthToWrite = tmp.size();
        } else {
            // For non-string formats after '/', we need to handle them differently
            // The format after '/' specifies what to pack, and we pack that many items
            // Count how many values are available
            int availableValues = values.size() - valueIndex;
            int itemsToWrite;

            if (stringCount >= 0) {
                // Use the minimum of requested count and available values
                itemsToWrite = Math.min(stringCount, availableValues);
            } else {
                // Use all available values
                itemsToWrite = availableValues;
            }

            // Pack the length first
            PackHelper.packLength(output, format, itemsToWrite, modifiers);

            // Now pack the items using the specified format
            for (int j = 0; j < itemsToWrite && valueIndex < values.size(); j++) {
                // Pack each item directly into the parent buffer
                String itemTemplate = String.valueOf(stringFormat);
                Pack.PackResult result = Pack.packInto(itemTemplate, values, valueIndex, output, byteMode, hasUnicodeInNormalMode);
                valueIndex = result.valueIndex();
                byteMode = result.byteMode();
                byteModeUsed = byteModeUsed || result.byteModeUsed();
                hasUnicodeInNormalMode = result.hasUnicodeInNormalMode();
            }

            // Return the position after consuming the correct number of values
            return new GroupResult(endPos, valueIndex, byteMode, byteModeUsed, hasUnicodeInNormalMode);
        }

        // Pack the length using the numeric format
        PackHelper.packLength(output, format, lengthToWrite, modifiers);

        // Write the string data
        output.write(dataToWrite, 0, dataToWrite.length);

        // For string formats, we consumed exactly 1 value
        return new GroupResult(endPos, valueIndex + 1, byteMode, byteModeUsed, hasUnicodeInNormalMode);
    }

    /**
     * Result of processing a group, containing both the template position and updated value index.
     */
    public record GroupResult(int position, int valueIndex, boolean byteMode, boolean byteModeUsed, boolean hasUnicodeInNormalMode) {
    }
}

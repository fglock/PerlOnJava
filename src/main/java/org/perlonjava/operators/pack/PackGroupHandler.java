package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

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
 * @see org.perlonjava.operators.Pack
 * @see PackParser
 * @see PackHelper
 */
public class PackGroupHandler {

    /**
     * Interface for pack operations to avoid circular dependencies.
     * This allows PackGroupHandler to call back to the main pack method.
     */
    @FunctionalInterface
    public interface PackFunction {
        RuntimeScalar pack(RuntimeList args);
    }

    /**
     * Result of processing a group, containing both the template position and updated value index.
     */
    public record GroupResult(int position, int valueIndex) {
    }

    /**
     * Handles a group in the template string, starting from the given position.
     * 
     * <p>Groups are enclosed in parentheses and can have modifiers and repeat counts.
     * This method processes the group content according to the specified repeat count
     * and handles special cases like backup operations within groups.</p>
     * 
     * @param template The template string
     * @param openPos The starting position of the group (points to '(')
     * @param values The list of values to pack
     * @param output The output stream
     * @param valueIndex The current index in the values list
     * @param packFunction Function to call for recursive packing
     * @return GroupResult containing the position after the group and updated value index
     * @throws PerlCompilerException if parentheses are unmatched or endianness conflicts
     */
    public static GroupResult handleGroup(String template, int openPos, List<RuntimeScalar> values,
                                   PackBuffer output, int valueIndex, PackFunction packFunction) {
        // Find matching closing parenthesis
        int closePos = PackHelper.findMatchingParen(template, openPos);
        if (closePos == -1) {
            throw new PerlCompilerException("pack: unmatched parenthesis in template");
        }

        // Extract group content
        String groupContent = template.substring(openPos + 1, closePos);
        // DEBUG: found group at positions " + openPos + " to " + closePos + ", content: '" + groupContent + "'

        // Parse group modifiers and repeat count
        GroupInfo groupInfo = PackParser.parseGroupInfo(template, closePos);

        // Check for conflicting endianness within the group
        if (groupInfo.endian != ' ' && PackHelper.hasConflictingEndianness(groupContent, groupInfo.endian)) {
            throw new PerlCompilerException("Can't use '" + groupInfo.endian + "' in a group with different byte-order in pack");
        }

        // Process the group content repeatedly
        for (int rep = 0; rep < groupInfo.repeatCount; rep++) {
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
                    RuntimeList groupArgs = new RuntimeList();
                    groupArgs.add(new RuntimeScalar(remainingContent));

                    // Collect values for remaining content
                    int remainingValueCount = PackHelper.countValuesNeeded(remainingContent);
                    for (int v = 0; v < remainingValueCount && valueIndex < values.size(); v++) {
                        groupArgs.add(values.get(valueIndex++));
                    }

                    RuntimeScalar groupResult = packFunction.pack(groupArgs);
                    byte[] groupBytes = groupResult.toString().getBytes(StandardCharsets.ISO_8859_1);
                    output.write(groupBytes, 0, groupBytes.length);
                }
            } else {
                // Normal group processing
                RuntimeList groupArgs = new RuntimeList();
                groupArgs.add(new RuntimeScalar(groupContent));

                // Collect values for this group iteration
                int groupValueCount = PackHelper.countValuesNeeded(groupContent);
                // DEBUG: group '" + groupContent + "' needs " + groupValueCount + " values, valueIndex=" + valueIndex + ", remaining=" + (values.size() - valueIndex)

                // Handle * groups
                if (groupInfo.repeatCount == Integer.MAX_VALUE) {
                    if (groupValueCount == Integer.MAX_VALUE || values.size() - valueIndex < groupValueCount) {
                        break;
                    }
                }

                for (int v = 0; v < groupValueCount && valueIndex < values.size(); v++) {
                    groupArgs.add(values.get(valueIndex++));
                }

                // Recursively pack the group
                RuntimeScalar groupResult = packFunction.pack(groupArgs);
                byte[] groupBytes = groupResult.toString().getBytes(StandardCharsets.ISO_8859_1);
                output.write(groupBytes, 0, groupBytes.length);
            }
        }

        return new GroupResult(groupInfo.endPosition - 1, valueIndex); // -1 because loop will increment
    }

    /**
     * Returns the index in the values list after processing a group.
     * 
     * <p>This method calculates how many values were consumed by a group
     * based on the group content and repeat count. It's used to update
     * the value index correctly after group processing.</p>
     * 
     * @param template The template string
     * @param groupEndPos The ending position of the group (points to ')')
     * @param values The list of values to pack
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
     * @param template The template string
     * @param position The starting position of the slash construct
     * @param slashPos The position of the slash character
     * @param format The format character before the slash
     * @param values The list of values to pack
     * @param valueIndex The current index in the values list
     * @param output The output stream
     * @param modifiers The modifiers for the format
     * @param packFunction Function to call for recursive packing
     * @return The new position in the template string
     * @throws PerlCompilerException if slash construct is malformed
     */
    public static int handleSlashConstruct(String template, int position, int slashPos, char format,
                                            List<RuntimeScalar> values, int valueIndex,
                                            PackBuffer output, ParsedModifiers modifiers,
                                            PackFunction packFunction) {
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

        // Parse string count
        ParsedCount stringCountInfo = PackParser.parseRepeatCount(template, stringPos);
        int stringCount = stringCountInfo.hasStar ? -1 : stringCountInfo.count;
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
            // Handle string formats (a, A, Z)
            if (stringCount >= 0) {
                // Specific count requested
                byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
                int actualCount = Math.min(stringCount, strBytes.length);
                dataToWrite = new byte[stringCount];
                System.arraycopy(strBytes, 0, dataToWrite, 0, actualCount);
                // Pad with nulls or spaces depending on format
                byte padByte = (stringFormat == 'A') ? (byte) ' ' : (byte) 0;
                for (int k = actualCount; k < stringCount; k++) {
                    dataToWrite[k] = padByte;
                }
                lengthToWrite = stringCount;
            } else {
                // Use full string
                dataToWrite = str.getBytes(StandardCharsets.UTF_8);
                lengthToWrite = dataToWrite.length;
                if (stringFormat == 'Z') {
                    lengthToWrite++; // Include null terminator in count
                }
            }
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
                // Create a sub-pack for each item
                RuntimeList itemArgs = new RuntimeList();
                itemArgs.add(new RuntimeScalar(String.valueOf(stringFormat)));
                itemArgs.add(values.get(valueIndex++));

                RuntimeScalar itemResult = packFunction.pack(itemArgs);
                byte[] itemBytes = itemResult.toString().getBytes(StandardCharsets.ISO_8859_1);
                output.write(itemBytes, 0, itemBytes.length);
            }

            // Return the position after consuming the correct number of values
            return endPos;
        }

        // Pack the length using the numeric format
        PackHelper.packLength(output, format, lengthToWrite, modifiers);

        // Write the string data
        output.write(dataToWrite, 0, dataToWrite.length);
        if (stringFormat == 'Z' && stringCount < 0) {
            output.write(0); // null terminator
        }

        return endPos;
    }
}

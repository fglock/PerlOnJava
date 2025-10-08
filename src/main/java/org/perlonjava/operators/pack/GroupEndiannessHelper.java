package org.perlonjava.operators.pack;

/**
 * Helper class for applying group-level endianness modifiers to pack/unpack templates.
 * This logic is shared between Pack and Unpack operations.
 */
public class GroupEndiannessHelper {
    
    /**
     * Apply group-level endianness to all applicable formats in the group content.
     * This adds the endianness modifier after each format character that supports it,
     * and recursively applies to nested groups.
     * 
     * @param groupContent The original group content
     * @param endian The endianness modifier ('&lt;' or '&gt;')
     * @return Modified group content with endianness applied
     */
    public static String applyGroupEndianness(String groupContent, char endian) {
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < groupContent.length(); i++) {
            char c = groupContent.charAt(i);
            
            // Handle nested groups
            if (c == '(') {
                int closePos = PackHelper.findMatchingParen(groupContent, i);
                if (closePos != -1) {
                    // Extract nested group content
                    String nestedContent = groupContent.substring(i + 1, closePos);
                    // Recursively apply endianness to nested group
                    String modifiedNested = applyGroupEndianness(nestedContent, endian);
                    result.append('(').append(modifiedNested).append(')');
                    i = closePos; // Skip to closing paren
                    continue;
                }
            }
            
            result.append(c);
            
            // Check if this is a format that supports endianness
            if ("sSiIlLqQjJfFdDpP".indexOf(c) >= 0) {
                // Check if next character is '!' modifier - if so, copy it first
                int nextPos = i + 1;
                if (nextPos < groupContent.length() && groupContent.charAt(nextPos) == '!') {
                    result.append('!');
                    i = nextPos; // Move past the '!'
                    nextPos++;
                }
                
                // Now check if there's already an endianness modifier
                if (nextPos < groupContent.length()) {
                    char next = groupContent.charAt(nextPos);
                    if (next != '<' && next != '>') {
                        // Add the group's endianness modifier
                        result.append(endian);
                    }
                } else {
                    // At end of string, add endianness
                    result.append(endian);
                }
            }
        }
        
        return result.toString();
    }
}

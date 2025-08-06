package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.*;

/**
 * The RuntimeTransliterate class implements Perl's tr/// operator, which is used for character
 * transliteration. It provides functionality to compile a transliteration pattern and apply it
 * to a given string.
 */
public class RuntimeTransliterate {

    // Mapping from source characters to target characters
    private Map<Integer, Integer> translationMap;

    // Set of characters to delete
    private Set<Integer> deleteSet;

    // Set of characters that are part of the search pattern
    private Set<Integer> searchSet;

    // Modifier flags
    private boolean complement;
    private boolean deleteUnmatched;
    private boolean squashDuplicates;
    private boolean returnOriginal;

    // For complement mode, we need to know the replacement pattern
    private List<Integer> replacementChars;

    /**
     * Compiles a RuntimeTransliterate object from a pattern string with optional modifiers.
     */
    public static RuntimeTransliterate compile(RuntimeScalar search, RuntimeScalar replace, RuntimeScalar modifiers) {
        RuntimeTransliterate transliterate = new RuntimeTransliterate();
        transliterate.compileTransliteration(search.toString(), replace.toString(), modifiers.toString());
        return transliterate;
    }

    /**
     * Applies the transliteration pattern to the given string.
     */
    public RuntimeScalar transliterate(RuntimeScalar originalString, int ctx) {
        String input = originalString.toString();
        StringBuilder result = new StringBuilder();
        int count = 0;
        Integer lastChar = null;

        // For complement mode, we need to track replacement index
        Map<Integer, Integer> complementMap = new HashMap<>();
        int replacementIndex = 0;

        for (int i = 0; i < input.length(); i++) {
            int codePoint = input.codePointAt(i);

            // Handle surrogate pairs for Unicode
            if (Character.isHighSurrogate(input.charAt(i)) && i + 1 < input.length()) {
                i++; // Skip the low surrogate
            }

            boolean matched = false;

            if (complement) {
                // In complement mode, we process characters NOT in the original search set
                matched = !searchSet.contains(codePoint);
            } else {
                // Normal mode: process characters IN the search set
                matched = searchSet.contains(codePoint);
            }

            if (matched) {
                count++;

                if (complement) {
                    // Special handling for complement mode
                    if (deleteUnmatched && replacementChars.isEmpty()) {
                        // Delete mode with empty replacement
                        lastChar = null;
                    } else if (replacementChars.isEmpty()) {
                        // Empty replacement, non-delete mode - keep character as is
                        if (!squashDuplicates || lastChar == null || lastChar != codePoint) {
                            appendCodePoint(result, codePoint);
                        }
                        lastChar = codePoint;
                    } else {
                        Integer mappedChar = null;

                        // Check if this is the common case of search range 0x00-0xFF
                        if (isRange0x00_0xFF(searchSet)) {
                            // Calculate position relative to first char after range
                            int position = codePoint - 0x100;
                            if (position >= 0) {
                                // Use position as index with wraparound
                                int index = position % replacementChars.size();
                                mappedChar = replacementChars.get(index);
                            } else {
                                // This shouldn't happen for chars matching complement
                                mappedChar = replacementChars.get(0);
                            }
                        } else {
                            // For other search ranges, use sequential assignment
                            // Check if we've already assigned a mapping for this character
                            if (complementMap.containsKey(codePoint)) {
                                mappedChar = complementMap.get(codePoint);
                            } else {
                                // Assign new mapping
                                if (replacementIndex < replacementChars.size()) {
                                    mappedChar = replacementChars.get(replacementIndex++);
                                    complementMap.put(codePoint, mappedChar);
                                } else if (deleteUnmatched) {
                                    // With /d modifier, delete characters that have no replacement
                                    lastChar = null;
                                    continue;  // Skip this character (delete it)
                                } else {
                                    // Use last replacement character
                                    mappedChar = replacementChars.get(replacementChars.size() - 1);
                                    complementMap.put(codePoint, mappedChar);
                                }
                            }
                        }

                        if (!squashDuplicates || lastChar == null || !lastChar.equals(mappedChar)) {
                            appendCodePoint(result, mappedChar);
                            lastChar = mappedChar;
                        }
                    }
                } else {
                    // Normal mode handling
                    if (deleteSet.contains(codePoint)) {
                        // Character should be deleted - DON'T change lastChar!
                        // We need to preserve it for squashing logic
                    } else if (translationMap.containsKey(codePoint)) {
                        int mappedChar = translationMap.get(codePoint);
                        // Handle squash duplicates
                        if (!squashDuplicates || lastChar == null || !lastChar.equals(mappedChar)) {
                            appendCodePoint(result, mappedChar);
                            lastChar = mappedChar;
                        } else {
                        }
                    } else {
                        // No mapping found (shouldn't happen if compilation is correct)
                        appendCodePoint(result, codePoint);
                        lastChar = codePoint;
                    }
                }
            } else {
                // Character not matched - keep as is
                appendCodePoint(result, codePoint);
                lastChar = codePoint;
            }
        }

        String resultString = result.toString();

        // Handle the /r modifier - return the transliterated string without modifying original
        if (returnOriginal) {
            return new RuntimeScalar(resultString);
        }

        // Modify the original string
        originalString.set(resultString);

        // Return the count of matched characters
        return new RuntimeScalar(count);
    }

    private boolean isRange0x00_0xFF(Set<Integer> searchSet) {
        // Check if searchSet contains exactly the range 0x00-0xFF
        if (searchSet.size() != 256) return false;
        for (int i = 0; i <= 0xFF; i++) {
            if (!searchSet.contains(i)) return false;
        }
        return true;
    }

    /**
     * Compiles the transliteration pattern and replacement strings with the given modifiers.
     */
    public void compileTransliteration(String search, String replace, String modifiers) {
        // Parse modifiers
        complement = modifiers.contains("c");
        deleteUnmatched = modifiers.contains("d");
        squashDuplicates = modifiers.contains("s");
        returnOriginal = modifiers.contains("r");

        // Expand ranges and escapes
        List<Integer> searchChars = expandRangesAndEscapes(search);
        List<Integer> replaceChars = expandRangesAndEscapes(replace);

        // Initialize data structures
        translationMap = new HashMap<>();
        deleteSet = new HashSet<>();
        searchSet = new HashSet<>(searchChars);
        replacementChars = replaceChars;

        if (!complement) {
            setupNormalMapping(searchChars, replaceChars);
        }
        // For complement mode, we handle mapping dynamically during transliteration
    }

    /**
     * Sets up the translation map for normal (non-complement) mode.
     */
    private void setupNormalMapping(List<Integer> searchChars, List<Integer> replaceChars) {
        int searchLen = searchChars.size();
        int replaceLen = replaceChars.size();

        // Debug: Track mapping index for proper assignment
        int mappingIndex = 0;

        for (int i = 0; i < searchLen; i++) {
            int searchChar = searchChars.get(i);

            // Skip if already mapped (character appeared earlier in search pattern)
            if (translationMap.containsKey(searchChar) || deleteSet.contains(searchChar)) {
                continue;
            }

            if (mappingIndex < replaceLen) {
                // Direct mapping using the mapping index, not i
                translationMap.put(searchChar, replaceChars.get(mappingIndex));
                mappingIndex++;
            } else if (deleteUnmatched || replaceLen == 0) {
                // Delete this character
                deleteSet.add(searchChar);
            } else {
                // Map to last character in replacement
                translationMap.put(searchChar, replaceChars.get(replaceLen - 1));
            }
        }
    }

    /**
     * Expands character ranges and escape sequences in the input string.
     * Returns a list of Unicode code points.
     */
    private List<Integer> expandRangesAndEscapes(String input) {
        List<Integer> expanded = new ArrayList<>();

        int i = 0;
        while (i < input.length()) {
            // Parse the current character
            List<Integer> currentChar = new ArrayList<>();
            int consumed = parseCharAt(input, i, currentChar);

            if (consumed == 0 || currentChar.isEmpty()) {
                i++;
                continue;
            }

            // Check if the next character is a dash (range operator)
            int nextPos = i + consumed;
            if (nextPos < input.length() && input.charAt(nextPos) == '-' &&
                    nextPos + 1 < input.length()) {

                // This might be a range - parse the character after the dash
                List<Integer> endChar = new ArrayList<>();
                int endConsumed = parseCharAt(input, nextPos + 1, endChar);

                if (endConsumed > 0 && !endChar.isEmpty()) {
                    // We have a valid range
                    int start = currentChar.get(0);
                    int end = endChar.get(0);

                    // Validate range
                    if (start > end) {
                        String startStr = formatCharForError(start);
                        String endStr = formatCharForError(end);
                        throw new PerlCompilerException("Invalid range \"" + startStr + "-" + endStr +
                                "\" in transliteration operator");
                    }

                    // Add the range
                    for (int c = start; c <= end; c++) {
                        expanded.add(c);
                    }

                    // Skip past the range
                    i = nextPos + 1 + endConsumed;
                    continue;
                }
            }

            // Not a range, just add the character
            expanded.addAll(currentChar);
            i += consumed;
        }

        return expanded;
    }

    /**
     * Formats a character for error messages.
     * Printable characters are shown as-is, non-printable as \x{XXXX}.
     */
    private String formatCharForError(int codePoint) {
        // Check if the character is printable ASCII or a common printable character
        // Basic printable ASCII range (excluding control characters)
        if (codePoint >= 0x20 && codePoint <= 0x7E) {
            return new String(Character.toChars(codePoint));
        }

        // Format as \x{XXXX} with appropriate padding
        if (codePoint <= 0xFF) {
            return String.format("\\x{%02X}", codePoint);
        } else {
            return String.format("\\x{%04X}", codePoint);
        }
    }

    /**
     * Parses a character at the given position, handling escape sequences.
     * Returns the number of characters consumed.
     */
    private int parseCharAt(String input, int pos, List<Integer> result) {
        if (pos >= input.length()) {
            return 0;
        }

        char ch = input.charAt(pos);

        if (ch == '\\' && pos + 1 < input.length()) {
            char next = input.charAt(pos + 1);
            switch (next) {
                case 'n':
                    result.add((int) '\n');
                    return 2;
                case 't':
                    result.add((int) '\t');
                    return 2;
                case 'r':
                    result.add((int) '\r');
                    return 2;
                case 'f':
                    result.add((int) '\f');
                    return 2;
                case 'b':
                    result.add((int) '\b');
                    return 2;
                case 'a':
                    result.add(0x07);
                    return 2; // Bell character
                case 'e':
                    result.add(0x1B);
                    return 2; // Escape character
                case '0':
                    result.add(0);
                    return 2; // Null character
                case 'x':
                    return 2 + parseHexSequence(input, pos + 2, result);
                case '-':
                    // Escaped dash
                    result.add((int) '-');
                    return 2;
                case 'N':
                    if (pos + 2 < input.length() && input.charAt(pos + 2) == '{') {
                        int closePos = input.indexOf('}', pos + 3);
                        if (closePos > pos + 3) {
                            String content = input.substring(pos + 3, closePos);

                            // Check if it's a Unicode code point \N{U+XXXX}
                            if (content.startsWith("U+")) {
                                try {
                                    int codePoint = Integer.parseInt(content.substring(2), 16);
                                    result.add(codePoint);
                                    return closePos - pos + 1;
                                } catch (NumberFormatException e) {
                                    // Invalid format
                                }
                            }

                            // For named characters, we'd need a lookup table
                            // For now, throw error for named sequences
                            throw new RuntimeException("\\" + "N{" + content +
                                    "} must not be a named sequence in transliteration operator");
                        }
                    }
                    result.add((int) 'N');
                    return 2;
                default:
                    // Other escaped character
                    result.add((int) next);
                    return 2;
            }
        } else {
            // Regular character
            result.add((int) ch);
            return 1;
        }
    }

    /**
     * Parses hexadecimal escape sequences (\xNN or \x{NNNN}).
     * Returns the number of additional characters consumed (after \x).
     */
    private int parseHexSequence(String input, int start, List<Integer> result) {
        if (start >= input.length()) {
            result.add((int) 'x'); // Invalid sequence, treat as literal 'x'
            return -2; // Back up to just after '\'
        }

        if (input.charAt(start) == '{') {
            // \x{NNNN} format
            int end = input.indexOf('}', start + 1);
            if (end > start + 1 && end < input.length()) {
                String hexStr = input.substring(start + 1, end);
                if (isValidHexString(hexStr)) {
                    try {
                        int value = Integer.parseInt(hexStr, 16);
                        result.add(value);
                        return end - start + 1; // Consumed {NNNN}
                    } catch (NumberFormatException e) {
                        // Fall through to error case
                    }
                }
            }
        } else {
            // \xNN format
            if (start + 1 < input.length() &&
                    isHexDigit(input.charAt(start)) &&
                    isHexDigit(input.charAt(start + 1))) {
                String hexStr = input.substring(start, start + 2);
                int value = Integer.parseInt(hexStr, 16);
                result.add(value);
                return 2; // Consumed NN
            }
        }

        // Invalid sequence - treat \x as literal characters
        result.add((int) 'x');
        return -2; // Back up to just after '\'
    }

    /**
     * Checks if a string is a valid hexadecimal string.
     */
    private boolean isValidHexString(String str) {
        if (str.isEmpty()) return false;
        for (char c : str.toCharArray()) {
            if (!isHexDigit(c)) return false;
        }
        return true;
    }

    /**
     * Checks if a character is a hexadecimal digit.
     */
    private boolean isHexDigit(char ch) {
        return (ch >= '0' && ch <= '9') ||
                (ch >= 'A' && ch <= 'F') ||
                (ch >= 'a' && ch <= 'f');
    }

    /**
     * Appends a Unicode code point to the StringBuilder.
     */
    private void appendCodePoint(StringBuilder sb, int codePoint) {
        sb.appendCodePoint(codePoint);
    }
}

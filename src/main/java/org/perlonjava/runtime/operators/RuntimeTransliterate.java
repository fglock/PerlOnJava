package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.regex.UnicodeResolver;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.*;

/**
 * The RuntimeTransliterate class implements Perl's tr/// operator, which is used for character
 * transliteration. It provides functionality to compile a transliteration pattern and apply it
 * to a given string.
 */
public class RuntimeTransliterate {

    // Mapping from source characters to target characters
    private Map<Long, Long> translationMap;

    // Set of characters to delete
    private Set<Long> deleteSet;

    // Set of characters that are part of the search pattern
    private Set<Long> searchSet;

    // Modifier flags
    private boolean complement;
    private boolean deleteUnmatched;
    private boolean squashDuplicates;
    private boolean returnOriginal;

    // For complement mode, we need to know the replacement pattern
    private List<Long> replacementChars;

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
        Long lastChar = null;
        boolean lastCharWasTransliterated = false;  // Track if last char came from transliteration

        // For complement mode, we need to track replacement index
        Map<Long, Long> complementMap = new HashMap<>();
        int replacementIndex = 0;

        for (int i = 0; i < input.length();) {
            // PerlUtfString also recognizes the internal representation used for
            // surrogate scalars.  Treat it as one character; iterating Java UTF-16
            // units here turns \x{d800} into eight unrelated characters.
            PerlUtfString.PerlStep step = PerlUtfString.readOnePerlLogical(input, i);
            long codePoint = step.codePoint();
            i = step.nextJavaIndex();

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
                        lastCharWasTransliterated = false;
                    } else if (replacementChars.isEmpty()) {
                        // Empty replacement, non-delete mode - keep character as is
                        if (!squashDuplicates || lastChar == null || !lastCharWasTransliterated || lastChar != codePoint) {
                            appendCodePoint(result, codePoint);
                            lastCharWasTransliterated = true;
                        }
                        lastChar = codePoint;
                    } else {
                        Long mappedChar = null;

                        // Check if this is the common case of search range 0x00-0xFF
                        if (isRange0x00_0xFF(searchSet)) {
                            // Calculate position relative to first char after range
                            long position = codePoint - 0x100;
                            if (position >= 0) {
                                // Use position as index with wraparound
                                int index = (int) (position % replacementChars.size());
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
                                    lastCharWasTransliterated = false;
                                    continue;  // Skip this character (delete it)
                                } else {
                                    // Use last replacement character
                                    mappedChar = replacementChars.get(replacementChars.size() - 1);
                                    complementMap.put(codePoint, mappedChar);
                                }
                            }
                        }

                        if (!squashDuplicates || lastChar == null || !lastCharWasTransliterated || !lastChar.equals(mappedChar)) {
                            appendCodePoint(result, mappedChar);
                            lastChar = mappedChar;
                            lastCharWasTransliterated = true;
                        }
                    }
                } else {
                    // Normal mode handling
                    if (deleteSet.contains(codePoint)) {
                        // Character should be deleted - DON'T change lastChar!
                        // We need to preserve it for squashing logic
                    } else if (translationMap.containsKey(codePoint)) {
                        long mappedChar = translationMap.get(codePoint);
                        // Handle squash duplicates - only squash if the last char was also transliterated
                        if (!squashDuplicates || lastChar == null || !lastCharWasTransliterated || !lastChar.equals(mappedChar)) {
                            appendCodePoint(result, mappedChar);
                            lastChar = mappedChar;
                            lastCharWasTransliterated = true;
                        }
                    } else {
                        // No mapping found (shouldn't happen if compilation is correct)
                        appendCodePoint(result, codePoint);
                        lastChar = codePoint;
                        lastCharWasTransliterated = false;
                    }
                }
            } else {
                // Character not matched - keep as is
                appendCodePoint(result, codePoint);
                lastChar = codePoint;
                lastCharWasTransliterated = false;
            }
        }

        String resultString = result.toString();

        // Handle the /r modifier - return the transliterated string without modifying original
        if (returnOriginal) {
            if (ctx == RuntimeContextType.VOID) {
                Warnings.emitCategoryWarning(
                        "void",
                        "Useless use of non-destructive transliteration (tr///r)");
            }
            RuntimeScalar rv = new RuntimeScalar(resultString);
            // Preserve BYTE_STRING type from input
            if (originalString.type == RuntimeScalarType.BYTE_STRING) {
                rv.type = RuntimeScalarType.BYTE_STRING;
            }
            return rv;
        }

        // Identity operations count without assigning: assignment would vivify
        // missing elements and destroy references even though no change is possible.
        // Conversely a modifying operation assigns even when nothing matched.
        if (modifiesTarget()) {
            // Preserve BYTE_STRING type: tr/// on a byte string should produce a byte string
            boolean wasByteString = originalString.type == RuntimeScalarType.BYTE_STRING;
            originalString.set(resultString);
            if (wasByteString) {
                originalString.type = RuntimeScalarType.BYTE_STRING;
            }
        }

        // Return the count of matched characters
        return new RuntimeScalar(count);
    }

    /** Whether this compiled operation requires a writable target. */
    public boolean modifiesTarget() {
        if (returnOriginal) return false;
        if (complement) {
            return squashDuplicates || deleteUnmatched || !replacementChars.isEmpty();
        }
        if (!deleteSet.isEmpty()) return true;
        if (squashDuplicates && !searchSet.isEmpty()) return true;
        for (Map.Entry<Long, Long> mapping : translationMap.entrySet()) {
            if (!mapping.getKey().equals(mapping.getValue())) return true;
        }
        return false;
    }

    private boolean isRange0x00_0xFF(Set<Long> searchSet) {
        // Check if searchSet contains exactly the range 0x00-0xFF
        if (searchSet.size() != 256) return false;
        for (int i = 0; i <= 0xFF; i++) {
            if (!searchSet.contains((long) i)) return false;
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
        List<Long> searchChars = expandRangesAndEscapes(search);
        List<Long> replaceChars = expandRangesAndEscapes(replace);

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
    private void setupNormalMapping(List<Long> searchChars, List<Long> replaceChars) {
        int searchLen = searchChars.size();
        int replaceLen = replaceChars.size();

        // Debug: Track mapping index for proper assignment
        int mappingIndex = 0;

        for (int i = 0; i < searchLen; i++) {
            long searchChar = searchChars.get(i);

            // Skip if already mapped (character appeared earlier in search pattern)
            if (translationMap.containsKey(searchChar) || deleteSet.contains(searchChar)) {
                continue;
            }

            if (mappingIndex < replaceLen) {
                // Direct mapping using the mapping index, not i
                translationMap.put(searchChar, replaceChars.get(mappingIndex));
                mappingIndex++;
            } else if (deleteUnmatched) {
                // Delete this character (only when 'd' modifier is present)
                deleteSet.add(searchChar);
            } else if (replaceLen == 0) {
                // Empty replacement: map to itself (count only, don't modify)
                translationMap.put(searchChar, searchChar);
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
    private List<Long> expandRangesAndEscapes(String input) {
        List<Long> expanded = new ArrayList<>();

        int i = 0;
        while (i < input.length()) {
            // Parse the current character
            List<Long> currentChar = new ArrayList<>();
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
                List<Long> endChar = new ArrayList<>();
                int endConsumed = parseCharAt(input, nextPos + 1, endChar);

                if (endConsumed > 0 && !endChar.isEmpty()) {
                    // Check for ambiguous range like "a-z-9"
                    // This happens when the char after the range could itself be part of a range
                    int afterRangePos = nextPos + 1 + endConsumed;
                    if (afterRangePos < input.length() && input.charAt(afterRangePos) == '-') {
                        // Check if there's something after this dash
                        if (afterRangePos + 1 < input.length()) {
                            // We have "X-Y-Z" pattern - this is ambiguous
                            throw new PerlCompilerException("Ambiguous range in transliteration operator");
                        }
                    }

                    // We have a valid range
                    long start = currentChar.get(0);
                    long end = endChar.get(0);

                    // Validate range
                    if (start > end) {
                        String startStr = formatCharForError(start);
                        String endStr = formatCharForError(end);
                        throw new PerlCompilerException("Invalid range \"" + startStr + "-" + endStr +
                                "\" in transliteration operator");
                    }

                    // Add the range
                    for (long c = start; c <= end; c++) {
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
    private String formatCharForError(long codePoint) {
        // Check if the character is printable ASCII or a common printable character
        // Basic printable ASCII range (excluding control characters)
        if (codePoint >= 0x20 && codePoint <= 0x7E) {
            return new String(Character.toChars((int) codePoint));
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
    private int parseCharAt(String input, int pos, List<Long> result) {
        if (pos >= input.length()) {
            return 0;
        }

        char ch = input.charAt(pos);

        if (ch == '\\' && pos + 1 < input.length()) {
            char next = input.charAt(pos + 1);
            switch (next) {
                case 'n':
                    result.add((long) '\n');
                    return 2;
                case 't':
                    result.add((long) '\t');
                    return 2;
                case 'r':
                    result.add((long) '\r');
                    return 2;
                case 'f':
                    result.add((long) '\f');
                    return 2;
                case 'b':
                    result.add((long) '\b');
                    return 2;
                case 'a':
                    result.add(0x07L);
                    return 2; // Bell character
                case 'e':
                    result.add(0x1BL);
                    return 2; // Escape character
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                    // Handle octal escape sequences
                    return 1 + parseOctalSequence(input, pos + 1, result);
                case 'x':
                    return 2 + parseHexSequence(input, pos + 2, result);
                case 'c':
                    // Transliteration source keeps escape syntax intact so
                    // ranges can be expanded here. Perl's \cX is X xor 0x40.
                    if (pos + 2 < input.length()) {
                        result.add((long) (input.charAt(pos + 2) ^ 0x40));
                        return 3;
                    }
                    result.add((long) 'c');
                    return 2;
                case '-':
                    // Escaped dash
                    result.add((long) '-');
                    return 2;
                case 'N':
                    if (pos + 2 < input.length() && input.charAt(pos + 2) == '{') {
                        int closePos = input.indexOf('}', pos + 3);
                        if (closePos > pos + 3) {
                            String content = input.substring(pos + 3, closePos).trim();

                            // Check for empty character name
                            if (content.isEmpty()) {
                                throw new RuntimeException("Unknown charname ''");
                            }

                            // Try to resolve the Unicode character name
                            try {
                                int codePoint = UnicodeResolver.getCodePointFromName(content);
                                result.add((long) codePoint);
                                return closePos - pos + 1;
                            } catch (IllegalArgumentException e) {
                                // Check if it's a named sequence (multi-character)
                                // Named sequences are not allowed in tr///
                                String errorMsg = e.getMessage();
                                if (errorMsg != null && errorMsg.contains("named sequence")) {
                                    throw new RuntimeException("\\" + "N{" + content +
                                            "} must not be a named sequence in transliteration operator");
                                }
                                // For any other error (invalid or unknown name), also reject as named sequence
                                // because ICU4J returns -1 for both cases and we can't distinguish them easily
                                // Perl 5 gives a specific error for named sequences, but we'll be conservative
                                throw new RuntimeException("\\" + "N{" + content +
                                        "} must not be a named sequence in transliteration operator");
                            }
                        } else if (closePos == pos + 3) {
                            // Empty \N{} - this is the case where closePos is immediately after {
                            throw new RuntimeException("Unknown charname ''");
                        }
                    }
                    result.add((long) 'N');
                    return 2;
                default:
                    // Other escaped character
                    result.add((long) next);
                    return 2;
            }
        } else {
            // Regular character
            PerlUtfString.PerlStep step = PerlUtfString.readOnePerlLogical(input, pos);
            result.add(step.codePoint());
            return step.nextJavaIndex() - pos;
        }
    }

    /**
     * Parses octal escape sequences (\0, \77, \377, etc.).
     * Returns the number of characters consumed (not including the initial backslash).
     */
    private int parseOctalSequence(String input, int start, List<Long> result) {
        int value = 0;
        int digits = 0;
        int pos = start;

        // Parse up to 3 octal digits
        while (pos < input.length() && digits < 3) {
            char ch = input.charAt(pos);
            if (ch >= '0' && ch <= '7') {
                value = value * 8 + (ch - '0');
                digits++;
                pos++;
            } else {
                break;
            }
        }

        // In Perl, octal values are capped at 0377 (255 decimal)
        // to maintain compatibility with older versions
        if (value > 0377) {
            value = 0377;
        }

        result.add((long) value);
        return digits;
    }

    /**
     * Parses hexadecimal escape sequences (\xNN or \x{NNNN}).
     * Returns the number of additional characters consumed (after \x).
     */
    private int parseHexSequence(String input, int start, List<Long> result) {
        if (start >= input.length()) {
            result.add((long) 'x'); // Invalid sequence, treat as literal 'x'
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
                        result.add((long) value);
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
                result.add((long) value);
                return 2; // Consumed NN
            }
        }

        // Invalid sequence - treat \x as literal characters
        result.add((long) 'x');
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
    private void appendCodePoint(StringBuilder sb, long codePoint) {
        if (codePoint > 0x10FFFFL) {
            sb.append(PerlUtfString.encodeBeyondUnicode(codePoint));
        } else if (codePoint >= 0xD800L && codePoint <= 0xDFFFL) {
            sb.append(PerlUtfString.encodeSurrogate(codePoint));
        } else {
            sb.appendCodePoint((int) codePoint);
        }
    }
}

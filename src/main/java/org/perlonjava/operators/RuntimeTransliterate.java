package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.RuntimeScalar;

/**
 * The RuntimeTransliterate class implements Perl's tr/// operator, which is used for character
 * transliteration. It provides functionality to compile a transliteration pattern and apply it
 * to a given string.
 */
public class RuntimeTransliterate {

    // Arrays and flags used for transliteration
    private char[] translationMap;
    private boolean[] usedChars;
    private boolean[] deleteChars;
    private boolean complement;
    private boolean deleteUnmatched;
    private boolean squashDuplicates;
    private boolean returnOriginal;

    /**
     * Compiles a RuntimeTransliterate object from a pattern string with optional modifiers.
     *
     * @param search    The pattern string to search for
     * @param replace   The replacement string
     * @param modifiers Modifiers for the pattern (e.g., complement, delete, squash)
     * @return A compiled RuntimeTransliterate object
     */
    public static RuntimeTransliterate compile(RuntimeScalar search, RuntimeScalar replace, RuntimeScalar modifiers) {
        // TODO: Cache the compilation
        RuntimeTransliterate transliterate = new RuntimeTransliterate();
        transliterate.compileTransliteration(search.toString(), replace.toString(), modifiers.toString());
        return transliterate;
    }

    /**
     * Applies the transliteration pattern to the given string.
     *
     * @param originalString The original string to be transliterated
     * @param ctx The runtime context
     * @return A new RuntimeScalar containing the transliterated string or count
     */
    public RuntimeScalar transliterate(RuntimeScalar originalString, int ctx) {
        String input = originalString.toString();
        StringBuilder result = new StringBuilder();
        boolean lastCharAdded = false;
        int count = 0;  // Track count of transliterated characters

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch < 256 && deleteChars[ch]) {
                lastCharAdded = false;
                count++;  // Count deleted characters
            } else if (ch < 256 && usedChars[ch]) {
                char mappedChar = translationMap[ch];
                if (!squashDuplicates || result.length() == 0 || result.charAt(result.length() - 1) != mappedChar) {
                    result.append(mappedChar);
                    lastCharAdded = true;
                }
                // Always count characters that match the search pattern
                count++;
            } else {
                result.append(ch);
                lastCharAdded = false;
            }
        }

        String resultString = result.toString();

        // Handle the /r modifier - return the transliterated string without modifying original
        if (returnOriginal) {
            return new RuntimeScalar(resultString);
        }

        // Modify the original string
        originalString.set(resultString);

        // Always return the count (unless /r modifier is used)
        return new RuntimeScalar(count);
    }

    /**
     * Compiles the transliteration pattern and replacement strings with the given modifiers.
     *
     * @param search    The pattern string to search for
     * @param replace   The replacement string
     * @param modifiers Modifiers for the pattern (e.g., complement, delete, squash)
     */
    public void compileTransliteration(String search, String replace, String modifiers) {
        complement = modifiers.contains("c");
        deleteUnmatched = modifiers.contains("d");
        squashDuplicates = modifiers.contains("s");
        returnOriginal = modifiers.contains("r");

        // Parse escape sequences first, then expand ranges
        String expandedSearch = expandRanges(search);
        String expandedReplace = expandRanges(replace);

        translationMap = new char[256];
        usedChars = new boolean[256];
        deleteChars = new boolean[256];

        for (int i = 0; i < 256; i++) {
            translationMap[i] = (char) i;
            usedChars[i] = false;
            deleteChars[i] = false;
        }

        if (complement) {
            complementTranslationMap(translationMap, usedChars, expandedSearch, expandedReplace);
        } else {
            populateTranslationMap(translationMap, usedChars, expandedSearch, expandedReplace);
        }
    }

    /**
     * Expands character ranges like a-z, A-Z, 0-9.
     *
     * @param input The input string possibly containing character ranges
     * @return The string with ranges expanded
     */
    private String expandRanges(String input) {
        StringBuilder expanded = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            if (i > 0 && i < input.length() - 1 && input.charAt(i) == '-') {
                char start = input.charAt(i - 1);
                char end = input.charAt(i + 1);

                // Check if this is a valid range
                if (start < end) {
                    // We already added start, so begin from start + 1
                    for (char ch = (char)(start + 1); ch <= end; ch++) {
                        expanded.append(ch);
                    }
                    i++; // Skip the end character as we've already processed it
                    continue;
                }
            }

            expanded.append(input.charAt(i));
        }

        return expanded.toString();
    }

    /**
     * Complements the translation map based on the search and replace strings.
     *
     * @param translationMap The translation map to populate
     * @param usedChars      The array indicating which characters are used
     * @param search         The search string
     * @param replace        The replacement string
     */
    private void complementTranslationMap(char[] translationMap, boolean[] usedChars, String search, String replace) {
        boolean[] complementSet = new boolean[256];
        for (int i = 0; i < search.length(); i++) {
            char ch = search.charAt(i);
            if (ch < 256) {
                complementSet[ch] = true;
            }
        }

        int replaceIndex = 0;
        for (int i = 0; i < 256; i++) {
            if (!complementSet[i]) {
                if (replaceIndex < replace.length()) {
                    translationMap[i] = replace.charAt(replaceIndex);
                    usedChars[i] = true;
                    replaceIndex++;
                } else {
                    if (deleteUnmatched) {
                        deleteChars[i] = true;
                        usedChars[i] = true;
                    } else if (!replace.isEmpty()) {
                        translationMap[i] = replace.charAt(replace.length() - 1);
                        usedChars[i] = true;
                    }
                }
            }
        }
    }

    /**
     * Populates the translation map based on the search and replace strings.
     *
     * @param translationMap The translation map to populate
     * @param usedChars      The array indicating which characters are used
     * @param search         The search string
     * @param replace        The replacement string
     */
    private void populateTranslationMap(char[] translationMap, boolean[] usedChars, String search, String replace) {
        int minLength = Math.min(search.length(), replace.length());

        // First pass: map characters that have replacements
        for (int i = 0; i < minLength; i++) {
            char searchChar = search.charAt(i);
            if (searchChar < 256) {
                // Only map if not already mapped (first occurrence wins)
                if (!usedChars[searchChar]) {
                    translationMap[searchChar] = replace.charAt(i);
                    usedChars[searchChar] = true;
                }
            }
        }

        // Second pass: handle remaining characters in search string
        for (int i = minLength; i < search.length(); i++) {
            char searchChar = search.charAt(i);
            if (searchChar < 256 && !usedChars[searchChar]) {
                if (deleteUnmatched) {
                    deleteChars[searchChar] = true;
                    usedChars[searchChar] = true;
                } else if (!replace.isEmpty()) {
                    // Map to the last character in replace string
                    translationMap[searchChar] = replace.charAt(replace.length() - 1);
                    usedChars[searchChar] = true;
                } else {
                    // Empty replacement - map to self
                    translationMap[searchChar] = searchChar;
                    usedChars[searchChar] = true;
                }
            }
        }
    }

    /**
     * Checks if a character is a hexadecimal digit.
     *
     * @param ch The character to check
     * @return True if the character is a hexadecimal digit, false otherwise
     */
    private boolean isHexDigit(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f');
    }
}

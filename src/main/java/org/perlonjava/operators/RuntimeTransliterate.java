package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The RuntimeTransliterate class implements Perl's tr/// operator, which is used for character
 * transliteration. It provides functionality to compile a transliteration pattern and apply it
 * to a given string.
 */
public class RuntimeTransliterate {

    // Maps and sets used for transliteration (now supports full Unicode)
    private Map<Character, Character> translationMap;
    private Set<Character> usedChars;
    private Set<Character> deleteChars;
    private Set<Character> inSearchSet;  // Track which chars are in the original search set
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
     * @param ctx            The runtime context
     * @return A new RuntimeScalar containing the transliterated string or count
     */
    public RuntimeScalar transliterate(RuntimeScalar originalString, int ctx) {
        String input = originalString.toString();
        StringBuilder result = new StringBuilder();
        char lastChar = '\0';
        boolean lastCharWasFromComplement = false;
        int count = 0;  // Track count of transliterated characters

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (deleteChars.contains(ch)) {
                count++;  // Count deleted characters
                lastChar = '\0';
                lastCharWasFromComplement = false;
            } else if (usedChars.contains(ch)) {
                char mappedChar = translationMap.getOrDefault(ch, ch);
                boolean isFromComplement = complement && !inSearchSet.contains(ch);

                // Apply squashing logic
                boolean shouldSquash = false;
                if (squashDuplicates && result.length() > 0 && lastChar == mappedChar) {
                    // In complement mode, only squash if both current and last char are from complement
                    if (complement) {
                        shouldSquash = isFromComplement && lastCharWasFromComplement;
                    } else {
                        // In normal mode, squash all duplicates
                        shouldSquash = true;
                    }
                }

                if (!shouldSquash) {
                    result.append(mappedChar);
                }

                // Always count characters that match the search pattern
                count++;
                lastChar = mappedChar;
                lastCharWasFromComplement = isFromComplement;
            } else {
                result.append(ch);
                lastChar = ch;
                lastCharWasFromComplement = false;
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

        translationMap = new HashMap<>();
        usedChars = new HashSet<>();
        deleteChars = new HashSet<>();
        inSearchSet = new HashSet<>();

        // Mark characters in the search set
        for (int i = 0; i < expandedSearch.length(); i++) {
            char ch = expandedSearch.charAt(i);
            inSearchSet.add(ch);
        }

        if (complement) {
            complementTranslationMap(expandedSearch, expandedReplace);
        } else {
            populateTranslationMap(expandedSearch, expandedReplace);
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
                    for (char ch = (char) (start + 1); ch <= end; ch++) {
                        expanded.append(ch);
                    }
                    i++; // Skip the end character as we've already processed it
                    continue;
                } else if (start > end) {
                    // Invalid range - throw exception
                    String startHex = String.format("\\x{%04X}", (int) start);
                    String endHex = String.format("\\x{%04X}", (int) end);
                    throw new PerlCompilerException(
                            "Invalid range \"" + startHex + "-" + endHex + "\" in transliteration operator"
                    );
                }
                // If start == end, fall through and treat as literal characters
            }

            expanded.append(input.charAt(i));
        }

        return expanded.toString();
    }

    /**
     * Complements the translation map based on the search and replace strings.
     *
     * @param search         The search string
     * @param replace        The replacement string
     */
    private void complementTranslationMap(String search, String replace) {
        Set<Character> searchSet = new HashSet<>();
        for (int i = 0; i < search.length(); i++) {
            searchSet.add(search.charAt(i));
        }

        // We need to iterate through all characters that might appear in the input
        // For now, we'll handle the common case of characters up to U+FFFF
        int replaceIndex = 0;

        for (int codePoint = 0; codePoint <= 0xFFFF; codePoint++) {
            char ch = (char) codePoint;

            if (!searchSet.contains(ch)) {
                // This character is in the complement set
                if (replace.isEmpty()) {
                    // Special case: complement with empty replacement
                    if (deleteUnmatched) {
                        // With 'd' modifier, delete complement characters
                        deleteChars.add(ch);
                        usedChars.add(ch);
                    } else {
                        // Without 'd' modifier, map to themselves (for squashing)
                        usedChars.add(ch);
                        translationMap.put(ch, ch);
                    }
                } else {
                    // Map to replacement characters
                    if (replaceIndex < replace.length()) {
                        translationMap.put(ch, replace.charAt(replaceIndex));
                        usedChars.add(ch);
                        replaceIndex++;
                    } else {
                        if (deleteUnmatched) {
                            deleteChars.add(ch);
                            usedChars.add(ch);
                        } else {
                            translationMap.put(ch, replace.charAt(replace.length() - 1));
                            usedChars.add(ch);
                        }
                    }
                }
            }
        }
    }

    /**
     * Populates the translation map based on the search and replace strings.
     *
     * @param search         The search string
     * @param replace        The replacement string
     */
    private void populateTranslationMap(String search, String replace) {
        int minLength = Math.min(search.length(), replace.length());

        // First pass: map characters that have replacements
        for (int i = 0; i < minLength; i++) {
            char searchChar = search.charAt(i);
            // Only map if not already mapped (first occurrence wins)
            if (!usedChars.contains(searchChar)) {
                translationMap.put(searchChar, replace.charAt(i));
                usedChars.add(searchChar);
            }
        }

        // Second pass: handle remaining characters in search string
        for (int i = minLength; i < search.length(); i++) {
            char searchChar = search.charAt(i);
            if (!usedChars.contains(searchChar)) {
                if (deleteUnmatched) {
                    deleteChars.add(searchChar);
                    usedChars.add(searchChar);
                } else if (!replace.isEmpty()) {
                    // Map to the last character in replace string
                    translationMap.put(searchChar, replace.charAt(replace.length() - 1));
                    usedChars.add(searchChar);
                } else {
                    // Empty replacement - map to self
                    translationMap.put(searchChar, searchChar);
                    usedChars.add(searchChar);
                }
            }
        }
    }
}
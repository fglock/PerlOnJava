package org.perlonjava.runtime;

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
     * @return A new RuntimeScalar containing the transliterated string
     */
    public RuntimeScalar transliterate(RuntimeScalar originalString) {
        String input = originalString.toString();
        StringBuilder result = new StringBuilder();
        boolean lastCharAdded = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (deleteChars[ch]) {
                lastCharAdded = false;
            } else if (ch < 256 && usedChars[ch]) {
                char mappedChar = translationMap[ch];
                if (!squashDuplicates || result.length() == 0 || result.charAt(result.length() - 1) != mappedChar) {
                    result.append(mappedChar);
                    lastCharAdded = true;
                }
            } else {
                result.append(ch);
                lastCharAdded = false;
            }
        }

        String resultString = result.toString();
        if (!returnOriginal) {
            originalString.set(resultString);
        }
        return new RuntimeScalar(resultString);
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

        String expandedSearch = expandRangesAndEscapes(search);
        String expandedReplace = expandRangesAndEscapes(replace);

        translationMap = new char[256];
        usedChars = new boolean[256];
        deleteChars = new boolean[256];

        for (int i = 0; i < 256; i++) {
            translationMap[i] = (char) i;
            usedChars[i] = false;
        }

        if (complement) {
            complementTranslationMap(translationMap, usedChars, expandedSearch, expandedReplace);
        } else {
            populateTranslationMap(translationMap, usedChars, expandedSearch, expandedReplace);
        }
    }

    /**
     * Expands character ranges and escape sequences in the input string.
     *
     * @param input The input string containing ranges and escapes
     * @return The expanded string
     */
    private String expandRangesAndEscapes(String input) {
        StringBuilder expanded = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (i + 2 < input.length() && input.charAt(i + 1) == '-') {
                char start = ch;
                char end = input.charAt(i + 2);
                i += 2;
                if (start <= end) {
                    for (char c = start; c <= end; c++) {
                        expanded.append(c);
                    }
                } else {
                    for (char c = start; c >= end; c--) {
                        expanded.append(c);
                    }
                }
            } else if (ch == '\\' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                switch (next) {
                    case 'n':
                        expanded.append('\n');
                        break;
                    case 't':
                        expanded.append('\t');
                        break;
                    case 'r':
                        expanded.append('\r');
                        break;
                    case 'f':
                        expanded.append('\f');
                        break;
                    case 'x':
                        if (i + 3 < input.length() && isHexDigit(input.charAt(i + 2)) && isHexDigit(input.charAt(i + 3))) {
                            int hexValue = Integer.parseInt(input.substring(i + 2, i + 4), 16);
                            expanded.append((char) hexValue);
                            i += 3;
                        }
                        break;
                    default:
                        expanded.append(next);
                        break;
                }
                i++;
            } else {
                expanded.append(ch);
            }
        }
        return expanded.toString();
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
            complementSet[search.charAt(i)] = true;
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
        for (int i = 0; i < minLength; i++) {
            translationMap[search.charAt(i)] = replace.charAt(i);
            usedChars[search.charAt(i)] = true;
        }

        for (int i = minLength; i < search.length(); i++) {
            if (deleteUnmatched) {
                deleteChars[search.charAt(i)] = true;
            } else if (!replace.isEmpty()) {
                translationMap[search.charAt(i)] = replace.charAt(replace.length() - 1);
                usedChars[search.charAt(i)] = true;
            }
        }
    }
}

package org.perlonjava.runtime;

/**
 * RuntimeTransliterate class to implement Perl's tr// operator
 */
public class RuntimeTransliterate implements RuntimeScalarReference {

    private boolean complement;
    private boolean deleteUnmatched;
    private boolean squashDuplicates;
    private boolean returnOriginal;
    private char[] translationMap;
    private boolean[] usedChars;

    /**
     * Creates a RuntimeTransliterate object from a pattern string with optional modifiers.
     *
     * @param search    The  pattern string
     * @param replace   The replacement string
     * @param modifiers Modifiers for the pattern
     * @return A RuntimeTransliterate object.
     */
    public static RuntimeTransliterate compile(String search, String replace, String modifiers) {
        RuntimeTransliterate transliterate = new RuntimeTransliterate();
        transliterate.compileTransliteration(search, replace, modifiers);
        return transliterate;
    }

    /**
     * Creates a Perl "qr"-like object from a pattern string with optional modifiers.
     *
     * @param search    The  pattern string
     * @param replace   The replacement string
     * @param modifiers Modifiers for the pattern
     * @return A RuntimeScalar.
     */
    public static RuntimeScalar getTransliterate(RuntimeScalar search, RuntimeScalar replace, RuntimeScalar modifiers) {
        RuntimeTransliterate transliterate = new RuntimeTransliterate();
        // TODO
        // return new RuntimeScalar(
        //        transliterate.compile(search.toString(), replace.toString(), modifiers.toString()));
        return new RuntimeScalar();
    }

    /**
     * Transliterates the characters in the string according to the specified search and replacement lists,
     * with support for the /c, /d, /s, and /r modifiers.
     *
     * @param originalString The string containing to be transliterated
     * @return A RuntimeScalar containing the transliterated string, or the original string if /r is used.
     */
    public RuntimeScalar transliterate(RuntimeScalar originalString) {
        String input = originalString.toString();

        // Perform transliteration on the input string
        StringBuilder result = new StringBuilder();
        boolean lastCharAdded = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (usedChars[ch]) {
                char mappedChar = translationMap[ch];

                if (!squashDuplicates || !lastCharAdded || (result.length() > 0 && result.charAt(result.length() - 1) != mappedChar)) {
                    result.append(mappedChar);
                    lastCharAdded = true;
                }
            } else if (!deleteUnmatched) {
                result.append(ch);
                lastCharAdded = false;
            }
        }

        // Return original or modified string based on /r modifier
        return returnOriginal ? new RuntimeScalar(input) : new RuntimeScalar(result.toString());
    }

    private void compileTransliteration(String search, String replace, String modifiers) {
        complement = modifiers.contains("c");
        deleteUnmatched = modifiers.contains("d");
        squashDuplicates = modifiers.contains("s");
        returnOriginal = modifiers.contains("r");

        // Expand search and replace lists
        String expandedSearch = expandRangesAndEscapes(search);
        String expandedReplace = expandRangesAndEscapes(replace);

        // Create translation map
        translationMap = new char[256];
        usedChars = new boolean[256];

        // Initialize translation map to identity mapping
        for (int i = 0; i < 256; i++) {
            translationMap[i] = (char) i;
            usedChars[i] = false;
        }

        // Populate translation map based on expanded search and replace lists
        if (complement) {
            complementTranslationMap(translationMap, usedChars, expandedSearch, expandedReplace);
        } else {
            populateTranslationMap(translationMap, usedChars, expandedSearch, expandedReplace);
        }
    }

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

    private boolean isHexDigit(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f');
    }

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
                }
            }
        }
    }

    private void populateTranslationMap(char[] translationMap, boolean[] usedChars, String search, String replace) {
        int minLength = Math.min(search.length(), replace.length());

        for (int i = 0; i < minLength; i++) {
            translationMap[search.charAt(i)] = replace.charAt(i);
            usedChars[search.charAt(i)] = true;
        }

        // If search list is longer, map remaining search chars to last replace char
        for (int i = minLength; i < search.length(); i++) {
            translationMap[search.charAt(i)] = replace.charAt(replace.length() - 1);
            usedChars[search.charAt(i)] = true;
        }
    }

    @Override
    public String toString() {
        // placeholder
        return "tr///";
    }

    public String toStringRef() {
        return "REF(" + this.hashCode() + ")";
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }

}


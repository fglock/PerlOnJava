package org.perlonjava.runtime;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RuntimeTransliterate class to implement Perl's tr// operator
 */
public class RuntimeTransliterate implements RuntimeScalarReference {

    /**
     * Transliterates the characters in the string according to the specified search and replacement lists,
     * with support for the /c, /d, /s, and /r modifiers.
     *
     * @param search The search string containing the characters to be replaced.
     * @param replace The replacement string containing the characters to replace with.
     * @param modifiers A string containing any combination of 'c', 'd', 's', and 'r' modifiers.
     * @return A RuntimeScalar containing the transliterated string, or the original string if /r is used.
     */
    public RuntimeScalar transliterate(RuntimeScalar originalString, String search, String replace, String modifiers) {
        String input = originalString.toString();
        boolean complement = modifiers.contains("c");
        boolean deleteUnmatched = modifiers.contains("d");
        boolean squashDuplicates = modifiers.contains("s");
        boolean returnOriginal = modifiers.contains("r");

        // Expand search and replace lists
        String expandedSearch = expandRangesAndEscapes(search);
        String expandedReplace = expandRangesAndEscapes(replace);

        // Create translation map
        char[] translationMap = new char[256];
        boolean[] usedChars = new boolean[256];

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

    /**
     * Creates a RuntimeTransliterate object from a pattern string with optional modifiers.
     *
     * @param patternString The regex pattern string with optional modifiers.
     * @param modifiers     Modifiers for the regex pattern (e.g., "i", "g").
     * @return A RuntimeTransliterate object.
     */
    public static RuntimeTransliterate compile(String patternString, String modifiers) {
        // placeholder
        return new RuntimeTransliterate();
    }

    /**
     * Creates a Perl "qr" object from a regex pattern string with optional modifiers.
     * `my $v = qr/abc/i;`
     *
     * @param patternString The  pattern string
     * @param modifiers     Modifiers for the pattern
     * @return A RuntimeScalar.
     */
    public static RuntimeScalar getQuotedRegex(RuntimeScalar patternString, RuntimeScalar modifiers) {
        // placeholder
        RuntimeTransliterate regex = new RuntimeTransliterate();
        return new RuntimeScalar();
    }

    // Internal variant of qr// that includes a `replacement`
    // This is the internal representation of the `tr///` operation
    public static RuntimeScalar getReplacementRegex(RuntimeScalar patternString, RuntimeScalar replacement, RuntimeScalar modifiers) {
        // placeholder
        RuntimeTransliterate regex = new RuntimeTransliterate();
        return new RuntimeScalar();
    }

    /**
     * Applies a Perl "qr" object on a string; returns true/false or a list,
     * and produces side-effects
     * `my $v =~ /$qr/;`
     *
     * @param quotedRegex The regex pattern object, created by getQuotedRegex()
     * @param string      The string to be matched.
     * @param ctx         The context LIST, SCALAR, VOID
     * @return A RuntimeScalar or RuntimeList
     */
    public static RuntimeDataProvider matchRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        // placeholder
        return new RuntimeScalar();
    }

    /**
     * Applies a Perl "s///" substitution on a string.
     * `my $v =~ s/$pattern/$replacement/;`
     *
     * @param quotedRegex The regex pattern object, created by getReplacementRegex()
     * @param string      The string to be modified.
     * @param ctx         The context LIST, SCALAR, VOID
     * @return A RuntimeScalar or RuntimeList
     */
    public static RuntimeBaseEntity replaceRegex(RuntimeScalar quotedRegex, RuntimeScalar string, int ctx) {
        // placeholder
        return quotedRegex;
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


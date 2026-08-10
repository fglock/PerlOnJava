package org.perlonjava.runtime.regex;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.UnicodeSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps Unicode characters to their multi-character case fold equivalents.
 * This is needed because Java's Pattern.UNICODE_CASE flag only handles
 * single-character case folding, not multi-character folds like ß→ss.
 */
public class MultiCharFoldMapper {

    // Map of characters that fold to multiple characters
    // Format: character → fold string
    private static final Map<Integer, String> MULTI_CHAR_FOLDS = new HashMap<>();

    // Reverse map: fold string → List of characters that fold to it
    // Format: fold string → characters
    private static final Map<String, List<Integer>> REVERSE_FOLDS = new HashMap<>();
    private static final Set<Integer> FOLD_COMPONENTS = new HashSet<>();

    static {
        // ICU4J is already the runtime's Unicode source of truth. Derive all
        // full folds instead of maintaining a partial hand-written table.
        UnicodeSet foldCandidates = new UnicodeSet()
                .applyIntPropertyValue(UProperty.CHANGES_WHEN_CASEFOLDED, 1);
        for (String original : foldCandidates) {
            if (original.codePointCount(0, original.length()) != 1) continue;
            int codePoint = original.codePointAt(0);
            String fold = UCharacter.foldCase(original, true);
            if (fold.codePointCount(0, fold.length()) > 1) {
                MULTI_CHAR_FOLDS.put(codePoint, fold);
            }
        }

        // Build reverse map (lowercase versions only for simpler matching)
        for (Map.Entry<Integer, String> entry : MULTI_CHAR_FOLDS.entrySet()) {
            String fold = entry.getValue();
            REVERSE_FOLDS.computeIfAbsent(fold, ignored -> new ArrayList<>()).add(entry.getKey());
            fold.codePoints().forEach(FOLD_COMPONENTS::add);
        }
    }

    /**
     * Check if a character has a multi-character case fold.
     *
     * @param codePoint The Unicode code point to check
     * @return true if this character folds to multiple characters
     */
    public static boolean hasMultiCharFold(int codePoint) {
        return MULTI_CHAR_FOLDS.containsKey(codePoint);
    }

    /**
     * Get the multi-character fold for a character.
     *
     * @param codePoint The Unicode code point
     * @return The folded string, or null if no multi-char fold exists
     */
    public static String getMultiCharFold(int codePoint) {
        return MULTI_CHAR_FOLDS.get(codePoint);
    }

    /**
     * Expand a character with a multi-char fold into a regex alternation.
     * For example: ß → (?:ß|ss|SS|Ss|sS)
     *
     * @param codePoint The Unicode code point
     * @return A regex pattern that matches all case variants, or null if no multi-char fold
     */
    public static String expandToAlternation(int codePoint) {
        String fold = MULTI_CHAR_FOLDS.get(codePoint);
        if (fold == null) {
            return null;
        }

        // Build all case variations
        StringBuilder sb = new StringBuilder("(?:");
        String original = new String(Character.toChars(codePoint));
        sb.append(Pattern.quote(original));
        sb.append("|");

        // Add the basic fold
        sb.append(Pattern.quote(fold));

        // Add case variations of the fold (if it's ASCII)
        if (fold.chars().allMatch(c -> c >= 'a' && c <= 'z')) {
            // Generate all case combinations for lowercase ASCII
            int len = fold.length();
            for (int mask = 1; mask < (1 << len); mask++) {
                sb.append("|");
                for (int i = 0; i < len; i++) {
                    char c = fold.charAt(i);
                    if ((mask & (1 << i)) != 0) {
                        sb.append(Character.toUpperCase(c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }

        sb.append(")");
        return sb.toString();
    }

    /**
     * Check if a string has a reverse fold (i.e., a character that folds to this string).
     * For example: "ss" has a reverse fold to ß
     *
     * @param str The string to check (will be lowercased)
     * @return true if a character folds to this string
     */
    public static boolean hasReverseFold(String str) {
        return REVERSE_FOLDS.containsKey(UCharacter.foldCase(str, true));
    }

    /**
     * Get the characters that fold to this string.
     * For example: "ss" → [ß, ẞ]
     *
     * @param str The string to look up (will be lowercased)
     * @return The character code points, or an empty list if no reverse fold exists
     */
    public static List<Integer> getReverseFolds(String str) {
        List<Integer> folds = REVERSE_FOLDS.get(UCharacter.foldCase(str, true));
        return folds == null ? List.of() : folds;
    }

    /** Whether a literal code point can participate in a reverse multi-char fold. */
    public static boolean isFoldComponent(int codePoint) {
        if (FOLD_COMPONENTS.contains(codePoint)) {
            return true;
        }

        // Escaped literals can themselves have a simple fold into a component
        // of a full fold.  For example, U+017F (long s) folds to "s", and two
        // escaped long-s characters must therefore be eligible for the same
        // reverse expansion as "ss" (including U+00DF).
        String original = new String(Character.toChars(codePoint));
        String folded = UCharacter.foldCase(original, true);
        return folded.codePointCount(0, folded.length()) == 1
                && FOLD_COMPONENTS.contains(folded.codePointAt(0));
    }
}

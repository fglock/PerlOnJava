package org.perlonjava.regex;

import java.util.HashMap;
import java.util.Map;
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
    // Format: fold string → character
    private static final Map<String, Integer> REVERSE_FOLDS = new HashMap<>();
    
    static {
        // Latin
        MULTI_CHAR_FOLDS.put(0x00DF, "ss");    // ß → ss
        MULTI_CHAR_FOLDS.put(0x0130, "i\u0307"); // İ → i̇ (i + combining dot above)
        MULTI_CHAR_FOLDS.put(0x0149, "\u02BCn"); // ŉ → ʼn
        MULTI_CHAR_FOLDS.put(0x01F0, "j\u030C"); // ǰ → ǰ
        MULTI_CHAR_FOLDS.put(0x1E96, "h\u0331"); // ẖ → ẖ
        MULTI_CHAR_FOLDS.put(0x1E97, "t\u0308"); // ẗ → ẗ
        MULTI_CHAR_FOLDS.put(0x1E98, "w\u030A"); // ẘ → ẘ
        MULTI_CHAR_FOLDS.put(0x1E99, "y\u030A"); // ẙ → ẙ
        MULTI_CHAR_FOLDS.put(0x1E9A, "a\u02BE"); // ẚ → aʾ
        MULTI_CHAR_FOLDS.put(0x1E9B, "\u017Fs"); // ẛ → ẛ
        MULTI_CHAR_FOLDS.put(0x1F50, "\u03C5\u0313"); // ὐ → ὐ
        
        // Greek
        MULTI_CHAR_FOLDS.put(0x0390, "\u03B9\u0308\u0301"); // ΐ → ΐ
        MULTI_CHAR_FOLDS.put(0x03B0, "\u03C5\u0308\u0301"); // ΰ → ΰ
        
        // Armenian
        MULTI_CHAR_FOLDS.put(0x0587, "\u0565\u0582"); // և → եւ
        
        // Latin ligatures
        MULTI_CHAR_FOLDS.put(0xFB00, "ff");    // ﬀ → ff
        MULTI_CHAR_FOLDS.put(0xFB01, "fi");    // ﬁ → fi
        MULTI_CHAR_FOLDS.put(0xFB02, "fl");    // ﬂ → fl
        MULTI_CHAR_FOLDS.put(0xFB03, "ffi");   // ﬃ → ffi
        MULTI_CHAR_FOLDS.put(0xFB04, "ffl");   // ﬄ → ffl
        MULTI_CHAR_FOLDS.put(0xFB05, "\u017Ft"); // ﬅ → ſt
        MULTI_CHAR_FOLDS.put(0xFB06, "st");    // ﬆ → st
        
        // Armenian ligatures
        MULTI_CHAR_FOLDS.put(0xFB13, "\u0574\u0576"); // ﬓ → մն
        MULTI_CHAR_FOLDS.put(0xFB14, "\u0574\u0565"); // ﬔ → մե
        MULTI_CHAR_FOLDS.put(0xFB15, "\u0574\u056B"); // ﬕ → մի
        MULTI_CHAR_FOLDS.put(0xFB16, "\u057E\u0576"); // ﬖ → վն
        MULTI_CHAR_FOLDS.put(0xFB17, "\u0574\u056D"); // ﬗ → մխ
        
        // Build reverse map (lowercase versions only for simpler matching)
        for (Map.Entry<Integer, String> entry : MULTI_CHAR_FOLDS.entrySet()) {
            String fold = entry.getValue().toLowerCase();
            REVERSE_FOLDS.put(fold, entry.getKey());
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
        return REVERSE_FOLDS.containsKey(str.toLowerCase());
    }
    
    /**
     * Get the character that folds to this string.
     * For example: "ss" → ß
     * 
     * @param str The string to look up (will be lowercased)
     * @return The character code point, or null if no reverse fold exists
     */
    public static Integer getReverseFold(String str) {
        return REVERSE_FOLDS.get(str.toLowerCase());
    }
}



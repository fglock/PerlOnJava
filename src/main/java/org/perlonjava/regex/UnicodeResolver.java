package org.perlonjava.regex;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.UnicodeSet;

public class UnicodeResolver {
    /**
     * Retrieves the Unicode code point for a given character name.
     *
     * @param name The name of the Unicode character.
     * @return The Unicode code point.
     * @throws IllegalArgumentException If the name is invalid or not found.
     */
    public static int getCodePointFromName(String name) {
        int codePoint;
        if (name.startsWith("U+")) {
            try {
                codePoint = Integer.parseInt(name.substring(2), 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid Unicode code point: " + name);
            }
        } else {
            codePoint = UCharacter.getCharFromName(name);
            if (codePoint == -1) {
                throw new IllegalArgumentException("Invalid Unicode character name: " + name);
            }
        }
        return codePoint;
    }

    public static String translateUnicodeProperty(String property, boolean negated) {
        try {
            // Special cases
            if (property.equals("XPosixSpace")) {
                return (negated ? "\\P{" : "\\p{") + "IsWhite_Space}";
            }

            if (property.equals("_Perl_IDStart")) {
                String pattern = "\\p{L}\\p{Nl}_";
                return negated ? "[^" + pattern + "]" : "[" + pattern + "]";
            }

            if (property.equals("_Perl_IDCont")) {
                String pattern = "\\p{L}\\p{Nl}\\p{Nd}\\p{Mn}\\p{Mc}\\p{Pc}";
                return negated ? "[^" + pattern + "]" : "[" + pattern + "]";
            }

            // Remove prefixes
            for (String prefix : new String[]{"Script=", "Block=", "In=", "Is="}) {
                if (property.startsWith(prefix)) {
                    property = property.substring(prefix.length());
                    break;
                }
            }

            // Single character properties
            if (property.length() == 1) {
                return (negated ? "\\P{" : "\\p{") + property + "}";
            }

            // Combined properties
            if (property.contains(";")) {
                StringBuilder result = new StringBuilder(negated ? "[^" : "[");
                for (String part : property.split(";")) {
                    result.append(translateUnicodeProperty(part, false));
                }
                return result.append("]").toString();
            }

            // Standard Unicode properties
            UnicodeSet unicodeSet = new UnicodeSet();
            if (isBlockProperty(property)) {
                unicodeSet.applyPropertyAlias("Block", property);
            } else {
                unicodeSet.applyPropertyAlias(property, "");
            }

            String pattern = unicodeSet.toPattern(false);
            return negated ? "[^" + pattern + "]" : "[" + pattern + "]";

        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid or unsupported Unicode property: " + property, e);
        }
    }

    // Helper method to check if a property is a block property
    private static boolean isBlockProperty(String property) {
        // List of known block properties (can be expanded as needed)
        String[] blockProperties = {
                "CJK_Unified_Ideographs", "Basic_Latin", "CJK_Symbols_and_Punctuation", "Hiragana", "Katakana"
        };
        for (String block : blockProperties) {
            if (property.equalsIgnoreCase(block)) {
                return true;
            }
        }
        return false;
    }
}

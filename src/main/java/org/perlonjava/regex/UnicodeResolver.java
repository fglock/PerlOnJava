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
            if (property.equals("XPosixSpace")) {
                property = "IsWhite_Space";
                return (negated ? "\\P{" : "\\p{") + property + "}";
            }

            // Remove common prefixes like "Script=", "Block=", "In=", or "Is="
            if (property.startsWith("Script=")) {
                property = property.substring("Script=".length());
            } else if (property.startsWith("Block=")) {
                property = property.substring("Block=".length());
            } else if (property.startsWith("In=")) {
                property = property.substring("In=".length());
            } else if (property.startsWith("Is=")) {
                property = property.substring("Is=".length());
            }

            // Handle single-character properties (e.g., \p{L}, \p{N})
            if (property.length() == 1) {
                return (negated ? "\\P{" : "\\p{") + property + "}";
            }

            // Handle combined properties (e.g., \p{Script=Hiragana;Letter})
            if (property.contains(";")) {
                String[] parts = property.split(";");
                StringBuilder combinedPattern = new StringBuilder("[");
                for (String part : parts) {
                    combinedPattern.append(translateUnicodeProperty(part, false));
                }
                combinedPattern.append("]");
                return combinedPattern.toString();
            }

            // Use ICU4J to resolve the Unicode property
            UnicodeSet unicodeSet = new UnicodeSet();

            // Handle block properties separately
            if (isBlockProperty(property)) {
                unicodeSet.applyPropertyAlias("Block", property);
            } else {
                unicodeSet.applyPropertyAlias(property, "");
            }

            // Generate the Java-compatible regex pattern
            if (negated) {
                return "[^" + unicodeSet.toPattern(false) + "]";
            } else {
                return "[" + unicodeSet.toPattern(false) + "]";
            }
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

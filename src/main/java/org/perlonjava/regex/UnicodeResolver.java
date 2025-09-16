package org.perlonjava.regex;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
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
            // Special cases - Perl XPosix properties not natively supported in Java
            switch (property) {
                case "XPosixSpace":
                case "XPerlSpace":
                case "SpacePerl":
                    // Use ICU4J UnicodeSet for accurate XPosixSpace
                    return getXPosixSpacePattern(negated);
                case "XPosixAlnum":
                    return wrapCharClass("\\p{IsAlphabetic}\\p{IsDigit}", negated);
                case "XPosixAlpha":
                case "Alpha":
                case "Alphabetic":
                    return wrapProperty("IsAlphabetic", negated);
                case "XPosixBlank":
                case "Blank":
                case "HorizSpace":
                    return wrapProperty("IsWhite_Space", negated);
                case "XPosixCntrl":
                case "Cc":
                case "Cntrl":
                case "Control":
                    return wrapProperty("gc=Cc", negated);
                case "XPosixDigit":
                case "Decimal_Number":
                case "Digit":
                case "Nd":
                    return wrapProperty("IsDigit", negated);
                case "XPosixGraph":
                case "Graph":
                    return wrapCharClass("\\p{IsAlphabetic}\\p{IsDigit}\\p{IsPunctuation}", negated);
                case "XPosixLower":
                case "Lower":
                case "Lowercase":
                    return wrapProperty("IsLowercase", negated);
                case "XPosixPrint":
                case "Print":
                    return wrapCharClass("\\p{IsAlphabetic}\\p{IsDigit}\\p{IsPunctuation}\\p{IsWhite_Space}", negated);
                case "XPosixPunct":
                    return wrapProperty("IsPunctuation", negated);
                case "XPosixUpper":
                case "Upper":
                case "Uppercase":
                    return wrapProperty("IsUppercase", negated);
                case "XPosixWord":
                case "Word":
                case "IsWord":
                    return wrapCharClass("\\p{IsAlphabetic}\\p{gc=Mn}\\p{gc=Me}\\p{gc=Mc}\\p{IsDigit}\\p{gc=Pc}", negated);
                case "XPosixXDigit":
                case "Hex":
                case "Hex_Digit":
                case "XDigit":
                    return wrapProperty("IsHex_Digit", negated);
                case "XIDS":
                case "XIDStart":
                case "XID_Start":
                    // Use ICU4J UnicodeSet for accurate XID_Start
                    return getXIDStartPattern(negated);
                case "XIDC":
                case "XIDCont":
                case "XID_Continue":
                    // Use ICU4J UnicodeSet for accurate XID_Continue
                    return getXIDContinuePattern(negated);
                case "_Perl_IDStart":
                    // Perl's definition: XID_Start + underscore
                    return getPerlIDStartPattern(negated);
                case "_Perl_IDCont":
                    return wrapCharClass("\\p{L}\\p{Nl}\\p{Nd}\\p{Mn}\\p{Mc}\\p{Pc}", negated);
                default:
                    break;
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
                return wrapProperty(property, negated);
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
            return wrapCharClass(pattern, negated);

        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid or unsupported Unicode property: " + property, e);
        }
    }

    // Helper method to get XID_Start pattern using ICU4J
    private static String getXIDStartPattern(boolean negated) {
        UnicodeSet xidStartSet = new UnicodeSet();
        xidStartSet.applyPropertyAlias("XID_Start", "True");
        String pattern = xidStartSet.toPattern(false);
        return wrapCharClass(pattern, negated);
    }

    // Helper method to get XID_Continue pattern using ICU4J
    private static String getXIDContinuePattern(boolean negated) {
        UnicodeSet xidContSet = new UnicodeSet();
        xidContSet.applyPropertyAlias("XID_Continue", "True");
        String pattern = xidContSet.toPattern(false);
        return wrapCharClass(pattern, negated);
    }

    // Helper method to get XPosixSpace pattern using ICU4J
    private static String getXPosixSpacePattern(boolean negated) {
        UnicodeSet spaceSet = new UnicodeSet();
        spaceSet.applyPropertyAlias("White_Space", "True");
        String pattern = spaceSet.toPattern(false);
        return wrapCharClass(pattern, negated);
    }

    // Helper method to get Perl's _IDStart pattern (XID_Start + underscore)
    private static String getPerlIDStartPattern(boolean negated) {
        UnicodeSet perlIDStartSet = new UnicodeSet();
        perlIDStartSet.applyPropertyAlias("XID_Start", "True");
        perlIDStartSet.add('_'); // Add underscore
        String pattern = perlIDStartSet.toPattern(false);
        return wrapCharClass(pattern, negated);
    }

    // Helper method to check if a character has XID_Start property
    public static boolean isXIDStart(int codePoint) {
        return UCharacter.hasBinaryProperty(codePoint, UProperty.XID_START);
    }

    // Helper method to check if a character has XID_Continue property
    public static boolean isXIDContinue(int codePoint) {
        return UCharacter.hasBinaryProperty(codePoint, UProperty.XID_CONTINUE);
    }

    // Helper method to check XPosixSpace (Unicode whitespace)
    public static boolean isXPosixSpace(int codePoint) {
        return UCharacter.hasBinaryProperty(codePoint, UProperty.WHITE_SPACE);
    }

    // Helper method to check _Perl_IDStart (XID_Start + underscore)
    public static boolean isPerlIDStart(int codePoint) {
        return codePoint == '_' || UCharacter.hasBinaryProperty(codePoint, UProperty.XID_START);
    }

    // Helper methods for negation
    private static String wrapProperty(String property, boolean negated) {
        return (negated ? "\\P{" : "\\p{") + property + "}";
    }

    private static String wrapCharClass(String pattern, boolean negated) {
        return negated ? "[^" + pattern + "]" : "[" + pattern + "]";
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
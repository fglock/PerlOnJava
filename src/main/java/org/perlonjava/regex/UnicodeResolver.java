package org.perlonjava.regex;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.UnicodeSet;
import org.perlonjava.runtime.*;

import java.util.HashSet;
import java.util.Set;

public class UnicodeResolver {
    /**
     * Retrieves the Unicode code point for a given character name.
     * Supports:
     * - U+XXXX format (hex code point)
     * - Official Unicode character names (via ICU4J)
     * - Perl charnames module aliases (NEL, NBSP, etc.)
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
            // First try common Perl charnames module aliases
            // These are short names that Perl's charnames module provides
            // for commonly used control characters and spaces
            codePoint = switch (name) {
                // Control characters
                case "NEL" -> 0x0085;  // NEXT LINE (NEL)
                case "BOM" -> 0xFEFF;  // BYTE ORDER MARK
                
                // Spaces (Perl allows both hyphenated and non-hyphenated forms)
                case "NBSP", "NO-BREAK SPACE" -> 0x00A0;
                case "ZWSP", "ZERO WIDTH SPACE" -> 0x200B;
                case "ZWNJ", "ZERO WIDTH NON-JOINER" -> 0x200C;
                case "ZWJ", "ZERO WIDTH JOINER" -> 0x200D;
                
                // Try ICU4J's official Unicode name lookup
                default -> UCharacter.getCharFromName(name);
            };
            
            if (codePoint == -1) {
                throw new IllegalArgumentException("Invalid Unicode character name: " + name);
            }
        }
        return codePoint;
    }

    /**
     * Parses a user-defined property definition string and returns a character class pattern.
     * The format is hex ranges separated by tabs/newlines:
     * - "0009\t000D\n0020" means ranges U+0009 to U+000D and single char U+0020
     * - Lines starting with # are comments
     * - Lines starting with + add another property
     * - Lines starting with - or ! remove a property
     * - Lines starting with & intersect with a property
     *
     * @param definition The property definition string
     * @param recursionSet Set to track recursive property calls
     * @param propertyName The name of the property being parsed (for error messages)
     * @return A character class pattern
     */
    private static String parseUserDefinedProperty(String definition, Set<String> recursionSet, String propertyName) {
        UnicodeSet resultSet = new UnicodeSet();
        boolean hasIntersection = false;
        UnicodeSet intersectionSet = null;
        
        String[] lines = definition.split("\\n");
        for (String line : lines) {
            line = line.trim();
            
            // Skip empty lines and comments
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            // Handle property references
            if (line.startsWith("+")) {
                // Add another property
                String propName = line.substring(1).trim();
                String propPattern = resolvePropertyReference(propName, recursionSet, propertyName);
                UnicodeSet propSet = new UnicodeSet(propPattern);
                resultSet.addAll(propSet);
            } else if (line.startsWith("-") || line.startsWith("!")) {
                // Remove a property
                String propName = line.substring(1).trim();
                String propPattern = resolvePropertyReference(propName, recursionSet, propertyName);
                UnicodeSet propSet = new UnicodeSet(propPattern);
                resultSet.removeAll(propSet);
            } else if (line.startsWith("&")) {
                // Intersection with a property
                String propName = line.substring(1).trim();
                String propPattern = resolvePropertyReference(propName, recursionSet, propertyName);
                if (!hasIntersection) {
                    intersectionSet = new UnicodeSet(propPattern);
                    hasIntersection = true;
                } else {
                    intersectionSet.retainAll(new UnicodeSet(propPattern));
                }
            } else {
                // Parse hex range - extract the hex part before any comments
                String hexPart = line.split("#")[0].trim();
                // Split by tabs or multiple spaces
                String[] parts = hexPart.split("\\t+|\\s{2,}");
                if (parts.length == 1 && !parts[0].isEmpty()) {
                    // Single character
                    String hexStr = parts[0].trim();
                    // Check if it's a valid hex string
                    if (!hexStr.matches("[0-9A-Fa-f]+")) {
                        throw new IllegalArgumentException("Can't find Unicode property definition \"" + line.trim() + "\" in expansion of " + propertyName);
                    }
                    try {
                        long codePoint = Long.parseLong(hexStr, 16);
                        if (codePoint > 0x10FFFF) {
                            throw new IllegalArgumentException("Code point too large in \"" + line.trim() + "\" in expansion of " + propertyName);
                        }
                        resultSet.add((int) codePoint);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Can't find Unicode property definition \"" + line.trim() + "\" in expansion of " + propertyName);
                    }
                } else if (parts.length >= 2) {
                    // Range
                    String startHex = parts[0].trim();
                    String endHex = parts[1].trim();
                    
                    // Check if they're valid hex strings
                    if (!startHex.matches("[0-9A-Fa-f]+") || !endHex.matches("[0-9A-Fa-f]+")) {
                        throw new IllegalArgumentException("Can't find Unicode property definition \"" + line.trim() + "\" in expansion of " + propertyName);
                    }
                    
                    try {
                        long start = Long.parseLong(startHex, 16);
                        long end = Long.parseLong(endHex, 16);
                        
                        if (start > 0x10FFFF) {
                            throw new IllegalArgumentException("Code point too large in \"" + line.trim() + "\" in expansion of " + propertyName);
                        }
                        if (end > 0x10FFFF) {
                            throw new IllegalArgumentException("Code point too large in \"" + line.trim() + "\" in expansion of " + propertyName);
                        }
                        if (start > end) {
                            throw new IllegalArgumentException("Illegal range in \"" + line.trim() + "\" in expansion of " + propertyName);
                        }
                        
                        resultSet.add((int) start, (int) end);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Can't find Unicode property definition \"" + line.trim() + "\" in expansion of " + propertyName);
                    }
                }
            }
        }
        
        // Apply intersection if any
        if (hasIntersection) {
            resultSet.retainAll(intersectionSet);
        }
        
        return resultSet.toPattern(false);
    }
    
    /**
     * Resolves a property reference (like utf8::InHiragana or main::IsMyProp).
     *
     * @param propRef The property reference
     * @param recursionSet Set to track recursive property calls
     * @param parentProperty The parent property name (for error messages)
     * @return A character class pattern
     */
    private static String resolvePropertyReference(String propRef, Set<String> recursionSet, String parentProperty) {
        // Check for recursion
        if (recursionSet.contains(propRef)) {
            // Build recursion chain for error message
            StringBuilder chain = new StringBuilder();
            for (String prop : recursionSet) {
                if (chain.length() > 0) {
                    chain.append(" in expansion of ");
                }
                chain.append(prop);
            }
            if (chain.length() > 0) {
                chain.append(" in expansion of ");
            }
            chain.append(propRef);
            throw new IllegalArgumentException("Infinite recursion in user-defined property \"" + propRef + "\" in expansion of " + chain);
        }
        
        // Remove utf8:: prefix if present
        if (propRef.startsWith("utf8::")) {
            String stdProp = propRef.substring(6);
            try {
                // Try as standard property
                return translateUnicodeProperty(stdProp, false, recursionSet);
            } catch (IllegalArgumentException e) {
                // Fall through to user-defined property lookup
                propRef = "main::" + stdProp;
            }
        }
        
        // Try as user-defined property
        return translateUnicodeProperty(propRef, false, recursionSet);
    }
    
    /**
     * Tries to look up a user-defined property by calling a Perl subroutine.
     *
     * @param property The property name (e.g., "IsMyUpper" or "main::IsMyUpper")
     * @param recursionSet Set to track recursive property calls
     * @return The property definition string, or null if not found
     */
    private static String tryUserDefinedProperty(String property, Set<String> recursionSet) {
        // Add to recursion set
        Set<String> newRecursionSet = new HashSet<>(recursionSet);
        newRecursionSet.add(property);
        
        // Build the full subroutine name
        String subName = property;
        if (!subName.contains("::")) {
            // Try in main package
            subName = "main::" + subName;
        }
        
        // Look up the subroutine
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(subName);
        if (codeRef == null || !codeRef.getDefinedBoolean()) {
            return null;
        }
        
        try {
            // Call the subroutine with an empty argument list
            RuntimeArray args = new RuntimeArray();
            RuntimeList result = RuntimeCode.apply(codeRef, args, RuntimeContextType.SCALAR);
            
            if (result.elements.isEmpty()) {
                return "";
            }
            
            String definition = result.elements.getFirst().toString();
            
            // Parse and return the property definition
            return parseUserDefinedProperty(definition, newRecursionSet, subName);
            
        } catch (PerlCompilerException e) {
            // Re-throw Perl exceptions (like die in IsDeath)
            String msg = e.getMessage();
            if (msg != null && !msg.contains("in expansion of")) {
                throw new IllegalArgumentException("Died" + (msg.isEmpty() ? "" : ": " + msg) + " in expansion of " + subName, e);
            }
            throw e;
        } catch (IllegalArgumentException e) {
            // Re-throw validation errors from parseUserDefinedProperty
            throw e;
        } catch (Exception e) {
            // Wrap other errors
            throw new IllegalArgumentException("Error in user-defined property " + subName + ": " + e.getMessage(), e);
        }
    }

    public static String translateUnicodeProperty(String property, boolean negated) {
        return translateUnicodeProperty(property, negated, new HashSet<>());
    }
    
    private static String translateUnicodeProperty(String property, boolean negated, Set<String> recursionSet) {
        try {
            // Check for user-defined properties (Is... or In...)
            if (property.matches("^(.*::)?(Is|In)[A-Z].*")) {
                String userProp = tryUserDefinedProperty(property, recursionSet);
                if (userProp != null) {
                    return wrapCharClass(userProp, negated);
                }
                // If the property doesn't exist yet, it might be a forward reference
                // Return a pattern that will never match anything - this allows the regex to compile
                // In real Perl, this would be deferred until match time, but implementing full
                // deferred resolution is complex. For now, return an empty character class.
                // Note: This means forward-referenced properties won't work, but at least
                // the regex will compile.
                return "(?!)"; // Negative lookahead that never matches
            }
            
            // Special cases - Perl XPosix properties not natively supported in Java
            switch (property) {
                case "lb=cr":
                case "lb=CR":
                    // Line Break = Carriage Return (U+000D)
                    return negated ? "[^\\r]" : "[\\r]";
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

            // Remove prefixes (Blk= is Perl's short form for Block=)
            for (String prefix : new String[]{"Script=", "Block=", "Blk=", "In=", "Is="}) {
                if (property.startsWith(prefix)) {
                    property = property.substring(prefix.length());
                    break;
                }
            }
            
            // Map Perl block aliases to Unicode block names
            if (property.equalsIgnoreCase("ASCII")) {
                property = "Basic_Latin";
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
            // If the error message already contains "in expansion of", it's a user-defined property error
            // that should be propagated as-is
            if (e.getMessage() != null && e.getMessage().contains("in expansion of")) {
                throw e;
            }
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
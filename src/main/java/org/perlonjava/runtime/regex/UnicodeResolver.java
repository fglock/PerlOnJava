package org.perlonjava.runtime.regex;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.UnicodeSet;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class UnicodeResolver {
    /**
     * Cache for user-defined property subroutine results.
     * Perl only calls user-defined property subs once per unique name and caches the result.
     * Key: fully qualified sub name (e.g., "main::IsMyUpper")
     * Value: the parsed character class pattern from parseUserDefinedProperty
     */
    private static final Map<String, String> userPropertyCache = new HashMap<>();

    /**
     * Retrieves the Unicode code point for a given character name.
     * Supports:
     * - U+XXXX format (hex code point)
     * - Official Unicode character names (via ICU4J)
     * - Perl charnames module aliases (NEL, NBSP, etc.)
     *
     * @param name The name of the Unicode character.
     * @return The Unicode code point.
     * @throws IllegalArgumentException If the name is invalid, not found, or is a named sequence.
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
                // C0 control character abbreviations (Unicode name aliases)
                case "NUL", "NULL" -> 0x0000;
                case "SOH", "START OF HEADING" -> 0x0001;
                case "STX", "START OF TEXT" -> 0x0002;
                case "ETX", "END OF TEXT" -> 0x0003;
                case "EOT", "END OF TRANSMISSION" -> 0x0004;
                case "ENQ", "ENQUIRY" -> 0x0005;
                case "ACK", "ACKNOWLEDGE" -> 0x0006;
                case "BEL", "ALERT" -> 0x0007;
                case "BS", "BACKSPACE" -> 0x0008;
                case "HT", "TAB", "CHARACTER TABULATION", "HORIZONTAL TABULATION" -> 0x0009;
                case "LF", "LINE FEED", "LINE FEED (LF)" -> 0x000A;
                case "VT", "LINE TABULATION", "VERTICAL TABULATION" -> 0x000B;
                case "FF", "FORM FEED", "FORM FEED (FF)" -> 0x000C;
                case "CR", "CARRIAGE RETURN", "CARRIAGE RETURN (CR)" -> 0x000D;
                case "SO", "SHIFT OUT" -> 0x000E;
                case "SI", "SHIFT IN" -> 0x000F;
                case "DLE", "DATA LINK ESCAPE" -> 0x0010;
                case "DC1", "DEVICE CONTROL ONE" -> 0x0011;
                case "DC2", "DEVICE CONTROL TWO" -> 0x0012;
                case "DC3", "DEVICE CONTROL THREE" -> 0x0013;
                case "DC4", "DEVICE CONTROL FOUR" -> 0x0014;
                case "NAK", "NEGATIVE ACKNOWLEDGE" -> 0x0015;
                case "SYN", "SYNCHRONOUS IDLE" -> 0x0016;
                case "ETB", "END OF TRANSMISSION BLOCK" -> 0x0017;
                case "CAN", "CANCEL" -> 0x0018;
                case "EOM", "END OF MEDIUM" -> 0x0019;
                case "SUB", "SUBSTITUTE" -> 0x001A;
                case "ESC", "ESCAPE" -> 0x001B;
                case "FS", "INFORMATION SEPARATOR FOUR", "FILE SEPARATOR" -> 0x001C;
                case "GS", "INFORMATION SEPARATOR THREE", "GROUP SEPARATOR" -> 0x001D;
                case "RS", "INFORMATION SEPARATOR TWO", "RECORD SEPARATOR" -> 0x001E;
                case "US", "INFORMATION SEPARATOR ONE", "UNIT SEPARATOR" -> 0x001F;
                case "SP", "SPACE" -> 0x0020;
                case "DEL", "DELETE" -> 0x007F;

                // Other control characters and special characters
                case "NEL", "NEXT LINE", "NEXT LINE (NEL)" -> 0x0085;
                case "BOM", "BYTE ORDER MARK" -> 0xFEFF;

                // Spaces (Perl allows both hyphenated and non-hyphenated forms)
                case "NBSP", "NO-BREAK SPACE" -> 0x00A0;
                case "ZWSP", "ZERO WIDTH SPACE" -> 0x200B;
                case "ZWNJ", "ZERO WIDTH NON-JOINER" -> 0x200C;
                case "ZWJ", "ZERO WIDTH JOINER" -> 0x200D;

                // Try ICU4J's official Unicode name lookup
                default -> UCharacter.getCharFromName(name);
            };

            if (codePoint == -1) {
                // Check if this is a named sequence (multi-character sequence)
                // Named sequences are not supported in some contexts like tr///
                if (isNamedSequence(name)) {
                    throw new IllegalArgumentException("named sequence: " + name);
                }
                throw new IllegalArgumentException("Invalid Unicode character name: " + name);
            }
        }
        return codePoint;
    }

    /**
     * Checks if a given name refers to a Unicode named character sequence.
     * Named sequences are multi-character sequences with Unicode-assigned names.
     *
     * @param name The name to check.
     * @return true if it's a named sequence, false otherwise.
     */
    private static boolean isNamedSequence(String name) {
        // ICU4J's UCharacter.getCharFromName() returns -1 for both invalid names
        // and named sequences. Unfortunately, there's no easy way to distinguish
        // between them without maintaining our own list of named sequences.
        // 
        // For now, we conservatively treat all failures as potential named sequences
        // in the context of tr///, which is the safest approach.
        //
        // Common named sequences include things like:
        // - "KATAKANA LETTER AINU P" (U+31F7 U+309A)
        // - "LATIN CAPITAL LETTER E WITH VERTICAL LINE BELOW" (U+0045 U+0329)
        //
        // This is left as a placeholder for future enhancement if needed.
        return false;
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
     * @param definition   The property definition string
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
                UnicodeSet propSet = resolvePropertyReferenceAsSet(propName, recursionSet, propertyName);
                resultSet.addAll(propSet);
            } else if (line.startsWith("-") || line.startsWith("!")) {
                // Remove a property
                String propName = line.substring(1).trim();
                UnicodeSet propSet = resolvePropertyReferenceAsSet(propName, recursionSet, propertyName);
                resultSet.removeAll(propSet);
            } else if (line.startsWith("&")) {
                // Intersection with a property
                String propName = line.substring(1).trim();
                UnicodeSet propSet = resolvePropertyReferenceAsSet(propName, recursionSet, propertyName);
                if (!hasIntersection) {
                    intersectionSet = propSet;
                    hasIntersection = true;
                } else {
                    intersectionSet.retainAll(propSet);
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

        return unicodeSetToJavaPattern(resultSet);
    }

    /**
     * Resolves a property reference to a UnicodeSet (like utf8::InHiragana or main::IsMyProp).
     * Returns a UnicodeSet directly instead of a Java regex pattern string, so the result
     * can be used with UnicodeSet set operations (addAll, removeAll, retainAll).
     *
     * @param propRef        The property reference
     * @param recursionSet   Set to track recursive property calls
     * @param parentProperty The parent property name (for error messages)
     * @return A UnicodeSet representing the property
     */
    private static UnicodeSet resolvePropertyReferenceAsSet(String propRef, Set<String> recursionSet, String parentProperty) {
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
        String propName = propRef;
        if (propRef.startsWith("utf8::")) {
            propName = propRef.substring(6);
        }

        // Try to resolve as a standard Unicode property via ICU4J
        UnicodeSet result = resolveStandardPropertyAsSet(propName, recursionSet);
        if (result != null) {
            return result;
        }

        // Try as user-defined property (calls the Perl sub)
        String fallbackRef = propRef.startsWith("utf8::") ? "main::" + propRef.substring(6) : propRef;
        String userProp = tryUserDefinedProperty(fallbackRef, recursionSet);
        if (userProp != null) {
            // userProp is a character class pattern from unicodeSetToJavaPattern
            return new UnicodeSet("[" + userProp + "]");
        }

        throw new IllegalArgumentException("Invalid or unsupported Unicode property: " + propRef);
    }

    /**
     * Resolves a standard Unicode property name to a UnicodeSet using ICU4J directly.
     * Handles the same aliases as translateUnicodeProperty but returns a UnicodeSet.
     *
     * @param property     The property name (without utf8:: prefix)
     * @param recursionSet Set to track recursive property calls
     * @return A UnicodeSet, or null if the property cannot be resolved
     */
    private static UnicodeSet resolveStandardPropertyAsSet(String property, Set<String> recursionSet) {
        // Handle well-known Perl property aliases
        switch (property) {
            case "XPosixSpace": case "XPerlSpace": case "SpacePerl":
            case "Space": case "White_Space": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("White_Space", "True");
                return set;
            }
            case "XPosixAlnum": case "Alnum": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("Alphabetic", "True");
                UnicodeSet digits = new UnicodeSet();
                digits.applyPropertyAlias("gc", "Nd");
                set.addAll(digits);
                return set;
            }
            case "XPosixAlpha": case "Alpha": case "Alphabetic": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("Alphabetic", "True");
                return set;
            }
            case "XPosixUpper": case "Upper": case "Uppercase": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("Uppercase", "True");
                return set;
            }
            case "Titlecase": case "TitlecaseLetter": case "Titlecase_Letter": case "Lt": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("gc", "Lt");
                return set;
            }
            case "XPosixLower": case "Lower": case "Lowercase": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("Lowercase", "True");
                return set;
            }
            case "XPosixDigit": case "Decimal_Number": case "Digit": case "Nd": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("gc", "Nd");
                return set;
            }
            case "XPosixPunct": case "Punct": case "Punctuation": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("gc", "P");
                return set;
            }
            case "Dash": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("Dash", "True");
                return set;
            }
            case "Hex_Digit": case "Hex": case "XPosixXDigit": case "XDigit":
            case "ASCII_Hex_Digit": case "AHex": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("ASCII_Hex_Digit", "True");
                return set;
            }
            case "Cn": {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("gc", "Cn");
                return set;
            }
            case "ASCII": {
                return new UnicodeSet("[\\u0000-\\u007F]");
            }
            default:
                break;
        }

        // Strip Is/In prefix for Perl compatibility
        String stripped = property;
        if (property.length() > 2
                && (property.charAt(0) == 'I' || property.charAt(0) == 'i')
                && (property.charAt(1) == 's' || property.charAt(1) == 'S')
                && Character.isUpperCase(property.charAt(2))) {
            stripped = property.substring(2);
            // Recurse with stripped name
            UnicodeSet result = resolveStandardPropertyAsSet(stripped, recursionSet);
            if (result != null) {
                return result;
            }
        } else if (property.length() > 2
                && (property.charAt(0) == 'I' || property.charAt(0) == 'i')
                && (property.charAt(1) == 'n' || property.charAt(1) == 'N')
                && Character.isUpperCase(property.charAt(2))) {
            stripped = property.substring(2);
            // Try as block name
            try {
                UnicodeSet set = new UnicodeSet();
                set.applyPropertyAlias("Block", stripped);
                return set;
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Map ASCII alias to block name
        if (stripped.equalsIgnoreCase("ASCII")) {
            return new UnicodeSet("[\\u0000-\\u007F]");
        }

        // Try direct ICU4J lookup as general category, script, or binary property
        try {
            UnicodeSet set = new UnicodeSet();
            set.applyPropertyAlias(stripped, "True");
            return set;
        } catch (IllegalArgumentException ignored) {
        }
        try {
            UnicodeSet set = new UnicodeSet();
            set.applyPropertyAlias(stripped, "");
            return set;
        } catch (IllegalArgumentException ignored) {
        }

        // Try as block name
        try {
            UnicodeSet set = new UnicodeSet();
            set.applyPropertyAlias("Block", stripped);
            return set;
        } catch (IllegalArgumentException ignored) {
        }

        return null;
    }

    /**
     * Tries to look up a user-defined property by calling a Perl subroutine.
     * Results are cached per sub name, matching Perl's behavior of only calling
     * user-defined property subs once per unique property name.
     *
     * @param property     The property name (e.g., "IsMyUpper" or "main::IsMyUpper")
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

        // Check cache first — Perl only calls user-defined property subs once
        if (userPropertyCache.containsKey(subName)) {
            return userPropertyCache.get(subName);
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
                String parsed = "";
                userPropertyCache.put(subName, parsed);
                return parsed;
            }

            String definition = result.elements.getFirst().toString();

            // Parse and cache the property definition
            String parsed = parseUserDefinedProperty(definition, newRecursionSet, subName);
            userPropertyCache.put(subName, parsed);
            return parsed;

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
            if (property.matches("^(.*::)?([Ii][sn])[A-Z].*")) {
                String userProp = tryUserDefinedProperty(property, recursionSet);
                if (userProp != null) {
                    return wrapCharClass(userProp, negated);
                }
                // Property not found - fall through to throw error below
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
                case "Space":
                case "White_Space":
                    // Use ICU4J UnicodeSet for accurate XPosixSpace
                    return getXPosixSpacePattern(negated);
                case "XPosixAlnum":
                case "Alnum":
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
                case "Punct":
                case "Punctuation":
                    return wrapProperty("IsPunctuation", negated);
                case "XPosixUpper":
                case "Upper":
                case "Uppercase":
                    return wrapProperty("IsUppercase", negated);
                case "Titlecase":
                case "TitlecaseLetter":
                case "Titlecase_Letter":
                case "Lt":
                    return wrapProperty("gc=Lt", negated);
                case "XPosixWord":
                case "Word":
                case "IsWord":
                    return wrapCharClass("\\p{IsAlphabetic}\\p{gc=Mn}\\p{gc=Me}\\p{gc=Mc}\\p{IsDigit}\\p{gc=Pc}", negated);
                case "XPosixXDigit":
                case "Hex":
                case "Hex_Digit":
                case "XDigit":
                    return wrapProperty("IsHex_Digit", negated);
                // ASCII-only POSIX character classes (PosixXxx variants)
                // These match only ASCII characters, unlike their XPosix counterparts
                case "PosixAlnum":
                    return negated ? "[^a-zA-Z0-9]" : "[a-zA-Z0-9]";
                case "PosixAlpha":
                    return negated ? "[^a-zA-Z]" : "[a-zA-Z]";
                case "PosixBlank":
                    return negated ? "[^ \\t]" : "[ \\t]";
                case "PosixCntrl":
                    return negated ? "[^\\x00-\\x1f\\x7f]" : "[\\x00-\\x1f\\x7f]";
                case "PosixDigit":
                    return negated ? "[^0-9]" : "[0-9]";
                case "PosixGraph":
                    return negated ? "[^!-~]" : "[!-~]";
                case "PosixLower":
                    return negated ? "[^a-z]" : "[a-z]";
                case "PosixPrint":
                    return negated ? "[^ -~]" : "[ -~]";
                case "PosixPunct":
                    return negated ? "[^!-/:-@\\[-`{-~]" : "[!-/:-@\\[-`{-~]";
                case "PosixSpace":
                    return negated ? "[^ \\t\\n\\r\\f\\x0b]" : "[ \\t\\n\\r\\f\\x0b]";
                case "PosixUpper":
                    return negated ? "[^A-Z]" : "[A-Z]";
                case "PosixWord":
                    return negated ? "[^a-zA-Z0-9_]" : "[a-zA-Z0-9_]";
                case "PosixXDigit":
                    return negated ? "[^0-9a-fA-F]" : "[0-9a-fA-F]";
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

            // Strip 'Is'/'is' prefix for Perl compatibility (e.g., IsPrint -> Print, isAlpha -> Alpha)
            // Perl is case-insensitive for the 'Is' prefix on Unicode property names
            if (property.length() > 2
                    && (property.charAt(0) == 'I' || property.charAt(0) == 'i')
                    && (property.charAt(1) == 's' || property.charAt(1) == 'S')
                    && Character.isUpperCase(property.charAt(2))) {
                property = property.substring(2);
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

            // Handle Property=Value syntax (e.g., ASCII_Hex_Digit=True, gc=Ll)
            String propName = property;
            String propValue = "";
            int eqIdx = property.indexOf('=');
            if (eqIdx > 0 && eqIdx < property.length() - 1) {
                propName = property.substring(0, eqIdx);
                propValue = property.substring(eqIdx + 1);
                // Handle negation: Property=False means \P{Property}
                if (propValue.equalsIgnoreCase("False") || propValue.equalsIgnoreCase("No") || propValue.equals("N") || propValue.equals("F")) {
                    negated = !negated;
                    propValue = "True";
                } else if (propValue.equalsIgnoreCase("True") || propValue.equalsIgnoreCase("Yes") || propValue.equals("Y") || propValue.equals("T")) {
                    propValue = "True";
                }
            }

            if (isBlockProperty(propName)) {
                unicodeSet.applyPropertyAlias("Block", propName);
            } else {
                try {
                    unicodeSet.applyPropertyAlias(propName, propValue);
                } catch (IllegalArgumentException ex) {
                    // Property not found as general category/script - try as a Unicode block name.
                    // Perl resolves \p{Emoticons} as \p{Block=Emoticons}, etc.
                    try {
                        unicodeSet.applyPropertyAlias("Block", property);
                    } catch (IllegalArgumentException ex2) {
                        // Neither worked - try user-defined property before giving up
                        String userProp = tryUserDefinedProperty(property, recursionSet);
                        if (userProp != null) {
                            return wrapCharClass(userProp, negated);
                        }
                        throw ex; // rethrow original error
                    }
                }
            }

            String pattern = unicodeSetToJavaPattern(unicodeSet);
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
        String pattern = unicodeSetToJavaPattern(xidStartSet);
        return wrapCharClass(pattern, negated);
    }

    // Helper method to get XID_Continue pattern using ICU4J
    private static String getXIDContinuePattern(boolean negated) {
        UnicodeSet xidContSet = new UnicodeSet();
        xidContSet.applyPropertyAlias("XID_Continue", "True");
        String pattern = unicodeSetToJavaPattern(xidContSet);
        return wrapCharClass(pattern, negated);
    }

    // Helper method to get XPosixSpace pattern using ICU4J
    private static String getXPosixSpacePattern(boolean negated) {
        UnicodeSet spaceSet = new UnicodeSet();
        spaceSet.applyPropertyAlias("White_Space", "True");
        String pattern = unicodeSetToJavaPattern(spaceSet);
        return wrapCharClass(pattern, negated);
    }

    // Helper method to get Perl's _IDStart pattern (XID_Start + underscore)
    private static String getPerlIDStartPattern(boolean negated) {
        UnicodeSet perlIDStartSet = new UnicodeSet();
        perlIDStartSet.applyPropertyAlias("XID_Start", "True");
        perlIDStartSet.add('_'); // Add underscore
        String pattern = unicodeSetToJavaPattern(perlIDStartSet);
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

    /**
     * Converts a UnicodeSet to a Java regex character class pattern.
     * Uses \x{XXXX} notation for supplementary characters (U+10000+) to avoid
     * issues with Java's Pattern.compile() misinterpreting UTF-16 surrogate pairs
     * in character class ranges generated by ICU4J's toPattern().
     */
    static String unicodeSetToJavaPattern(UnicodeSet set) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < set.getRangeCount(); i++) {
            int start = set.getRangeStart(i);
            int end = set.getRangeEnd(i);
            appendJavaPatternChar(sb, start);
            if (start != end) {
                sb.append('-');
                appendJavaPatternChar(sb, end);
            }
        }
        return sb.toString();
    }

    private static void appendJavaPatternChar(StringBuilder sb, int codePoint) {
        if (codePoint >= 0x10000) {
            // Use \x{XXXX} for supplementary characters to avoid surrogate pair issues
            sb.append(String.format("\\x{%X}", codePoint));
        } else {
            // Escape special regex metacharacters inside character classes
            // Also escape # and whitespace so the pattern works with Pattern.COMMENTS flag
            switch (codePoint) {
                case '[': case ']': case '\\': case '^': case '-': case '&':
                case '{': case '}': case '#':
                    sb.append('\\');
                    sb.append((char) codePoint);
                    break;
                default:
                    if (codePoint < 0x20 || codePoint == 0x7F ||
                        Character.isWhitespace(codePoint)) {
                        // Control characters and whitespace - use hex escape
                        sb.append(String.format("\\x{%X}", codePoint));
                    } else {
                        sb.append((char) codePoint);
                    }
                    break;
            }
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
package org.perlonjava.runtime.regex;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.UnicodeSet;
import org.joni.CharacterPropertyResolver;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class UnicodeResolver {
    private static final Pattern USER_DEFINED_PROPERTY_NAME = Pattern.compile(
            "^(?:[A-Za-z_][A-Za-z0-9_]*::)*(?:Is|In)[A-Za-z_][A-Za-z0-9_]*$");
    private static final UnicodeSet[] PERL_DECOMPOSITION_TYPE_SETS =
            buildPerlDecompositionTypeSets();

    /**
     * Cache for user-defined property subroutine results.
     * Perl only calls user-defined property subs once per unique name and caches the result.
     * Key: fully qualified sub name (e.g., "main::IsMyUpper")
     * Value: the parsed character class pattern from parseUserDefinedProperty
     */
    private static Map<String, String> userPropertyCache() {
        return PerlRuntime.current().regexState().userUnicodePropertyCache;
    }

    private static Set<String> deferredUserProperties() {
        return PerlRuntime.current().regexState().deferredUserUnicodeProperties;
    }

    private static String userPropertyCacheKey(String subName, boolean caseInsensitive) {
        return subName + (caseInsensitive ? "\u0000i" : "\u0000s");
    }

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
                String propName = stripDefinitionComment(line.substring(1));
                UnicodeSet propSet = resolvePropertyReferenceAsSet(propName, recursionSet, propertyName);
                resultSet.addAll(propSet);
            } else if (line.startsWith("-") || line.startsWith("!")) {
                // Remove a property
                String propName = stripDefinitionComment(line.substring(1));
                // A leading minus may subtract a literal code point/range,
                // e.g. "-61" in a consonant property definition.
                if (!(line.startsWith("-") && removeHexRange(resultSet, propName))) {
                    UnicodeSet propSet = resolvePropertyReferenceAsSet(propName, recursionSet, propertyName);
                    resultSet.removeAll(propSet);
                }
            } else if (line.startsWith("&")) {
                // Intersection with a property
                String propName = stripDefinitionComment(line.substring(1));
                UnicodeSet propSet = resolvePropertyReferenceAsSet(propName, recursionSet, propertyName);
                if (!hasIntersection) {
                    intersectionSet = propSet;
                    hasIntersection = true;
                } else {
                    intersectionSet.retainAll(propSet);
                }
            } else {
                // Parse hex range - extract the hex part before any comments
                String hexPart = stripDefinitionComment(line);
                String[] parts = hexPart.split("\\s+");
                if (parts.length == 1 && !parts[0].isEmpty()) {
                    // Single character
                    String hexStr = parts[0].trim();
                    // Check if it's a valid hex string
                    if (!hexStr.matches("[0-9A-Fa-f]+")) {
                        throw new IllegalArgumentException(
                                "Can't find Unicode property definition \"" + hexPart
                                        + "\" in expansion of " + propertyName);
                    }
                    try {
                        long codePoint = Long.parseLong(hexStr, 16);
                        if (codePoint > 0x10FFFF) {
                            // JVM only supports Unicode up to U+10FFFF; silently clamp
                            // (Perl supports 31-bit/32-bit code points, but Java doesn't)
                            codePoint = 0x10FFFF;
                        }
                        resultSet.add((int) codePoint);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Code point too large in \"" + line
                                        + "\" in expansion of " + propertyName);
                    }
                } else if (parts.length >= 2) {
                    // Range
                    String startHex = parts[0].trim();
                    String endHex = parts[1].trim();

                    // Check if they're valid hex strings
                    if (!startHex.matches("[0-9A-Fa-f]+") || !endHex.matches("[0-9A-Fa-f]+")) {
                        throw new IllegalArgumentException(
                                "Can't find Unicode property definition \"" + hexPart
                                        + "\" in expansion of " + propertyName);
                    }

                    try {
                        long start = Long.parseLong(startHex, 16);
                        long end = Long.parseLong(endHex, 16);

                        // JVM only supports Unicode up to U+10FFFF; clamp values
                        // (Perl supports 31-bit/32-bit code points, but Java doesn't)
                        if (start > 0x10FFFF) {
                            // Entire range is beyond JVM limit; skip it
                            continue;
                        }
                        if (end > 0x10FFFF) {
                            end = 0x10FFFF;
                        }
                        if (start > end) {
                            throw new IllegalArgumentException("Illegal range in \"" + line.trim() + "\" in expansion of " + propertyName);
                        }

                        resultSet.add((int) start, (int) end);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "Code point too large in \"" + line
                                        + "\" in expansion of " + propertyName);
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

    private static String stripDefinitionComment(String propertyReference) {
        int comment = propertyReference.indexOf('#');
        return (comment >= 0 ? propertyReference.substring(0, comment) : propertyReference).trim();
    }

    private static boolean removeHexRange(UnicodeSet target, String definition) {
        String[] endpoints = definition.split("\\t+|\\s+");
        if (endpoints.length < 1 || endpoints.length > 2) return false;
        for (String endpoint : endpoints) {
            if (!endpoint.matches("[0-9A-Fa-f]+")) return false;
        }
        try {
            long start = Long.parseLong(endpoints[0], 16);
            long end = endpoints.length == 1 ? start : Long.parseLong(endpoints[1], 16);
            if (start > end) return false;
            if (start <= 0x10FFFF) {
                target.remove((int) start, (int) Math.min(end, 0x10FFFF));
            }
            return true;
        } catch (NumberFormatException invalidHex) {
            return false;
        }
    }

    private static IllegalArgumentException recursivePropertyError(
            String property, Set<String> recursionSet) {
        List<String> expansionChain = new ArrayList<>(recursionSet);
        Collections.reverse(expansionChain);
        return new IllegalArgumentException(
                "Infinite recursion in user-defined property \"" + property
                        + "\" in expansion of "
                        + String.join(" in expansion of ", expansionChain));
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
            throw recursivePropertyError(propRef, recursionSet);
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
        String userProp = tryUserDefinedProperty(fallbackRef, recursionSet, false);
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
        UnicodeSet perlBuiltInAlias = resolvePerlBuiltInPropertyAlias(property);
        if (perlBuiltInAlias != null) return perlBuiltInAlias;

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
    private static String tryUserDefinedProperty(
            String property, Set<String> recursionSet, boolean caseInsensitive) {
        return tryUserDefinedProperty(property, recursionSet, caseInsensitive, false);
    }

    private static String tryUserDefinedProperty(
            String property, Set<String> recursionSet, boolean caseInsensitive,
            boolean qualifyBareDiagnosticName) {
        // Build the full subroutine name
        String subName = property;
        if (!subName.contains("::")) {
            // Try in main package
            subName = "main::" + subName;
        }

        // The runtime-family coordinator also keys properties by their fully
        // qualified sub name. Keep recursion tracking in that same canonical
        // namespace; otherwise InFoo -> main::InFoo can wait on its own active
        // future instead of reporting recursive expansion.
        if (recursionSet.contains(subName)) {
            throw recursivePropertyError(subName, recursionSet);
        }
        Set<String> newRecursionSet = new LinkedHashSet<>(recursionSet);
        newRecursionSet.add(subName);

        // Check cache first — Perl only calls user-defined property subs once
        String cacheKey = userPropertyCacheKey(subName, caseInsensitive);
        if (userPropertyCache().containsKey(cacheKey)) {
            return userPropertyCache().get(cacheKey);
        }

        // A property sub is arbitrary Perl and may block. Regex parsing occurs
        // under the process compile lock, so invoking it here would prevent an
        // unrelated ithread from compiling a different property. Leave the
        // existing placeholder marker for ensureCompiledForRuntime() to resolve
        // after ordinary execution has released the compiler lock.
        if (PerlLanguageProvider.COMPILE_LOCK.isHeldByCurrentThread()) {
            return null;
        }

        // Look up the subroutine without autovivifying an empty CODE slot.
        if (!GlobalVariable.isGlobalCodeRefDefined(subName)) {
            return null;
        }
        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(subName);

        final String resolvedSubName = subName;
        final String diagnosticName = (qualifyBareDiagnosticName
                || deferredUserProperties().contains(subName))
                && !property.contains("::") ? subName : property;
        final String coordinationKey = cacheKey;
        try {
            String parsed = PerlRuntime.current().threadRegistry()
                    .resolveUserUnicodeProperty(coordinationKey,
                            () -> resolveUserDefinedProperty(
                                    codeRef, resolvedSubName, diagnosticName,
                                    newRecursionSet,
                                    caseInsensitive));
            userPropertyCache().put(cacheKey, parsed);
            return parsed;
        } catch (PerlDieException e) {
            throw propertyDefinitionDie(e, diagnosticName);
        } catch (PerlCompilerException e) {
            // Re-throw Perl exceptions (like die in IsDeath)
            String msg = e.getMessage();
            if (msg != null && !msg.contains("in expansion of")) {
                throw new IllegalArgumentException("Died" + (msg.isEmpty() ? "" : ": " + msg)
                        + " in expansion of " + diagnosticName, e);
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

    private static String resolveUserDefinedProperty(
            RuntimeScalar codeRef, String subName, String diagnosticName,
            Set<String> recursionSet, boolean caseInsensitive) {
        try {
            // Perl passes one false/true argument for case-sensitive/folded
            // expansion. A user property may intentionally return distinct
            // definitions for the two modes.
            RuntimeArray args = new RuntimeArray(new RuntimeScalar(caseInsensitive ? 1 : 0));
            RuntimeList result = RuntimeCode.apply(codeRef, args, RuntimeContextType.SCALAR);

            if (result.elements.isEmpty()) {
                return "";
            }

            String definition = result.elements.getFirst().toString();

            // Parse and cache the property definition
            return parseUserDefinedProperty(definition, recursionSet, diagnosticName);

        } catch (PerlDieException e) {
            throw propertyDefinitionDie(e, diagnosticName);
        } catch (PerlCompilerException e) {
            // Re-throw Perl exceptions (like die in IsDeath)
            String msg = e.getMessage();
            if (msg != null && !msg.contains("in expansion of")) {
                throw new IllegalArgumentException("Died" + (msg.isEmpty() ? "" : ": " + msg)
                        + " in expansion of " + diagnosticName, e);
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

    private static IllegalArgumentException propertyDefinitionDie(
            PerlDieException failure, String propertyName) {
        String message = failure.getMessage();
        return new IllegalArgumentException(
                "Error \"" + (message == null ? "" : message)
                        + "\" in expansion of " + propertyName);
    }

    /**
     * Resolve deferred top-level user properties before entering the synchronized
     * regex compiler. Property subs are arbitrary Perl and may block; keeping
     * them outside that compiler monitor lets unrelated property names proceed.
     */
    static void preloadUserDefinedProperties(String pattern, boolean caseInsensitive) {
        preloadUserDefinedProperties(pattern, caseInsensitive, false);
    }

    static void preloadDeferredUserDefinedProperties(
            String pattern, boolean caseInsensitive) {
        preloadUserDefinedProperties(pattern, caseInsensitive, true);
    }

    private static void preloadUserDefinedProperties(
            String pattern, boolean caseInsensitive,
            boolean qualifyBareDiagnosticName) {
        if (pattern == null || pattern.isEmpty()) return;

        for (int slash = pattern.indexOf('\\'); slash >= 0;
             slash = pattern.indexOf('\\', slash + 1)) {
            int precedingSlashes = 0;
            for (int cursor = slash - 1; cursor >= 0 && pattern.charAt(cursor) == '\\'; cursor--) {
                precedingSlashes++;
            }
            if ((precedingSlashes & 1) != 0 || slash + 3 >= pattern.length()) continue;

            char marker = pattern.charAt(slash + 1);
            if ((marker != 'p' && marker != 'P') || pattern.charAt(slash + 2) != '{') continue;
            int end = pattern.indexOf('}', slash + 3);
            if (end < 0) return;

            String property = pattern.substring(slash + 3, end).trim();
            if (property.startsWith("^")) property = property.substring(1).trim();
            if (isUserDefinedPropertyName(property)) {
                if (qualifyBareDiagnosticName && !property.contains("::")) {
                    deferredUserProperties().add("main::" + property);
                }
                // Preloading is an optimization for callbacks that are already
                // available. Perl permits qr// to contain a forward reference
                // to a user property; RegexPreprocessor represents that with a
                // placeholder and recompiles on first use. Calling the complete
                // translator here would turn that intentional forward reference
                // into an early "unsupported property" fatal before the
                // placeholder path can run.
                tryUserDefinedProperty(property, new LinkedHashSet<>(), caseInsensitive,
                        qualifyBareDiagnosticName);
            }
            slash = end;
        }
    }

    public static String translateUnicodeProperty(String property, boolean negated) {
        return translateUnicodeProperty(property, negated, new LinkedHashSet<>(), false);
    }

    static String translateUnicodePropertyForCharClass(String property, boolean negated) {
        String normalized = property.trim();
        if (normalized.startsWith("^")) {
            normalized = normalized.substring(1).trim();
            negated = !negated;
        }
        UnicodeSet set = resolveStandardPropertyAsSet(normalized, new LinkedHashSet<>());
        if (set == null) {
            throw new IllegalArgumentException(
                    "Invalid or unsupported Unicode property: " + property);
        }
        if (negated) set = new UnicodeSet(set).complement();
        return unicodeSetToJavaPattern(set);
    }

    static String translateUnicodeProperty(
            String property, boolean negated, boolean caseInsensitive) {
        return translateUnicodeProperty(property, negated, new LinkedHashSet<>(), caseInsensitive);
    }

    private static String translateUnicodeProperty(String property, boolean negated, Set<String> recursionSet) {
        return translateUnicodeProperty(property, negated, recursionSet, false);
    }

    private static String translateUnicodeProperty(String property, boolean negated,
                                                   Set<String> recursionSet,
                                                   boolean caseInsensitive) {
        try {
            // Perl accepts a leading caret inside the braces as property
            // negation: \p{^Latin} is equivalent to \P{Latin}, while
            // \P{^Latin} cancels the negation. Java does not accept the caret
            // form, so fold it into the outer negation flag before resolving.
            if (property.startsWith("^")) {
                property = property.substring(1).trim();
                negated = !negated;
            }
            boolean isPrefixedNumericWildcard =
                    isPerlIsPrefixedNumericWildcard(property);
            boolean isPrefixedJoiningGroupWildcard =
                    isPerlIsPrefixedJoiningGroupWildcard(property);
            boolean isPrefixedBlockWildcard =
                    isPerlIsPrefixedBlockWildcard(property);
            boolean isPrefixedScriptWildcard =
                    isPerlIsPrefixedScriptWildcard(property);
            property = normalizePerlIsPropertyAssignment(property);
            if (isPrefixedNumericWildcard) {
                throw new IllegalArgumentException(
                        "Is-prefixed Numeric_Value properties do not accept wildcard values");
            }
            if (isPrefixedJoiningGroupWildcard) {
                throw new IllegalArgumentException(
                        "Can't find Unicode property definition for Is-prefixed Joining_Group wildcard");
            }
            if (isPrefixedBlockWildcard) {
                throw new IllegalArgumentException(
                        "Can't find Unicode property definition for Is-prefixed Block wildcard");
            }
            if (isPrefixedScriptWildcard) {
                throw new IllegalArgumentException(
                        "Can't find Unicode property definition for Is-prefixed Script wildcard");
            }
            if (property.startsWith("utf8::")) {
                String userPropertyName = property.substring("utf8::".length());
                if (!userPropertyName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Illegal user-defined property name \"" + property + "\"");
                }
            }

            // User-defined properties require an exact Is/In prefix on the final
            // identifier. Lowercase variants are ordinary unknown properties.
            if (isUserDefinedPropertyName(property)) {
                String userProp = tryUserDefinedProperty(
                        property, recursionSet, caseInsensitive);
                if (userProp != null) {
                    return wrapCharClass(userProp, negated);
                }
                // Property not found - fall through to throw error below
            }

            UnicodeSet perlBuiltInAlias = resolvePerlBuiltInPropertyAlias(property);
            if (perlBuiltInAlias != null) {
                return wrapCharClass(unicodeSetToJavaPattern(perlBuiltInAlias), negated);
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

            String agePattern = translatePerlAgeProperty(property, negated);
            if (agePattern != null) return agePattern;

            // Is= is Perl shorthand for a property value. Keep Script=,
            // Block=/Blk=, and the age aliases as property/value pairs for ICU.
            if (property.startsWith("Is=")) {
                property = property.substring("Is=".length());
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
                        String userProp = tryUserDefinedProperty(property, recursionSet, false);
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
            String message = e.getMessage();
            if (message != null && (message.contains("in expansion of")
                    || message.startsWith("Illegal user-defined property name")
                    || message.startsWith("Can't find Unicode property definition")
                    || message.startsWith("Timeout waiting for another thread"))) {
                throw e;
            }
            throw new IllegalArgumentException("Invalid or unsupported Unicode property: " + property, e);
        }
    }

    static boolean isUserDefinedPropertyName(String property) {
        return property != null && USER_DEFINED_PROPERTY_NAME.matcher(property).matches();
    }

    static boolean isPerlBuiltInPropertyAlias(String property) {
        if (property == null) return false;
        return isPerlSpecialPropertyAlias(property.trim())
                || !normalizePerlIsPropertyAssignment(property).equals(property)
                || resolvePerlBuiltInPropertyAlias(property) != null;
    }

    /** Returns pinned Perl-property ranges and their native Joni fold policy. */
    static CharacterPropertyResolver.Result resolveJoniProperty(
            String property, boolean inCharacterClass) {
        if (property == null) return null;
        property = normalizePerlIsPropertyAssignment(property);
        int assignment = propertyValueDelimiter(property);
        if (assignment <= 0 || assignment == property.length() - 1) {
            return null;
        }
        String name = property.substring(0, assignment);
        String value = property.substring(assignment + 1);
        boolean caseFold;
        if (isGeneralCategoryProperty(name)) {
            caseFold = true;
        } else if (PerlUnicodeBlockData.isPropertyAlias(name)) {
            if (perlBlockWildcardBody(value) != null) return null;
            caseFold = false;
        } else if (PerlUnicodeScriptData.isScriptPropertyAlias(name)
                || PerlUnicodeScriptData.isScriptExtensionsPropertyAlias(name)) {
            if (perlNumericWildcardBody(value) != null) return null;
            caseFold = false;
        } else if (isCanonicalCombiningClassProperty(name)
                || PerlUnicodeBidiClassData.isPropertyAlias(name)
                || PerlUnicodeDecompositionTypeData.isPropertyAlias(name)
                || PerlUnicodeEastAsianWidthData.isPropertyAlias(name)) {
            caseFold = false;
        } else if (PerlUnicodeNumericValueData.isPropertyAlias(name)
                || PerlUnicodeJoiningGroupData.isPropertyAlias(name)) {
            if (perlNumericWildcardBody(value) != null) return null;
            caseFold = false;
        } else if (isPerlAgeProperty(name)) {
            if (isPerlAgeWildcard(value)) return null;
            caseFold = false;
        } else {
            return null;
        }

        // Joni currently folds a complete bracket expression as one class.
        // Keep no-fold families translated by the adapter inside brackets until
        // the AST can retain per-property fold policy through class composition.
        if (!caseFold && inCharacterClass) return null;

        UnicodeSet set = resolvePerlBuiltInPropertyAlias(property);
        if (set == null) return null;

        int[] ranges = new int[set.getRangeCount() * 2 + 1];
        ranges[0] = set.getRangeCount();
        for (int i = 0; i < set.getRangeCount(); i++) {
            ranges[i * 2 + 1] = set.getRangeStart(i);
            ranges[i * 2 + 2] = set.getRangeEnd(i);
        }
        return new CharacterPropertyResolver.Result(ranges, caseFold);
    }

    private static boolean isPerlSpecialPropertyAlias(String property) {
        return switch (property) {
            case "lb=cr", "lb=CR",
                    "XPosixSpace", "XPerlSpace", "SpacePerl", "Space", "White_Space",
                    "XPosixAlnum", "Alnum",
                    "XPosixAlpha", "Alpha", "Alphabetic",
                    "XPosixBlank", "Blank", "HorizSpace",
                    "XPosixCntrl", "Cc", "Cntrl", "Control",
                    "XPosixDigit", "Decimal_Number", "Digit", "Nd", "IsDigit",
                    "XPosixGraph", "Graph",
                    "XPosixLower", "Lower", "Lowercase", "IsLower",
                    "XPosixPrint", "Print",
                    "XPosixPunct", "Punct", "Punctuation",
                    "XPosixUpper", "Upper", "Uppercase", "IsUpper",
                    "Titlecase", "TitlecaseLetter", "Titlecase_Letter", "Lt",
                    "XPosixWord", "Word", "IsWord",
                    "XPosixXDigit", "Hex", "Hex_Digit", "XDigit",
                    "PosixAlnum", "PosixAlpha", "PosixBlank", "PosixCntrl",
                    "PosixDigit", "PosixGraph", "PosixLower", "PosixPrint",
                    "PosixPunct", "PosixSpace", "PosixUpper", "PosixWord",
                    "PosixXDigit",
                    "XIDS", "XIDStart", "XID_Start",
                    "XIDC", "XIDCont", "XID_Continue",
                    "_Perl_IDStart", "_Perl_IDCont" -> true;
            default -> false;
        };
    }

    private static UnicodeSet resolvePerlBuiltInPropertyAlias(String property) {
        if (property == null) return null;

        String alias = normalizePerlIsPropertyAssignment(property.trim());
        int assignment = propertyValueDelimiter(alias);
        if (assignment == alias.length() - 1
                && (PerlUnicodeScriptData.isScriptPropertyAlias(
                        alias.substring(0, assignment))
                    || PerlUnicodeScriptData.isScriptExtensionsPropertyAlias(
                        alias.substring(0, assignment)))) {
            throw new IllegalArgumentException(
                    "Unicode property wildcard not terminated");
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && isGeneralCategoryProperty(alias.substring(0, assignment))) {
            UnicodeSet category = PerlUnicodeGeneralCategoryData.resolve(
                    alias.substring(assignment + 1));
            if (category == null) {
                throw new IllegalArgumentException(
                        "Unsupported General_Category value: "
                                + alias.substring(assignment + 1).trim());
            }
            return category;
        }
        UnicodeSet age = resolvePerlAgeProperty(alias, true);
        if (age != null) return age;
        if (assignment > 0 && assignment < alias.length() - 1
                && isCanonicalCombiningClassProperty(alias.substring(0, assignment))) {
            UnicodeSet combiningClass = PerlUnicodeCombiningClassData.resolve(
                    alias.substring(assignment + 1));
            if (combiningClass == null) {
                throw new IllegalArgumentException(
                        "Unsupported Canonical_Combining_Class value: "
                                + alias.substring(assignment + 1).trim());
            }
            return combiningClass;
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeBidiClassData.isPropertyAlias(
                        alias.substring(0, assignment))) {
            UnicodeSet bidiClass = PerlUnicodeBidiClassData.valueSet(
                    alias.substring(assignment + 1));
            if (bidiClass == null) {
                throw new IllegalArgumentException(
                        "Unsupported Bidi_Class value: "
                                + alias.substring(assignment + 1).trim());
            }
            return bidiClass;
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeDecompositionTypeData.isPropertyAlias(
                        alias.substring(0, assignment))) {
            byte value = PerlUnicodeDecompositionTypeData.valueForAlias(
                    alias.substring(assignment + 1));
            if (value == PerlUnicodeDecompositionTypeData.INVALID) {
                throw new IllegalArgumentException(
                        "Unsupported Decomposition_Type value: "
                                + alias.substring(assignment + 1).trim());
            }
            return PERL_DECOMPOSITION_TYPE_SETS[value];
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeEastAsianWidthData.isPropertyAlias(
                        alias.substring(0, assignment))) {
            UnicodeSet eastAsianWidth = PerlUnicodeEastAsianWidthData.valueSet(
                    alias.substring(assignment + 1));
            if (eastAsianWidth == null) {
                throw new IllegalArgumentException(
                        "Unsupported East_Asian_Width value: "
                                + alias.substring(assignment + 1).trim());
            }
            return eastAsianWidth;
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeNumericValueData.isPropertyAlias(
                        alias.substring(0, assignment))) {
            UnicodeSet numericValue = resolvePerlNumericValue(
                    alias.substring(assignment + 1));
            if (numericValue == null) {
                throw new IllegalArgumentException(
                        "Unsupported Numeric_Value value: "
                                + alias.substring(assignment + 1).trim());
            }
            return numericValue;
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeJoiningGroupData.isPropertyAlias(
                        alias.substring(0, assignment))) {
            return resolvePerlJoiningGroup(alias.substring(assignment + 1));
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeBlockData.isPropertyAlias(
                        alias.substring(0, assignment))) {
            return resolvePerlBlock(alias.substring(assignment + 1));
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeScriptData.isScriptPropertyAlias(
                        alias.substring(0, assignment))) {
            return resolvePerlScript(alias.substring(assignment + 1), false);
        }
        if (assignment > 0 && assignment < alias.length() - 1
                && PerlUnicodeScriptData.isScriptExtensionsPropertyAlias(
                        alias.substring(0, assignment))) {
            return resolvePerlScript(alias.substring(assignment + 1), true);
        }
        if (assignment > 0 && assignment < alias.length() - 1) {
            Boolean value = perlBooleanPropertyValue(alias.substring(assignment + 1));
            if (value != null) {
                UnicodeSet binaryProperty = resolvePerlBuiltInPropertyAlias(
                        alias.substring(0, assignment));
                if (binaryProperty == null
                        && loosePropertyName(alias.substring(0, assignment))
                                .matches("(?:asciihexdigit|ahex)")) {
                    binaryProperty = new UnicodeSet()
                            .applyPropertyAlias("ASCII_Hex_Digit", "True");
                }
                if (binaryProperty != null) {
                    return value ? binaryProperty
                            : new UnicodeSet(binaryProperty).complement().freeze();
                }
            }
        }
        if (alias.equalsIgnoreCase("L&")) {
            UnicodeSet casedLetters = unicodePropertyValueSet(
                    UProperty.GENERAL_CATEGORY, "UppercaseLetter");
            casedLetters.addAll(unicodePropertyValueSet(
                    UProperty.GENERAL_CATEGORY, "LowercaseLetter"));
            casedLetters.addAll(unicodePropertyValueSet(
                    UProperty.GENERAL_CATEGORY, "TitlecaseLetter"));
            return casedLetters;
        }

        // Perl's bare script-value shortcuts use Script_Extensions semantics.
        // Keep binary and General_Category names ahead of this value namespace.
        String scriptShortcut = alias;
        if (alias.length() > 2 && alias.startsWith("Is")) {
            int valueStart = 2;
            while (valueStart < alias.length()) {
                char separator = alias.charAt(valueStart);
                if (!Character.isWhitespace(separator)
                        && separator != '-' && separator != '_') break;
                valueStart++;
            }
            if (valueStart < alias.length()) scriptShortcut = alias.substring(valueStart);
        }
        if (assignment < 0
                && !isIcuBinaryPropertyAlias(scriptShortcut)
                && !isIcuGeneralCategoryAlias(scriptShortcut)) {
            String canonicalScript = PerlUnicodeScriptData.canonicalValue(scriptShortcut);
            if (!"Katakana_Or_Hiragana".equals(canonicalScript)) {
                UnicodeSet scriptExtensions =
                        PerlUnicodeScriptData.scriptExtensionsSet(scriptShortcut);
                if (scriptExtensions != null) return scriptExtensions;
            }
        }

        String blockAlias = alias;
        boolean blockShortcut = false;
        boolean isBlockShortcut = false;
        if (alias.length() > 2 && alias.regionMatches(true, 0, "in", 0, 2)) {
            int valueStart = 2;
            while (valueStart < alias.length()) {
                char separator = alias.charAt(valueStart);
                if (!Character.isWhitespace(separator) && separator != '-' && separator != '_') break;
                valueStart++;
            }
            if (valueStart >= alias.length()) return null;
            blockAlias = alias.substring(valueStart);
            blockShortcut = true;
        } else if (assignment >= 0 || unicodePropertyValue(UProperty.SCRIPT, alias) >= 0) {
            return null;
        } else if (alias.length() > 2 && alias.regionMatches(true, 0, "is", 0, 2)) {
            int valueStart = 2;
            while (valueStart < alias.length()) {
                char separator = alias.charAt(valueStart);
                if (!Character.isWhitespace(separator)
                        && separator != '-' && separator != '_') break;
                valueStart++;
            }
            if (valueStart >= alias.length()) return null;
            String candidate = alias.substring(valueStart);
            if (unicodePropertyValue(UProperty.SCRIPT, candidate) >= 0) return null;
            if (isIcuBinaryPropertyAlias(candidate)
                    || isIcuGeneralCategoryAlias(candidate)) return null;
            blockAlias = candidate;
            blockShortcut = true;
            isBlockShortcut = true;
        }
        if (!blockShortcut && (isIcuBinaryPropertyAlias(blockAlias)
                || isIcuGeneralCategoryAlias(blockAlias))) return null;
        UnicodeSet block = PerlUnicodeBlockData.set(blockAlias);
        if (isBlockShortcut && block != null && block.containsSome(0xD800, 0xDFFF)) {
            // Joni's UTF-8 subject path cannot represent an isolated surrogate.
            // Retain the established deferred single-Is behavior until that
            // representation debt is closed; explicit Block=/In forms remain pinned.
            return null;
        }
        return block;
    }

    private static Boolean perlBooleanPropertyValue(String value) {
        return switch (loosePropertyName(value)) {
            case "true", "yes", "y", "t" -> true;
            case "false", "no", "n", "f" -> false;
            default -> null;
        };
    }

    private static boolean isIcuBinaryPropertyAlias(String alias) {
        try {
            new UnicodeSet().applyPropertyAlias(alias, "True");
            return true;
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    private static boolean isIcuGeneralCategoryAlias(String alias) {
        try {
            new UnicodeSet().applyPropertyAlias("General_Category", alias);
            return true;
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    private static boolean isPerlIsPrefixedNumericWildcard(String property) {
        int assignment = propertyValueDelimiter(property);
        if (assignment <= 0 || assignment == property.length() - 1) return false;
        String name = property.substring(0, assignment);
        String propertyName = exactIsPrefixedPropertyName(name);
        return propertyName != null
                && PerlUnicodeNumericValueData.isPropertyAlias(propertyName)
                && perlNumericWildcardBody(property.substring(assignment + 1)) != null;
    }

    private static boolean isPerlIsPrefixedJoiningGroupWildcard(String property) {
        int assignment = propertyValueDelimiter(property);
        if (assignment <= 0 || assignment == property.length() - 1) return false;
        String name = property.substring(0, assignment);
        String propertyName = exactIsPrefixedPropertyName(name);
        return propertyName != null
                && PerlUnicodeJoiningGroupData.isPropertyAlias(propertyName)
                && perlNumericWildcardBody(property.substring(assignment + 1)) != null;
    }

    private static boolean isPerlIsPrefixedBlockWildcard(String property) {
        int assignment = propertyValueDelimiter(property);
        if (assignment <= 0 || assignment == property.length() - 1) return false;
        String propertyName = exactIsPrefixedPropertyName(
                property.substring(0, assignment));
        return propertyName != null
                && PerlUnicodeBlockData.isPropertyAlias(propertyName)
                && perlBlockWildcardBody(property.substring(assignment + 1)) != null;
    }

    private static boolean isPerlIsPrefixedScriptWildcard(String property) {
        int assignment = propertyValueDelimiter(property);
        if (assignment <= 0 || assignment == property.length() - 1) return false;
        String propertyName = exactIsPrefixedPropertyName(
                property.substring(0, assignment));
        return propertyName != null
                && (PerlUnicodeScriptData.isScriptPropertyAlias(propertyName)
                    || PerlUnicodeScriptData.isScriptExtensionsPropertyAlias(propertyName))
                && perlNumericWildcardBody(property.substring(assignment + 1)) != null;
    }

    private static String exactIsPrefixedPropertyName(String name) {
        if (name.length() <= 2 || name.charAt(0) != 'I' || name.charAt(1) != 's') {
            return null;
        }
        int start = 2;
        while (start < name.length()) {
            char separator = name.charAt(start);
            if (!Character.isWhitespace(separator)
                    && separator != '-' && separator != '_') {
                break;
            }
            start++;
        }
        return start == name.length() ? null : name.substring(start);
    }

    private static UnicodeSet resolvePerlJoiningGroup(String value) {
        String wildcard = perlNumericWildcardBody(value);
        if (wildcard == null) {
            UnicodeSet exact = PerlUnicodeJoiningGroupData.valueSet(value);
            if (exact == null) {
                throw new IllegalArgumentException(
                        "Unsupported Joining_Group value: " + value.trim());
            }
            return exact;
        }
        if (wildcard.indexOf('*') >= 0) {
            throw new IllegalArgumentException(
                    "quantifier '*' is not allowed in Unicode property value wildcard");
        }

        Pattern valuePattern;
        try {
            valuePattern = Pattern.compile(wildcard);
        } catch (RuntimeException invalidPattern) {
            throw new IllegalArgumentException(
                    "Invalid Unicode property value wildcard", invalidPattern);
        }

        UnicodeSet result = new UnicodeSet();
        for (String candidate : PerlUnicodeJoiningGroupData.wildcardValues()) {
            if (valuePattern.matcher(candidate).matches()
                    || valuePattern.matcher(loosePropertyName(candidate)).matches()) {
                result.addAll(PerlUnicodeJoiningGroupData.valueSet(candidate));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Unicode property value wildcard matches Joining_Group");
        }
        return result.freeze();
    }

    private static UnicodeSet resolvePerlBlock(String value) {
        String wildcard = perlBlockWildcardBody(value);
        if (wildcard == null) {
            UnicodeSet exact = PerlUnicodeBlockData.set(value);
            if (exact == null) {
                throw new IllegalArgumentException(
                        "Unsupported Block value: " + value.trim());
            }
            return exact;
        }
        if (wildcard.indexOf('*') >= 0) {
            throw new IllegalArgumentException(
                    "quantifier '*' is not allowed in Unicode property value wildcard");
        }

        Pattern valuePattern;
        try {
            valuePattern = Pattern.compile(wildcard);
        } catch (RuntimeException invalidPattern) {
            throw new IllegalArgumentException(
                    "Invalid Unicode property value wildcard", invalidPattern);
        }

        UnicodeSet result = new UnicodeSet();
        for (int valueId = 0; valueId < PerlUnicodeBlockData.valueCount(); valueId++) {
            String candidate = PerlUnicodeBlockData.canonicalValue(valueId);
            if (valuePattern.matcher(candidate).matches()
                    || valuePattern.matcher(loosePropertyName(candidate)).matches()) {
                result.addAll(PerlUnicodeBlockData.set(valueId));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Unicode property value wildcard matches Block");
        }
        return result.freeze();
    }

    private static UnicodeSet resolvePerlScript(String value, boolean extensions) {
        String wildcard = perlNumericWildcardBody(value);
        if (wildcard == null) {
            if ("Katakana_Or_Hiragana".equals(
                    PerlUnicodeScriptData.canonicalValue(value))) {
                throw new IllegalArgumentException(
                        "Can't find Unicode property definition \""
                                + (extensions ? "Script_Extensions" : "Script")
                                + "=" + value.trim() + "\"");
            }
            UnicodeSet exact = extensions
                    ? PerlUnicodeScriptData.scriptExtensionsSet(value)
                    : PerlUnicodeScriptData.scriptSet(value);
            if (exact == null) {
                throw new IllegalArgumentException(
                        "Unsupported " + (extensions ? "Script_Extensions" : "Script")
                                + " value: " + value.trim());
            }
            return exact;
        }
        if (wildcard.indexOf('*') >= 0) {
            throw new IllegalArgumentException(
                    "quantifier '*' is not allowed in Unicode property value wildcard");
        }

        Pattern valuePattern;
        try {
            valuePattern = Pattern.compile(wildcard);
        } catch (RuntimeException invalidPattern) {
            throw new IllegalArgumentException(
                    "Invalid Unicode property value wildcard", invalidPattern);
        }

        UnicodeSet result = new UnicodeSet();
        for (String candidate : PerlUnicodeScriptData.wildcardValues()) {
            if ("Katakana_Or_Hiragana".equals(
                    PerlUnicodeScriptData.canonicalValue(candidate))) {
                continue;
            }
            if (valuePattern.matcher(candidate).matches()
                    || valuePattern.matcher(loosePropertyName(candidate)).matches()) {
                UnicodeSet match = extensions
                        ? PerlUnicodeScriptData.scriptExtensionsSet(candidate)
                        : PerlUnicodeScriptData.scriptSet(candidate);
                if (match != null) result.addAll(match);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "No Unicode property value wildcard matches "
                            + (extensions ? "Script_Extensions" : "Script"));
        }
        return result.freeze();
    }

    private static String perlBlockWildcardBody(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("#") || !trimmed.endsWith("#")
                || trimmed.length() <= 2) {
            return null;
        }
        String body = trimmed.substring(1, trimmed.length() - 1);
        if (body.startsWith("\\A") && body.endsWith("\\z")
                && body.length() > 4) {
            return body.substring(2, body.length() - 2);
        }
        return body;
    }

    private static UnicodeSet resolvePerlNumericValue(String value) {
        String wildcard = perlNumericWildcardBody(value);
        if (wildcard != null) {
            Pattern valuePattern;
            try {
                valuePattern = Pattern.compile(wildcard);
            } catch (RuntimeException invalidPattern) {
                return null;
            }
            UnicodeSet result = new UnicodeSet();
            for (int index = 0; index < PerlUnicodeNumericValueData.valueCount(); index++) {
                if (valuePattern.matcher(
                        PerlUnicodeNumericValueData.canonicalValue(index)).matches()) {
                    result.addAll(PerlUnicodeNumericValueData.set(index));
                }
            }
            if (valuePattern.matcher("NaN").matches()
                    || valuePattern.matcher("nan").matches()) {
                result.addAll(PerlUnicodeNumericValueData.nanSet());
            }
            return result.isEmpty() ? null : result.freeze();
        }

        if (loosePropertyName(value).equals("nan")) {
            return PerlUnicodeNumericValueData.nanSet();
        }

        short index = perlNumericValueIndex(value);
        return index == PerlUnicodeNumericValueData.INVALID
                ? null
                : PerlUnicodeNumericValueData.set(index);
    }

    private static String perlNumericWildcardBody(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("/\\A") && trimmed.endsWith("\\z/")
                && trimmed.length() > 6) {
            return trimmed.substring(3, trimmed.length() - 3);
        }
        if (trimmed.startsWith(":\\A") && trimmed.endsWith("\\z:")
                && trimmed.length() > 6) {
            return trimmed.substring(3, trimmed.length() - 3);
        }
        return null;
    }

    private static short perlNumericValueIndex(String value) {
        try {
            if (value.indexOf('/') >= 0) {
                return perlRationalValueIndex(value);
            }

            String normalized = removePerlNumericLooseCharacters(value);
            if (!normalized.matches("(?:\\+?[0-9]+(?:\\.[0-9]*)?"
                    + "|-[0-9]*(?:\\.[0-9]+)?)(?:[eE][+-]?[0-9]+)?")) {
                return PerlUnicodeNumericValueData.INVALID;
            }
            BigDecimal decimal = new BigDecimal(normalized);
            BigInteger numerator = decimal.unscaledValue();
            BigInteger denominator = BigInteger.ONE;
            if (decimal.scale() > 0) {
                denominator = BigInteger.TEN.pow(decimal.scale());
            } else if (decimal.scale() < 0) {
                numerator = numerator.multiply(BigInteger.TEN.pow(-decimal.scale()));
            }
            short exact = rationalValueIndex(numerator, denominator);
            return exact != PerlUnicodeNumericValueData.INVALID
                    ? exact
                    : PerlUnicodeNumericValueData.valueForDecimal(decimal);
        } catch (ArithmeticException | NumberFormatException invalidValue) {
            return PerlUnicodeNumericValueData.INVALID;
        }
    }

    private static short perlRationalValueIndex(String value) {
        value = value.trim();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == ' ' || character >= '\t' && character <= '\r') {
                return PerlUnicodeNumericValueData.INVALID;
            }
        }
        String[] parts = value.split("/", -1);
        if (parts.length != 2 || parts[0].endsWith("_")) {
            return PerlUnicodeNumericValueData.INVALID;
        }
        String numeratorText = parts[0].replace("_", "");
        String denominatorText = parts[1];
        if (denominatorText.startsWith("+")) denominatorText = denominatorText.substring(1);
        while (denominatorText.startsWith("_")) denominatorText = denominatorText.substring(1);
        denominatorText = denominatorText.replace("_", "");
        if (!numeratorText.matches("[+-]?[0-9]+")
                || !denominatorText.matches("[0-9]+")) {
            return PerlUnicodeNumericValueData.INVALID;
        }
        return rationalValueIndex(
                new BigInteger(numeratorText), new BigInteger(denominatorText));
    }

    private static short rationalValueIndex(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) return PerlUnicodeNumericValueData.INVALID;
        BigInteger divisor = numerator.gcd(denominator);
        numerator = numerator.divide(divisor);
        denominator = denominator.divide(divisor);
        if (numerator.bitLength() > 63 || denominator.bitLength() > 63) {
            return PerlUnicodeNumericValueData.INVALID;
        }
        return PerlUnicodeNumericValueData.valueForRational(
                numerator.longValueExact(), denominator.longValueExact());
    }

    private static String removePerlNumericLooseCharacters(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '_' || character == ' '
                    || character >= '\t' && character <= '\r') continue;
            normalized.append(character);
        }
        return normalized.toString();
    }

    private static boolean isGeneralCategoryProperty(String property) {
        return switch (loosePropertyName(property)) {
            case "gc", "generalcategory", "category" -> true;
            default -> false;
        };
    }

    private static boolean isCanonicalCombiningClassProperty(String property) {
        return switch (loosePropertyName(property)) {
            case "ccc", "canonicalcombiningclass" -> true;
            default -> false;
        };
    }

    private static UnicodeSet[] buildPerlDecompositionTypeSets() {
        UnicodeSet[] sets = new UnicodeSet[
                PerlUnicodeDecompositionTypeData.NON_CANONICAL + 1];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
        for (int i = 0; i < PerlUnicodeDecompositionTypeData.rangeCount(); i++) {
            int start = PerlUnicodeDecompositionTypeData.rangeStart(i);
            int end = PerlUnicodeDecompositionTypeData.rangeEnd(i);
            byte value = PerlUnicodeDecompositionTypeData.rangeValue(i);
            sets[value].add(start, end);
            if (PerlUnicodeDecompositionTypeData.matches(
                    value, PerlUnicodeDecompositionTypeData.NON_CANONICAL)) {
                sets[PerlUnicodeDecompositionTypeData.NON_CANONICAL].add(start, end);
            }
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private static int propertyValueDelimiter(String property) {
        int equals = property.indexOf('=');
        if (equals >= 0) return equals;
        for (int i = 1; i < property.length() - 1; i++) {
            if (property.charAt(i) == ':'
                    && property.charAt(i - 1) != ':'
                    && property.charAt(i + 1) != ':') {
                return i;
            }
        }
        return -1;
    }

    private static String loosePropertyName(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch) && ch != '-' && ch != '_') {
                normalized.append(Character.toLowerCase(ch));
            }
        }
        return normalized.toString();
    }

    /**
     * Perl accepts an {@code Is_} prefix before property names in explicit
     * property/value forms, and accepts a colon in place of the equals sign.
     * The prefix is distinct from a bare {@code IsFoo} user property because
     * this normalization applies only when a value delimiter is present.
     */
    private static String normalizePerlIsPropertyAssignment(String property) {
        int delimiter = property.indexOf('=');
        if (delimiter < 0) {
            for (int i = 1; i < property.length() - 1; i++) {
                if (property.charAt(i) == ':'
                        && property.charAt(i - 1) != ':'
                        && property.charAt(i + 1) != ':') {
                    delimiter = i;
                    break;
                }
            }
        }
        if (delimiter <= 0 || delimiter == property.length() - 1) {
            return property;
        }

        String name = property.substring(0, delimiter);
        // Perl's assignment prefix is exactly "Is". Property and value
        // aliases use loose matching after that prefix, but the prefix itself
        // remains case-sensitive (for example, is_dt=Can is invalid).
        if (name.length() < 2 || name.charAt(0) != 'I' || name.charAt(1) != 's') {
            return property;
        }
        String looseName = loosePropertyName(name);
        if (looseName.length() == 2) {
            return property;
        }

        int nameStart = 2;
        while (nameStart < name.length()) {
            char separator = name.charAt(nameStart);
            if (!Character.isWhitespace(separator)
                    && separator != '-' && separator != '_') {
                break;
            }
            nameStart++;
        }
        if (nameStart >= name.length()) return property;
        return name.substring(nameStart) + "=" + property.substring(delimiter + 1);
    }

    private static UnicodeSet unicodePropertyValueSet(int property, String alias) {
        int value = unicodePropertyValue(property, alias);
        if (value < 0) return null;
        return new UnicodeSet().applyIntPropertyValue(property, value);
    }

    private static int unicodePropertyValue(int property, String alias) {
        try {
            return UCharacter.getPropertyValueEnum(property, alias);
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
    }

    private static String translatePerlAgeProperty(String property, boolean negated) {
        UnicodeSet result = resolvePerlAgeProperty(property, true);
        return result == null ? null
                : wrapCharClass(unicodeSetToJavaPattern(result), negated);
    }

    private static UnicodeSet resolvePerlAgeProperty(
            String property, boolean allowWildcard) {
        property = normalizePerlIsPropertyAssignment(property);
        int delimiter = propertyValueDelimiter(property);
        if (delimiter <= 0 || delimiter == property.length() - 1) return null;

        String name = property.substring(0, delimiter);
        boolean exact;
        String looseName = loosePropertyName(name);
        if (looseName.equals("age")) {
            exact = true;
        } else if (looseName.equals("in") || looseName.equals("presentin")) {
            exact = false;
        } else {
            return null;
        }

        String value = property.substring(delimiter + 1);
        if (!allowWildcard && isPerlAgeWildcard(value)) return null;
        String requested = normalizeUnicodeAgeVersion(value);
        if (requested.equalsIgnoreCase("NA") || requested.equalsIgnoreCase("Unassigned")) {
            return PerlUnicodeAgeData.unassignedSet();
        }

        UnicodeSet result = exact
                ? PerlUnicodeAgeData.exactSet(requested)
                : PerlUnicodeAgeData.cumulativeSet(requested);
        if (result == null) {
            throw new IllegalArgumentException("Unsupported Unicode age version: " + requested);
        }
        return result;
    }

    private static boolean isPerlAgeProperty(String name) {
        String looseName = loosePropertyName(name);
        return looseName.equals("age") || looseName.equals("in")
                || looseName.equals("presentin");
    }

    private static boolean isPerlAgeWildcard(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith(":\\A") && trimmed.endsWith("\\z:");
    }

    private static String normalizeUnicodeAgeVersion(String value) {
        String normalized = value.trim();
        if (normalized.startsWith(":\\A") && normalized.endsWith("\\z:")
                && normalized.length() > 6) {
            normalized = normalized.substring(3, normalized.length() - 3);
        }
        normalized = normalized.replaceAll("[\\s_+\\-]", "");
        if (normalized.equalsIgnoreCase("NA")
                || normalized.equalsIgnoreCase("Unassigned")) return normalized;
        if (normalized.length() > 1
                && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')) {
            normalized = normalized.substring(1);
            if (!normalized.contains(".")) {
                if (normalized.length() < 2) return normalized;
                normalized = normalized.substring(0, normalized.length() - 1)
                        + "." + normalized.charAt(normalized.length() - 1);
            }
        }
        if (!normalized.contains(".")) normalized += ".0";
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("[0-9]+")
                || !parts[1].matches("[0-9]+")) return normalized;
        try {
            return Integer.parseInt(parts[0]) + "." + Integer.parseInt(parts[1]);
        } catch (NumberFormatException invalidVersion) {
            return normalized;
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
        if (pattern.isEmpty()) {
            return negated
                    ? "[\\x{0}-\\x{10FFFF}]"
                    : "[^\\x{0}-\\x{10FFFF}]";
        }
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
        if (codePoint >= 0x10000
                || (codePoint >= Character.MIN_SURROGATE
                        && codePoint <= Character.MAX_SURROGATE)) {
            // Use \x{XXXX} for supplementary characters and surrogate code
            // points. Literal unpaired surrogates cannot be encoded losslessly
            // for Joni and can turn an otherwise valid range into an empty one.
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

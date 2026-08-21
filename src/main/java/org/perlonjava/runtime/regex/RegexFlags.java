package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

/**
 * @param isGlobalMatch        g flag - match globally (find all occurrences)
 * @param keepCurrentPosition  c flag - continue matching from last match position
 * @param isNonDestructive     r flag - non-destructive match (leaves target string unchanged)
 * @param isMatchExactlyOnce   m?PAT? flag - match pattern exactly once
 * @param useGAssertion        \G assertion - match must occur at previous match end
 * @param isExtendedWhitespace xx flag - ignore whitespace and comments in pattern
 * @param isNonCapturing       n flag - make groups non-capturing by default
 * @param isOptimized          o flag - compile pattern only once
 * @param isCaseInsensitive    i flag - case insensitive matching
 * @param isMultiLine          m flag - multiline mode (^ and $ match line boundaries)
 * @param isDotAll             s flag - dot matches all characters including newline
 * @param isExtended           x flag - ignore whitespace and # comments in pattern
 * @param preservesMatch       p flag - preserve match after failed matches
 * @param isUnicode            u flag - Unicode semantics (\w, \d, \s match Unicode)
 * @param isAscii              a flag - ASCII-restrict (\w, \d, \s match only ASCII)
 * @param isAsciiStrict        aa flags - also forbid ASCII/non-ASCII case-fold crossings
 * @param isLocale             l flag - locale-dependent class provenance
 */
public record RegexFlags(boolean isGlobalMatch, boolean keepCurrentPosition, boolean isNonDestructive,
                         boolean isMatchExactlyOnce, boolean useGAssertion, boolean isExtendedWhitespace,
                         boolean isNonCapturing, boolean isOptimized, boolean isCaseInsensitive, boolean isMultiLine,
                         boolean isDotAll, boolean isExtended, boolean preservesMatch, boolean isUnicode,
                         boolean isAscii, boolean isAsciiStrict, boolean isLocale,
                         boolean allowEvalGroup, boolean taintResults) {

    /** Compatibility constructor predating explicit locale provenance. */
    public RegexFlags(boolean isGlobalMatch, boolean keepCurrentPosition,
            boolean isNonDestructive, boolean isMatchExactlyOnce,
            boolean useGAssertion, boolean isExtendedWhitespace,
            boolean isNonCapturing, boolean isOptimized,
            boolean isCaseInsensitive, boolean isMultiLine, boolean isDotAll,
            boolean isExtended, boolean preservesMatch, boolean isUnicode,
            boolean isAscii, boolean isAsciiStrict, boolean allowEvalGroup,
            boolean taintResults) {
        this(isGlobalMatch, keepCurrentPosition, isNonDestructive,
                isMatchExactlyOnce, useGAssertion, isExtendedWhitespace,
                isNonCapturing, isOptimized, isCaseInsensitive, isMultiLine,
                isDotAll, isExtended, preservesMatch, isUnicode, isAscii,
                isAsciiStrict, false, allowEvalGroup, taintResults);
    }

    public static RegexFlags fromModifiers(String modifiers, String patternString) {
        // m?PAT? is encoded by StringParser as an extra trailing '?' on the modifier string
        // (see parseRegexMatch).  Do NOT use modifiers.contains("?"): '?' appears inside many
        // ordinary patterns (e.g. (?:...) or ...?) and must not enable match-once mode for those.
        boolean matchOnce = modifiers != null && modifiers.endsWith("?");
        return new RegexFlags(
                modifiers.contains("g"),
                modifiers.contains("c"),
                modifiers.contains("r"),
                matchOnce,
                patternString != null && patternString.contains("\\G"),
                modifiers.contains("xx"),
                modifiers.contains("n"),
                modifiers.contains("o"),
                modifiers.contains("i"),
                modifiers.contains("m"),
                modifiers.contains("s"),
                modifiers.contains("x"),
                modifiers.contains("p"),
                modifiers.contains("u"),
                modifiers.contains("a"),
                modifiers.indexOf('a') >= 0
                        && modifiers.indexOf('a', modifiers.indexOf('a') + 1) >= 0,
                modifiers.contains("l"),
                modifiers.contains("E"),
                modifiers.contains("T")
        );
    }

    public static void validateModifiers(String modifiers) {
        // Valid modifiers based on what's actually handled in fromModifiers
        String validModifiers = "gcr?noimsxpadeulET"; // E/T are internal lexical flags

        for (int i = 0; i < modifiers.length(); i++) {
            char modifier = modifiers.charAt(i);

            // Handle 'xx' as a special case (two characters)
            if (modifier == 'x' && i + 1 < modifiers.length() && modifiers.charAt(i + 1) == 'x') {
                i++; // Skip the second 'x'
                continue;
            }

            if (validModifiers.indexOf(modifier) == -1) {
                throw new PerlCompilerException("Unknown regexp modifier \"/" + modifier + "\"");
            }
        }
    }

    /**
     * Finds a positive inline Perl {@code p} modifier without treating escaped
     * text or character-class contents as modifier groups. Match-variable
     * retention is PerlOnJava source policy rather than a Joni matcher option.
     */
    static boolean hasInlinePreserveModifier(String pattern) {
        if (pattern == null || pattern.length() < 4) return false;

        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i + 3 < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                inClass = true;
                continue;
            }
            if (c == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass || c != '(' || pattern.charAt(i + 1) != '?') continue;

            boolean negative = false;
            for (int j = i + 2; j < pattern.length(); j++) {
                char modifier = pattern.charAt(j);
                if (modifier == ':' || modifier == ')') break;
                if (modifier == '-') {
                    negative = true;
                    continue;
                }
                if (modifier == '^') continue;
                if (modifier < 'a' || modifier > 'z') break;
                if (modifier == 'p' && !negative) return true;
            }
        }
        return false;
    }

    public RegexFlags with(String positiveFlags, String negativeFlags) {
        boolean newFlagN = this.isNonCapturing;
        boolean newIsCaseInsensitive = this.isCaseInsensitive;
        boolean newIsMultiLine = this.isMultiLine;
        boolean newIsDotAll = this.isDotAll;
        boolean newIsExtended = this.isExtended;
        boolean newPreservesMatch = this.preservesMatch;
        boolean newIsUnicode = this.isUnicode;
        boolean newIsAscii = this.isAscii;
        boolean newIsAsciiStrict = this.isAsciiStrict;
        boolean newIsLocale = this.isLocale;

        // Handle positive flags
        if (positiveFlags.indexOf('n') >= 0) newFlagN = true;
        if (positiveFlags.indexOf('i') >= 0) newIsCaseInsensitive = true;
        if (positiveFlags.indexOf('m') >= 0) newIsMultiLine = true;
        if (positiveFlags.indexOf('s') >= 0) newIsDotAll = true;
        if (positiveFlags.indexOf('x') >= 0) newIsExtended = true;
        if (positiveFlags.indexOf('p') >= 0) newPreservesMatch = true;
        if (positiveFlags.indexOf('u') >= 0) {
            newIsUnicode = true;
            newIsLocale = false;
        }
        if (positiveFlags.indexOf('l') >= 0) {
            newIsLocale = true;
            newIsUnicode = false;
            newIsAscii = false;
            newIsAsciiStrict = false;
        }
        if (positiveFlags.indexOf('a') >= 0) {
            newIsAscii = true;
            newIsLocale = false;
            int firstA = positiveFlags.indexOf('a');
            if (positiveFlags.indexOf('a', firstA + 1) >= 0) newIsAsciiStrict = true;
        }

        // Handle negative flags
        if (negativeFlags.indexOf('n') >= 0) newFlagN = false;
        if (negativeFlags.indexOf('i') >= 0) newIsCaseInsensitive = false;
        if (negativeFlags.indexOf('m') >= 0) newIsMultiLine = false;
        if (negativeFlags.indexOf('s') >= 0) newIsDotAll = false;
        if (negativeFlags.indexOf('x') >= 0) newIsExtended = false;
        if (negativeFlags.indexOf('u') >= 0) newIsUnicode = false;
        if (negativeFlags.indexOf('a') >= 0) {
            newIsAscii = false;
            newIsAsciiStrict = false;
        }

        return new RegexFlags(
                this.isGlobalMatch,
                this.keepCurrentPosition,
                this.isNonDestructive,
                this.isMatchExactlyOnce,
                this.useGAssertion,
                this.isExtendedWhitespace,
                newFlagN,
                this.isOptimized,
                newIsCaseInsensitive,
                newIsMultiLine,
                newIsDotAll,
                newIsExtended,
                newPreservesMatch,
                newIsUnicode,
                newIsAscii,
                newIsAsciiStrict,
                newIsLocale,
                this.allowEvalGroup,
                this.taintResults
        );
    }

    public String toFlagString() {
        StringBuilder flagString = new StringBuilder();

        if (isGlobalMatch) flagString.append('g');
        if (preservesMatch) flagString.append('p');
        if (isAscii) flagString.append(isAsciiStrict ? "aa" : "a");
        if (isUnicode) flagString.append('u');
        if (isLocale) flagString.append('l');
        if (isMultiLine) flagString.append('m');
        if (isDotAll) flagString.append('s');
        if (isCaseInsensitive) flagString.append('i');
        if (isExtendedWhitespace) flagString.append("xx");
        else if (isExtended) flagString.append('x');
        if (isNonCapturing) flagString.append('n');
        if (isNonDestructive) flagString.append('r');
        if (taintResults) flagString.append('T');

        return flagString.toString();
    }

    /**
     * Returns the modifier string as Perl's regexp_pattern() would return it.
     * Only includes pattern-level modifiers (a, u, m, s, i, x, n), not
     * match-level ones like g, p, r.
     */
    public String toModifierString() {
        StringBuilder sb = new StringBuilder();
        if (isAscii) sb.append(isAsciiStrict ? "aa" : "a");
        if (isUnicode) sb.append('u');
        if (isLocale) sb.append('l');
        if (isMultiLine) sb.append('m');
        if (isDotAll) sb.append('s');
        if (isCaseInsensitive) sb.append('i');
        if (isExtendedWhitespace) sb.append("xx");
        else if (isExtended) sb.append('x');
        if (isNonCapturing) sb.append('n');
        return sb.toString();
    }
}

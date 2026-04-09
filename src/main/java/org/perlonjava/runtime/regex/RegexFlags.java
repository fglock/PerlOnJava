package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

import static java.util.regex.Pattern.*;

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
 */
public record RegexFlags(boolean isGlobalMatch, boolean keepCurrentPosition, boolean isNonDestructive,
                         boolean isMatchExactlyOnce, boolean useGAssertion, boolean isExtendedWhitespace,
                         boolean isNonCapturing, boolean isOptimized, boolean isCaseInsensitive, boolean isMultiLine,
                         boolean isDotAll, boolean isExtended, boolean preservesMatch, boolean isUnicode,
                         boolean isAscii) {

    public static RegexFlags fromModifiers(String modifiers, String patternString) {
        return new RegexFlags(
                modifiers.contains("g"),
                modifiers.contains("c"),
                modifiers.contains("r"),
                modifiers.contains("?"),
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
                modifiers.contains("a")
        );
    }

    public static void validateModifiers(String modifiers) {
        // Valid modifiers based on what's actually handled in fromModifiers
        String validModifiers = "gcr?noimsxpadeul"; // Add 'xx' handling separately, 'l' for locale

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

    public int toPatternFlags() {
        int flags = 0;
        
        // UNIX_LINES ensures that . only excludes \n (not \r, \u0085, etc.)
        // This matches Perl's behavior where . excludes only \n
        flags |= UNIX_LINES;
        
        // /u flag enables Unicode semantics for \w, \d, \s
        // /a flag (ASCII-restrict) disables Unicode semantics
        if (isUnicode && !isAscii) {
            flags |= UNICODE_CHARACTER_CLASS;
        }
        
        if (isCaseInsensitive) {
            flags |= CASE_INSENSITIVE;
            // For Unicode case-insensitive matching, add UNICODE_CASE
            // But NOT if /a flag (ASCII-restrict) is set - /a restricts case folding to ASCII
            if (!isAscii) {
                flags |= UNICODE_CASE;
            }
        }
        if (isMultiLine) {
            flags |= MULTILINE;
        }
        if (isDotAll) {
            flags |= DOTALL;
        }
        if (isExtended) {
            flags |= COMMENTS;
        }
        return flags;
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

        // Handle positive flags
        if (positiveFlags.indexOf('n') >= 0) newFlagN = true;
        if (positiveFlags.indexOf('i') >= 0) newIsCaseInsensitive = true;
        if (positiveFlags.indexOf('m') >= 0) newIsMultiLine = true;
        if (positiveFlags.indexOf('s') >= 0) newIsDotAll = true;
        if (positiveFlags.indexOf('x') >= 0) newIsExtended = true;
        if (positiveFlags.indexOf('p') >= 0) newPreservesMatch = true;
        if (positiveFlags.indexOf('u') >= 0) newIsUnicode = true;
        if (positiveFlags.indexOf('a') >= 0) newIsAscii = true;

        // Handle negative flags
        if (negativeFlags.indexOf('n') >= 0) newFlagN = false;
        if (negativeFlags.indexOf('i') >= 0) newIsCaseInsensitive = false;
        if (negativeFlags.indexOf('m') >= 0) newIsMultiLine = false;
        if (negativeFlags.indexOf('s') >= 0) newIsDotAll = false;
        if (negativeFlags.indexOf('x') >= 0) newIsExtended = false;
        if (negativeFlags.indexOf('u') >= 0) newIsUnicode = false;
        if (negativeFlags.indexOf('a') >= 0) newIsAscii = false;

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
                newIsAscii
        );
    }

    public String toFlagString() {
        StringBuilder flagString = new StringBuilder();

        if (isGlobalMatch) flagString.append('g');
        if (preservesMatch) flagString.append('p');
        if (isCaseInsensitive) flagString.append('i');
        if (isMultiLine) flagString.append('m');
        if (isDotAll) flagString.append('s');
        if (isNonCapturing) flagString.append('n');
        if (isExtended) flagString.append('x');
        if (isNonDestructive) flagString.append('r');
        if (isUnicode) flagString.append('u');
        if (isAscii) flagString.append('a');

        return flagString.toString();
    }

    /**
     * Returns the modifier string as Perl's regexp_pattern() would return it.
     * Only includes pattern-level modifiers (i, m, s, x, n, a, u), not
     * match-level ones like g, p, r.
     */
    public String toModifierString() {
        StringBuilder sb = new StringBuilder();
        if (isMultiLine) sb.append('m');
        if (isDotAll) sb.append('s');
        if (isCaseInsensitive) sb.append('i');
        if (isExtended) sb.append('x');
        if (isNonCapturing) sb.append('n');
        if (isAscii) sb.append('a');
        if (isUnicode) sb.append('u');
        return sb.toString();
    }
}

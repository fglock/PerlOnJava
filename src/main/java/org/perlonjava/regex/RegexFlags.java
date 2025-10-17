package org.perlonjava.regex;

import org.perlonjava.runtime.PerlCompilerException;

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
 */
public record RegexFlags(boolean isGlobalMatch, boolean keepCurrentPosition, boolean isNonDestructive,
                         boolean isMatchExactlyOnce, boolean useGAssertion, boolean isExtendedWhitespace,
                         boolean isNonCapturing, boolean isOptimized, boolean isCaseInsensitive, boolean isMultiLine,
                         boolean isDotAll, boolean isExtended, boolean preservesMatch) {

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
                modifiers.contains("p")
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
        if (isCaseInsensitive) {
            // For proper Unicode case-insensitive matching, we need both flags:
            // - CASE_INSENSITIVE: enables case-insensitive matching
            // - UNICODE_CASE: enables Unicode-aware case folding (not just ASCII)
            // Without UNICODE_CASE, only ASCII A-Z matches a-z
            flags |= CASE_INSENSITIVE | UNICODE_CASE;
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

        // Handle positive flags
        if (positiveFlags.indexOf('n') >= 0) newFlagN = true;
        if (positiveFlags.indexOf('i') >= 0) newIsCaseInsensitive = true;
        if (positiveFlags.indexOf('m') >= 0) newIsMultiLine = true;
        if (positiveFlags.indexOf('s') >= 0) newIsDotAll = true;
        if (positiveFlags.indexOf('x') >= 0) newIsExtended = true;
        if (positiveFlags.indexOf('p') >= 0) newPreservesMatch = true;

        // Handle negative flags
        if (negativeFlags.indexOf('n') >= 0) newFlagN = false;
        if (negativeFlags.indexOf('i') >= 0) newIsCaseInsensitive = false;
        if (negativeFlags.indexOf('m') >= 0) newIsMultiLine = false;
        if (negativeFlags.indexOf('s') >= 0) newIsDotAll = false;
        if (negativeFlags.indexOf('x') >= 0) newIsExtended = false;

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
                newPreservesMatch
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

        return flagString.toString();
    }
}

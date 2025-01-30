package org.perlonjava.regex;

import static java.util.regex.Pattern.*;

public class RegexFlags {
    private final boolean isGlobalMatch;         // g flag - match globally (find all occurrences)
    private final boolean keepCurrentPosition;   // c flag - continue matching from last match position
    private final boolean isNonDestructive;      // r flag - non-destructive match (leaves target string unchanged)
    private final boolean isMatchExactlyOnce;    // m?PAT? flag - match pattern exactly once
    private final boolean useGAssertion;         // \G assertion - match must occur at previous match end
    private final boolean isExtendedWhitespace;  // xx flag - ignore whitespace and comments in pattern
    private final boolean isNonCapturing;        // n flag - make groups non-capturing by default
    private final boolean isOptimized;           // o flag - compile pattern only once
    private final boolean isCaseInsensitive;     // i flag - case insensitive matching
    private final boolean isMultiLine;           // m flag - multiline mode (^ and $ match line boundaries)
    private final boolean isDotAll;              // s flag - dot matches all characters including newline
    private final boolean isExtended;            // x flag - ignore whitespace and # comments in pattern

    public RegexFlags(boolean isGlobalMatch, boolean keepCurrentPosition, boolean isNonDestructive,
                      boolean isMatchExactlyOnce, boolean useGAssertion, boolean isExtendedWhitespace,
                      boolean isNonCapturing, boolean isOptimized, boolean isCaseInsensitive,
                      boolean isMultiLine, boolean isDotAll, boolean isExtended) {
        this.isGlobalMatch = isGlobalMatch;
        this.keepCurrentPosition = keepCurrentPosition;
        this.isNonDestructive = isNonDestructive;
        this.isMatchExactlyOnce = isMatchExactlyOnce;
        this.useGAssertion = useGAssertion;
        this.isExtendedWhitespace = isExtendedWhitespace;
        this.isNonCapturing = isNonCapturing;
        this.isOptimized = isOptimized;
        this.isCaseInsensitive = isCaseInsensitive;
        this.isMultiLine = isMultiLine;
        this.isDotAll = isDotAll;
        this.isExtended = isExtended;
    }

    public static RegexFlags fromModifiers(String modifiers, String patternString) {
        return new RegexFlags(
                modifiers.contains("g"),
                modifiers.contains("c"),
                modifiers.contains("r"),
                modifiers.contains("?"),
                patternString.contains("\\G"),
                modifiers.contains("xx"),
                modifiers.contains("n"),
                modifiers.contains("o"),
                modifiers.contains("i"),
                modifiers.contains("m"),
                modifiers.contains("s"),
                modifiers.contains("x")
        );
    }

    public int toPatternFlags() {
        int flags = 0;
        if (isCaseInsensitive) {
            flags |= CASE_INSENSITIVE;
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

        // Handle positive flags
        if (positiveFlags.indexOf('n') >= 0) newFlagN = true;
        if (positiveFlags.indexOf('i') >= 0) newIsCaseInsensitive = true;
        if (positiveFlags.indexOf('m') >= 0) newIsMultiLine = true;
        if (positiveFlags.indexOf('s') >= 0) newIsDotAll = true;
        if (positiveFlags.indexOf('x') >= 0) newIsExtended = true;

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
                newIsExtended
        );
    }

    // Getters
    public boolean isGlobalMatch() {
        return isGlobalMatch;
    }

    public boolean keepCurrentPosition() {
        return keepCurrentPosition;
    }

    public boolean isNonDestructive() {
        return isNonDestructive;
    }

    public boolean isMatchExactlyOnce() {
        return isMatchExactlyOnce;
    }

    public boolean useGAssertion() {
        return useGAssertion;
    }

    public boolean isExtendedWhitespace() {
        return isExtendedWhitespace;
    }

    public boolean isNonCapturing() {
        return isNonCapturing;
    }

    public boolean isOptimized() {
        return isOptimized;
    }

    public boolean isCaseInsensitive() {
        return isCaseInsensitive;
    }

    public boolean isMultiLine() {
        return isMultiLine;
    }

    public boolean isDotAll() {
        return isDotAll;
    }

    public boolean isExtended() {
        return isExtended;
    }
}

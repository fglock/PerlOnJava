package org.perlonjava.regex;

public record RegexFlags(
        boolean isGlobalMatch,         // g flag - match globally (find all occurrences)
        boolean keepCurrentPosition,   // c flag - continue matching from last match position
        boolean isNonDestructive,      // r flag - non-destructive match (leaves target string unchanged)
        boolean isMatchExactlyOnce,    // m?PAT? flag - match pattern exactly once
        boolean useGAssertion,         // \G assertion - match must occur at previous match end
        boolean isExtendedWhitespace,  // xx flag - ignore whitespace and comments in pattern
        boolean isNonCapturing,        // n flag - make groups non-capturing by default
        boolean isOptimized,           // o flag - compile pattern only once
        boolean isCaseInsensitive,     // i flag - case insensitive matching
        boolean isMultiLine,           // m flag - multiline mode (^ and $ match line boundaries)
        boolean isDotAll,              // s flag - dot matches all characters including newline
        boolean isExtended             // x flag - ignore whitespace and # comments in pattern
) {
}

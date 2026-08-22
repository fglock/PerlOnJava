package org.perlonjava.runtime.regex;

import java.util.Arrays;

import org.joni.Regex.ParsedProgramFeature;
import org.joni.exception.SyntaxException;

/** Test helper for asserting parser-owned Joni facts without backend routing. */
final class JoniProgramFacts {
    private JoniProgramFacts() {
    }

    static boolean has(String pattern, ParsedProgramFeature feature) {
        return metadata(pattern, RegexFlags.fromModifiers("", pattern)).has(feature);
    }

    static boolean has(String pattern, RegexFlags flags, ParsedProgramFeature feature) {
        return metadata(pattern, flags).has(feature);
    }

    static boolean hasAny(String pattern, ParsedProgramFeature... features) {
        var metadata = metadata(pattern, RegexFlags.fromModifiers("", pattern));
        return Arrays.stream(features).anyMatch(metadata::has);
    }

    private static org.joni.Regex.ParsedProgramMetadata metadata(
            String pattern, RegexFlags flags) {
        try {
            return new JoniRegexPattern(pattern, flags).parsedProgramMetadata();
        } catch (SyntaxException error) {
            return error.getParsedProgramMetadata();
        }
    }
}

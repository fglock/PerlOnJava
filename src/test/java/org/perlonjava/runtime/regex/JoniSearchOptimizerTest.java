package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JoniSearchOptimizerTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "");

    @Test
    void greedyAnyCharacterStarStillFindsAndRejectsRequiredLiterals() {
        String prefix = "0".repeat(40_000);

        assertFalse(matches(".*:::\\s*ab", prefix + "::: 0c"));
        assertTrue(matches(".*:::\\s*ab", prefix + "::: ab"));
    }

    @Test
    void exactUtf8FollowersKeepLateCandidatesAndNewlineSemantics() {
        assertLateCandidate("¢", "¢x", "¢ok");
        assertLateCandidate("€", "€x", "€ok");
        assertLateCandidate("😀", "😀x", "😀ok");
        assertFalse(matches("\\A.*:::ab", "prefix\n:::ab"));
    }

    @Test
    void lazyAndGreedyRequiredLiteralsAgreeOnARejectingSubject() {
        String subject = "0".repeat(20_000) + "::: ac";

        assertFalse(matches(".*:::\\s*ab", subject));
        assertFalse(matches(".*?:::\\s*ab", subject));
    }

    private static void assertLateCandidate(String character, String falseSuffix, String trueSuffix) {
        String subject = "0".repeat(1_000) + character + falseSuffix + character + trueSuffix;

        assertTrue(matches(".*" + character + "ok", subject));
        assertFalse(matches(".*" + character + "missing", subject));
    }

    private static boolean matches(String source, String input) {
        RegexMatcher matcher = new JoniRegexPattern(source, FLAGS).matcher(input, List.of());
        return matcher.find();
    }
}

package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class NativeScriptRunRoutingTest {
    @Test
    void routesScriptRunFormsToJoni() {
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*script_run:a|ab)c"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*sr:a|ab)c"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*atomic_script_run:a|ab)c"));
        assertTrue(JoniRegexPattern.requiresJoniBackend("(*asr:a|ab)c"));
    }

    @Test
    void ignoresScriptRunLookalikes() {
        assertFalse(JoniRegexPattern.requiresJoniBackend("\\Q(*script_run:a)\\E"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("[(?*script_run:)]"));
        assertFalse(JoniRegexPattern.requiresJoniBackend("(?# (*script_run:a))ordinary"));
    }
}

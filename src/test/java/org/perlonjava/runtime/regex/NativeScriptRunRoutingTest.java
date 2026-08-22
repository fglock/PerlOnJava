package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.joni.Regex.ParsedProgramFeature.ATOMIC_SCRIPT_RUN;
import static org.joni.Regex.ParsedProgramFeature.SCRIPT_RUN;
import static org.perlonjava.runtime.regex.JoniProgramFacts.hasAny;

@Tag("unit")
class NativeScriptRunRoutingTest {
    @Test
    void routesScriptRunFormsToJoni() {
        assertTrue(hasAny("(*script_run:a|ab)c", SCRIPT_RUN, ATOMIC_SCRIPT_RUN));
        assertTrue(hasAny("(*sr:a|ab)c", SCRIPT_RUN, ATOMIC_SCRIPT_RUN));
        assertTrue(hasAny("(*atomic_script_run:a|ab)c", SCRIPT_RUN, ATOMIC_SCRIPT_RUN));
        assertTrue(hasAny("(*asr:a|ab)c", SCRIPT_RUN, ATOMIC_SCRIPT_RUN));
    }

    @Test
    void ignoresScriptRunLookalikes() {
        assertFalse(hasAny("\\Q(*script_run:a)\\E", SCRIPT_RUN, ATOMIC_SCRIPT_RUN));
        assertFalse(hasAny("[(?*script_run:)]", SCRIPT_RUN, ATOMIC_SCRIPT_RUN));
        assertFalse(hasAny("(?# (*script_run:a))ordinary", SCRIPT_RUN, ATOMIC_SCRIPT_RUN));
    }
}

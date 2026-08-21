package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegexLocaleProvenanceTest {
    @Test
    void carriesTopLevelLocaleIntoNativeClassDebugProvenance() {
        RegexFlags flags = RegexFlags.fromModifiers("l", "[\\x{102}-\\x{104}]");
        assertTrue(flags.isLocale());

        JoniRegexPattern pattern = new JoniRegexPattern(
                "[\\x{102}-\\x{104}]", flags);
        assertEquals("", pattern.engineRegex()
                .perlFirstProgramDebugDescription());
    }
}

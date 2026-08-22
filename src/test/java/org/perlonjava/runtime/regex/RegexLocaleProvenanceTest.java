package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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

    @Test
    void carriesInlineLocaleAsParserOwnedProgramMetadata() {
        JoniRegexPattern pattern = new JoniRegexPattern(
                "(?l:^\\xE4$)", RegexFlags.fromModifiers("i", ""));
        assertTrue(pattern.engineRegex().getParsedProgramMetadata().has(
                org.joni.Regex.ParsedProgramFeature.LOCALE_CHARSET));
    }

    @Test
    void localeFoldSnapshotsRuntimeStateForUnicodeAndByteSubjects() {
        RegexFlags flags = RegexFlags.fromModifiers("il", "^\\xE4$");
        JoniRegexPattern pattern = new JoniRegexPattern(
                "^\\xE4$", flags, 0, true, false, false);
        PerlRuntime.current().regexState().localeState
                .publishCtype("en_US.ISO8859-1");
        try {
            assertTrue(pattern.matcher("Ä", List.of()).find());
            RuntimeScalar bytes = new RuntimeScalar(new byte[]{(byte)0xc4});
            assertTrue(pattern.matcher(bytes.toString(), List.of(), bytes).find());
            JoniRegexPattern bytePattern = new JoniRegexPattern(
                    "^\\xE4$", flags, 0, true, true, true);
            assertTrue(bytePattern.matcher(
                    bytes.toString(), List.of(), bytes).find());
        } finally {
            PerlRuntime.current().regexState().localeState.publishCtype("C");
        }
    }
}

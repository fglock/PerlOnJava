package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class JoniNativePropertySourcePreservationTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "");

    @Test
    void preservesNativeUnicodePropertiesByteForByteAcrossScannerContexts() {
        String[] exactPatterns = {
                "\\p{Block=Basic_Latin}",
                "\\p{Script=Latin}",
                "\\p{Script_Extensions=Hiragana}",
                "\\p{Age=2.1}",
                "\\p{Present_In=3.0}",
                "\\p{Uppercase_Letter}",
                "\\p{XPosixSpace}",
                "[\\p{Block=Basic_Latin}_]",
                "[^\\p{Block=Basic_Latin}]",
                "[\\P{Script=Latin}_]",
                "(?[ \\p{Script=Latin} & \\p{Block=Basic_Latin} ])",
                "(?[ \\p{Script_Extensions=Hiragana} - [A-Z] ])",
                "(?[ ! \\p{Block=Basic_Latin} ])",
        };

        for (String source : exactPatterns) {
            JoniRegexPattern pattern = new JoniRegexPattern(source, FLAGS);
            assertEquals(source, pattern.patternDescription(), source);
        }
    }
}

package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeBlockWildcardAliasTest {
    @Test
    void wildcardResolutionIncludesGeneratedShortBlockAliases() {
        assertMatches("Blk=:\\ABengali_Sup\\z:", 0x11DFF);
        assertMatches("Blk=:\\AMisc_Arrows_Ext\\z:", 0x1DBFF);
        assertMatches("Blk=:\\AMusic_Sup\\z:", 0x1D28F);
    }

    private static void assertMatches(String property, int codePoint) {
        String expression = "\\p{" + property + "}";
        assertTrue(new JoniRegexPattern(expression, RegexFlags.fromModifiers("", "BlockWildcard"))
                .matcher(new String(Character.toChars(codePoint)), List.of()).find(), property);
    }
}

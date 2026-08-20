package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.joni.CharacterPropertyResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlLineBreakPropertyResolverTest {
    @Test
    void resolvesLineBreakAliasesWithoutCaseFolding() {
        for (String property : new String[] {"lb=CR", "Line_Break=Carriage_Return"}) {
            CharacterPropertyResolver.Result result =
                    UnicodeResolver.resolveJoniProperty(property, false, true);
            assertNotNull(result, property);
            assertFalse(result.caseFold, property);
        }
    }
}

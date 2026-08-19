package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joni.CharacterPropertyResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlCaseFoldPropertyResolverTest {
    @Test
    void marksOnlyTheAuthorizedBareFamiliesAsFoldable() {
        for (String property : new String[] {
                "Uppercase", "Titlecase", "TitlecaseLetter",
                "PosixLower", "PosixUpper", "XPosixLower", "XPosixUpper"}) {
            CharacterPropertyResolver.Result result =
                    UnicodeResolver.resolveJoniProperty(property, false, true);
            assertNotNull(result, property);
            assertTrue(result.caseFold, property);
        }
    }

    @Test
    void preservesNoFoldPropertyFamilies() {
        for (String property : new String[] {
                "Block=Latin_1_Supplement", "Age=1.1", "ccc=230", "bc=L",
                "dt=Canonical", "ea=W", "nv=1", "jg=Alef", "vo=R"}) {
            CharacterPropertyResolver.Result result =
                    UnicodeResolver.resolveJoniProperty(property, false, true);
            assertNotNull(result, property);
            assertFalse(result.caseFold, property);
        }
    }
}

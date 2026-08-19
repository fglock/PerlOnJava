package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RegexBackendPolicyTest {
    private String originalBackendProperty;

    @BeforeEach
    void rememberBackendProperty() {
        originalBackendProperty = System.getProperty(RegexBackendPolicy.PROPERTY);
        System.clearProperty(RegexBackendPolicy.PROPERTY);
    }

    @AfterEach
    void restoreBackendProperty() {
        if (originalBackendProperty == null) {
            System.clearProperty(RegexBackendPolicy.PROPERTY);
        } else {
            System.setProperty(RegexBackendPolicy.PROPERTY, originalBackendProperty);
        }
    }

    @Test
    void defaultModeTemporarilyUsesJavaForOrdinaryLookbehind() {
        assertFalse(RegexBackendPolicy.useJoni("ordinary"));
        assertFalse(RegexBackendPolicy.useJoni("(?<=x)y"));
        assertFalse(RegexBackendPolicy.useJoni("(?<!x)y"));
        assertTrue(RegexBackendPolicy.useJoni("(?&recursive)"));
    }

    @Test
    void autoModeTemporarilyUsesJavaForOrdinaryLookbehind() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "auto");

        assertFalse(RegexBackendPolicy.useJoni("ordinary"));
        assertFalse(RegexBackendPolicy.useJoni("\\p{Titlecase}"));
        assertFalse(RegexBackendPolicy.useJoni("\\p{XPosixSpace}"));
        assertFalse(RegexBackendPolicy.useJoni("(?<=x)y"));
        assertFalse(RegexBackendPolicy.useJoni("(?<!x)y"));
    }

    @Test
    void autoModeRetainsJoniRoutingWhenLookbehindHasJoniOnlyConstructs() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "auto");

        assertTrue(RegexBackendPolicy.useJoni("(?{=CALL:0})"));
        assertTrue(RegexBackendPolicy.useJoni("(?{=DYNAMIC:0})"));
        assertTrue(RegexBackendPolicy.useJoni("(*:mark)"));
        assertTrue(RegexBackendPolicy.useJoni("(?<=x)(?{=CALL:0})"));
        assertTrue(RegexBackendPolicy.useJoni("(?<!x)(*:mark)"));
    }

    @Test
    void autoModeTemporarilyUsesJavaForBranchResetSubroutineCalls() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "auto");

        assertFalse(RegexBackendPolicy.useJoni("(?|(?<digit>1)|(?<digit>2))(?&digit)"));
        assertFalse(RegexBackendPolicy.useJoni("(?|(1)|(2))(?1)"));
        assertTrue(RegexBackendPolicy.useJoni("(?<digit>1)(?&digit)"));
        assertTrue(RegexBackendPolicy.useJoni(
                "(?|(?<digit>1)|(?<digit>2))(?&digit)(?{=CALL:0})"));
        assertTrue(RegexBackendPolicy.useJoni("(?|(1)|(2))(?1)(*:mark)"));
    }

    @Test
    void javaModeRetainsRequiredAdvancedJoniRouting() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "java");

        assertFalse(RegexBackendPolicy.useJoni("ordinary"));
        assertTrue(RegexBackendPolicy.useJoni("(?&recursive)"));
    }

    @Test
    void javaModeRoutesRealPerlConditionalsToJoni() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "java");

        assertTrue(RegexBackendPolicy.useJoni("(a)(?(1)b|c)"));
        assertTrue(RegexBackendPolicy.useJoni("(a)(?(1)b)"));
        assertTrue(RegexBackendPolicy.useJoni("(?<x>x)(?(<x>)y|z)"));
        assertTrue(RegexBackendPolicy.useJoni("(?(?=a)a|b)"));
        assertTrue(RegexBackendPolicy.useJoni("(?(DEFINE)(?<x>x))"));
        assertTrue(RegexBackendPolicy.useJoni("(?(R)recursive|plain)"));
        assertTrue(RegexBackendPolicy.useJoni("(?(bogus)x|y)"));
        assertTrue(RegexBackendPolicy.useJoni("(?(1)x"));
    }

    @Test
    void conditionalLookalikesRemainOnTheOrdinaryRoute() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "java");

        assertFalse(RegexBackendPolicy.useJoni("\\(\\?\\("));
        assertFalse(RegexBackendPolicy.useJoni("[(?()]"));
        assertFalse(RegexBackendPolicy.useJoni("(?[ [(?()] ])"));
        assertFalse(RegexBackendPolicy.useJoni("\\Q(?(DEFINE)(?<x>x))\\E"));
        assertFalse(RegexBackendPolicy.useJoni("(?# (?(1)x|y))ordinary"));

        String extendedComment = "# (?(1)x|y)\nordinary";
        RegexFlags extendedFlags = RegexFlags.fromModifiers("x", extendedComment);
        assertFalse(RegexBackendPolicy.useJoni(extendedComment, extendedFlags));
    }

    @Test
    void joniModeRoutesOrdinaryPatternsToJoni() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "joni");

        assertTrue(RegexBackendPolicy.useJoni("ordinary"));
        assertTrue(RegexBackendPolicy.useJoni("(?<=x)y"));
        assertTrue(RegexBackendPolicy.useJoni("(?|(?<digit>1)|(?<digit>2))(?&digit)"));
    }

    @Test
    void invalidModeFailsInsteadOfSilentlyChangingSemantics() {
        System.setProperty(RegexBackendPolicy.PROPERTY, "unknown");

        assertThrows(IllegalArgumentException.class, RegexBackendPolicy::current);
    }
}

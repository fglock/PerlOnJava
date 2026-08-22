package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class RegexFlagsCompatibilityTest {
    @Test
    void preservesBothLegacyPublicConstructorDescriptors() {
        RegexFlags preLocale = new RegexFlags(
                false, false, false, false, false, true,
                false, false, false, false, false, false, false, false,
                false, false, true, false);
        assertFalse(preLocale.isEnhancedExtendedWhitespace());
        assertFalse(preLocale.isLocale());

        RegexFlags withLocale = new RegexFlags(
                false, false, false, false, false, true,
                false, false, false, false, false, false, false, false,
                false, false, true, false, false);
        assertFalse(withLocale.isEnhancedExtendedWhitespace());
        assertTrue(withLocale.isLocale());

        assertTrue(hasBooleanConstructor(18));
        assertTrue(hasBooleanConstructor(19));
        assertTrue(hasBooleanConstructor(20));
    }

    private static boolean hasBooleanConstructor(int arity) {
        return Arrays.stream(RegexFlags.class.getConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == arity
                        && Arrays.stream(constructor.getParameterTypes())
                                .allMatch(type -> type == boolean.class));
    }
}

package org.perlonjava.runtime.regex;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.operators.StringOperators;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class JoniWideScalarPatternTest {
    private static final RegexFlags FLAGS = RegexFlags.fromModifiers("", "");

    @Test
    void productCodecMatchesSignedIvMaxAsOneScalar() {
        String input = PerlUtfString.encodeBeyondUnicode(Long.MAX_VALUE);

        assertTrue(matches("\\A\\x{7FFF_FFFF_FFFF_FFFF}\\z", input));
        assertTrue(matches("\\A[\\x{7FFF_FFFF_FFFF_FFFF}]\\z", input));
        assertTrue(matches(
                "\\A[\\x{11_0000}-\\x{7FFF_FFFF_FFFF_FFFF}]\\z", input));
    }

    @Test
    void chrPreservesSignedIvMaxForTheProductCodec() {
        RuntimeScalar character = StringOperators.chr(new RuntimeScalar(Long.MAX_VALUE));

        assertEquals(RuntimeScalarType.STRING, character.type);
        assertEquals(PerlUtfString.encodeBeyondUnicode(Long.MAX_VALUE), character.toString());
        assertTrue(matches("\\A\\x{7FFF_FFFF_FFFF_FFFF}\\z", character.toString()));
    }

    private static boolean matches(String source, String input) {
        RegexMatcher matcher = new JoniRegexPattern(source, FLAGS).matcher(input, List.of());
        return matcher.find();
    }
}

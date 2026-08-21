package org.perlonjava.runtime.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

@Tag("unit")
class StringOperatorsChrBoundsTest {
    private static final BigInteger FIRST_ABOVE_SIGNED_IV =
            BigInteger.ONE.shiftLeft(63);
    private static final BigInteger UV_MAX =
            BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    @Test
    void acceptsAndPreservesSignedIvMaximum() {
        RuntimeScalar character = StringOperators.chr(new RuntimeScalar(Long.MAX_VALUE));

        assertEquals(PerlUtfString.encodeBeyondUnicode(Long.MAX_VALUE),
                character.toString());
    }

    @Test
    void rejectsBigIntegerValuesOutsideExecutableCharacterDomain() {
        assertRejected(FIRST_ABOVE_SIGNED_IV, "8000000000000000");
        assertRejected(UV_MAX.subtract(BigInteger.ONE), "FFFFFFFFFFFFFFFE");
        assertRejected(UV_MAX, "FFFFFFFFFFFFFFFF");
    }

    @Test
    void capsOverflowDiagnosticAtPerlUvMaximum() {
        assertRejected(UV_MAX.add(BigInteger.ONE), "FFFFFFFFFFFFFFFF");
    }

    private static void assertRejected(BigInteger value, String expectedHex) {
        PerlCompilerException error = assertThrows(PerlCompilerException.class,
                () -> StringOperators.chr(new RuntimeScalar(value)));
        assertEquals("Use of code point 0x" + expectedHex
                        + " is not allowed; the permissible max is 0x7FFFFFFFFFFFFFFF",
                error.getMessage());
    }
}

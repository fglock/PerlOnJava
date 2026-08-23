package org.joni;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TestPerlPropertyCaseFoldClosure {
    @Test
    public void exposesTitlecaseAndLatinOneSimpleFoldSiblings() {
        assertSiblings(0x2160, 0x2160, 0x2170);
        assertSiblings(0x00c0, 0x00c0, 0x00e0);
    }

    private static void assertSiblings(int member, int... expected) {
        assertEquals(expected.length, PerlUnicodeCaseFoldData.simpleFoldClassLength(member));
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index],
                    PerlUnicodeCaseFoldData.simpleFoldClassCodePoint(member, index));
        }
    }
}

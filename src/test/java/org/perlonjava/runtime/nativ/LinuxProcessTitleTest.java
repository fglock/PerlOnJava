package org.perlonjava.runtime.nativ;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class LinuxProcessTitleTest {
    @Test
    void parsesArgvBoundsWhenProcessNameContainsSpacesAndParentheses() {
        StringBuilder stat = new StringBuilder("123 (java worker (one)) S");
        // Fields 4 through 47 are irrelevant to this parser.
        for (int field = 4; field <= 47; field++) stat.append(' ').append(field);
        stat.append(" 4096 8192 50 51 52");

        assertArrayEquals(new long[] {4096, 8192}, LinuxProcessTitle.argvBounds(stat.toString()));
    }
}

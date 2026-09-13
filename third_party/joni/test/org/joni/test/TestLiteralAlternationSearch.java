/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ASCIIEncoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestLiteralAlternationSearch {
    @Test
    public void searchesToEarliestCompleteAlternativeAndKeepsBranchOrder() {
        assertMatch("a|ab", "zzab", 2, 3);
        assertMatch("ab|a", "zzab", 2, 4);
        assertMatch("42|gamma|epsilon", "alpha:gamma:42", 6, 11);
    }

    @Test
    public void searchHonorsItsStartOffset() {
        Regex regex = regex("42|gamma|epsilon");
        byte[] input = "gamma:epsilon".getBytes(StandardCharsets.ISO_8859_1);
        Matcher matcher = regex.matcher(input);
        assertTrue(matcher.search(1, input.length, Option.NONE) >= 0);
        assertEquals(6, matcher.getBegin());
        assertEquals(13, matcher.getEnd());
    }

    private static void assertMatch(String pattern, String input, int begin, int end) {
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
        Matcher matcher = regex(pattern).matcher(bytes);
        assertTrue(matcher.search(0, bytes.length, Option.NONE) >= 0);
        assertEquals(begin, matcher.getBegin());
        assertEquals(end, matcher.getEnd());
    }

    private static Regex regex(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.ISO_8859_1);
        return new Regex(bytes, 0, bytes.length, Option.NONE,
                ASCIIEncoding.INSTANCE, Syntax.PerlNG);
    }
}

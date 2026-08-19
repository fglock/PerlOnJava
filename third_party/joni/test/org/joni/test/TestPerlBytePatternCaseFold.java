/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.ISO8859_1Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlBytePatternCaseFold {
    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.ISO_8859_1);
        byte[] inputBytes = input.getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.IGNORECASE | Option.PERL_BYTE_PATTERN,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    @Test
    public void bytePatternsRejectUnicodeMultiCharacterFolds() {
        assertEquals(-1, search("^\u00df$", "ss"));
        assertEquals(-1, search("^ss$", "\u00df"));
        assertEquals(-1, search("^(\u00df)\\1$", "ssss"));
        assertEquals(-1, search("^(ss)\\1$", "\u00df\u00df"));
    }

    @Test
    public void bytePatternsKeepLatinOneSimpleFolds() {
        assertEquals(0, search("^\u00e4$", "\u00c4"));
        assertEquals(0, search("^(\u00e4)\\1$", "\u00c4\u00e4"));
    }
}

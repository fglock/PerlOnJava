package org.joni.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlSimpleFoldClosure {
    private static int search(String pattern, String input) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(bytes, 0, bytes.length, Option.IGNORECASE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        byte[] target = input.getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(target);
        return matcher.search(0, target.length, Option.NONE);
    }

    @Test
    public void foldsPinnedSimpleClassesWithoutBreakingScopedAsciiPolicy() {
        assertEquals(0, search("^(?d:k)$", "\u212a"));
        assertEquals(0, search("^(?u:\u00e5)$", "\u212b"));
        assertEquals(0, search("^(?a:kk)$", "\u212aK"));
        assertEquals(0, search("^(?d:[k])$", "\u212a"));
        assertEquals(-1, search("^(?aa:k)$", "\u212a"));
        assertEquals(0, search("^\u2c65$", "\u023a"));
        assertEquals(0, search("^\u2c66$", "\u023e"));
    }
}

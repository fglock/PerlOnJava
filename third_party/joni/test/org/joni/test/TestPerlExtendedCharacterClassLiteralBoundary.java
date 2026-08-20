package org.joni.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_ESC_H_HORIZONTAL_WHITESPACE;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.joni.constants.SyntaxProperties.OP2_PLUS_POSSESSIVE_INTERVAL;
import static org.joni.constants.SyntaxProperties.OP_POSIX_BRACKET;

public class TestPerlExtendedCharacterClassLiteralBoundary {
    private static final Syntax SYNTAX = new Syntax(
            "PerlExtendedCharacterClassLiteralBoundary", Syntax.RUBY.op | OP_POSIX_BRACKET,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL
                    | OP2_PLUS_POSSESSIVE_INTERVAL | OP2_ESC_H_HORIZONTAL_WHITESPACE,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options & ~(Option.ASCII_RANGE
                    | Option.POSIX_BRACKET_ALL_RANGE | Option.WORD_BOUND_ALL_RANGE),
            Syntax.RUBY.metaCharTable);

    private static int search(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length, Option.NONE,
                UTF8Encoding.INSTANCE, SYNTAX);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        return regex.matcher(inputBytes).search(0, inputBytes.length, Option.NONE);
    }

    @Test
    public void acceptsLeadingLiteralCloseBracketInsideNestedClass() {
        String pattern = "(?[ [^][ \\\\ ] ])";
        assertEquals(0, search(pattern, "A"));
        assertEquals(-1, search(pattern, "]"));
        assertEquals(-1, search(pattern, "["));
        assertEquals(-1, search(pattern, "\\"));
        assertEquals(0, search("(?[ [a[] ])", "["));
        assertEquals(0, search("(?[ [[] ])", "["));
        assertEquals(0, search("(?[ [:ascii:] ])", "A"));
        assertEquals(0, search("(?[ [:graph:] ])", "A"));
        assertEquals(0, search("(?[ [:ascii:] & [:graph:] ])", "A"));
        assertEquals(0, search(
                "(?[ [:ascii:] & [:graph:] & [^][ \\\\ ] ])", "A"));
    }
}

package org.joni.test;

import static org.joni.constants.SyntaxProperties.ALLOW_MULTIPLEX_DEFINITION_NAME_CALL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_PERL;
import static org.joni.constants.SyntaxProperties.OP2_OPTION_RUBY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.exception.JOniException;
import org.junit.Test;

public class TestPerlDefineContainer {
    private static final Syntax SYNTAX = new Syntax(
            "PerlDefineContainer", Syntax.RUBY.op,
            (Syntax.RUBY.op2 & ~OP2_OPTION_RUBY) | OP2_OPTION_PERL,
            Syntax.RUBY.op3,
            Syntax.RUBY.behavior | ALLOW_MULTIPLEX_DEFINITION_NAME_CALL,
            Syntax.RUBY.options, Syntax.RUBY.metaCharTable);

    private static Matcher matcher(String pattern, String input) {
        byte[] patternBytes = pattern.getBytes(StandardCharsets.UTF_8);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(patternBytes, 0, patternBytes.length,
                Option.CAPTURE_GROUP, UTF8Encoding.INSTANCE, SYNTAX);
        return regex.matcher(inputBytes);
    }

    private static Matcher assertMatches(String pattern, String input) {
        Matcher matcher = matcher(pattern, input);
        assertEquals(pattern, 0, matcher.search(0, input.length(), Option.NONE));
        return matcher;
    }

    private static void assertDoesNotMatch(String pattern, String input) {
        assertEquals(pattern, -1,
                matcher(pattern, input).search(0, input.length(), Option.NONE));
    }

    @Test
    public void skipsDefinitionsAndRetainsForwardCalls() {
        assertMatches("^(?(DEFINE)(?<never>FAIL))A$", "A");
        assertDoesNotMatch("^(?(DEFINE)(?<never>FAIL))$", "FAIL");
        assertMatches("^(?&word)(?(DEFINE)(?<word>[a-z]+))$", "word");
        assertMatches("^(?(DEFINE)(a))(?1)$", "a");
        assertMatches("^(?(DEFINE)(a)(b))(?-1)$", "b");
    }

    @Test
    public void preservesRecursionAndBacktracking() {
        assertMatches("^(?&par)(?(DEFINE)(?<par>\\((?:x|(?&par))*\\)))$", "((x))");
        assertMatches("^(?&piece)c(?(DEFINE)(?<piece>a|ab))$", "abc");
    }

    @Test
    public void leavesUncalledCapturesUndefined() {
        Matcher matcher = assertMatches("^(x)(?(DEFINE)(y)(?<z>z))(w)$", "xw");
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(-1, matcher.getRegion().getBeg(2));
        assertEquals(-1, matcher.getRegion().getBeg(3));
        assertEquals(1, matcher.getRegion().getBeg(4));
    }

    @Test
    public void publishesAnEnclosingCaptureAroundADefineCall() {
        Matcher matcher = assertMatches(
                "((?&solution)|%)\\z(?(DEFINE)(?<solution>7% solution))",
                "7% solution");
        assertEquals(0, matcher.captureBegin(1));
        assertEquals(11, matcher.captureEnd(1));
        assertEquals(0, matcher.getRegion().getBeg(1));
        assertEquals(11, matcher.getRegion().getEnd(1));
    }

    @Test
    public void rejectsTopLevelBranches() {
        try {
            matcher("(?(DEFINE)(?<x>x)|y)", "");
            fail("expected DEFINE branch diagnostic");
        } catch (JOniException error) {
            if (!error.getMessage().contains("does not allow branches")) {
                fail(error.getMessage());
            }
        }
    }
}

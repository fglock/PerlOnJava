package org.perlonjava;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
public class ErrorMessageUtilSourceLocationIndexTest {
    @Test
    void indexesBlankHeavyMultibyteSourceAndRepeatedLookups() throws Exception {
        String source = "\n".repeat(4_000)
                + "my $caf\u00e9 = 1;\n"
                + "#line 700 \"same-\u03b1.pl\"\n"
                + "warn 'first';\n"
                + "#line 0 lib/Other.pm\n"
                + "warn 'second';\n";
        List<LexerToken> tokens = new Lexer(source).tokenize();
        ErrorMessageUtil util = new ErrorMessageUtil("physical.pl", tokens);

        int declaration = tokenIndex(tokens, "my", 0);
        int firstWarn = tokenIndex(tokens, "warn", 0);
        int secondWarn = tokenIndex(tokens, "warn", 1);

        assertEquals(new ErrorMessageUtil.SourceLocation("physical.pl", 4_001),
                util.getSourceLocationAccurate(declaration));
        ErrorMessageUtil.SourceLocation first =
                util.getSourceLocationAccurate(firstWarn);
        assertEquals(new ErrorMessageUtil.SourceLocation("same-\u03b1.pl", 700), first);
        assertEquals(first, util.getSourceLocationAccurate(firstWarn));
        assertEquals(new ErrorMessageUtil.SourceLocation("lib/Other.pm", 0),
                util.getSourceLocationAccurate(secondWarn));

        Object index = sourceIndex(util);
        assertSame(index, sourceIndex(util));
        assertEquals(2, directiveCount(index));
    }

    @Test
    void equalAndDistinctTokenIdentitiesKeepIndependentIndexes() throws Exception {
        String source = "#line 9 \"logical.pl\"\nwarn 'same';\n";
        List<LexerToken> firstTokens = new Lexer(source).tokenize();
        List<LexerToken> equalButDistinctTokens = new ArrayList<>(firstTokens);
        ErrorMessageUtil first = new ErrorMessageUtil("first.pl", firstTokens);
        ErrorMessageUtil second = new ErrorMessageUtil(
                "second.pl", equalButDistinctTokens);
        int warn = tokenIndex(firstTokens, "warn", 0);

        ErrorMessageUtil.SourceLocation expected =
                new ErrorMessageUtil.SourceLocation("logical.pl", 9);
        assertEquals(expected, first.getSourceLocationAccurate(warn));
        assertEquals(expected, second.getSourceLocationAccurate(warn));
        assertNotSame(sourceIndex(first), sourceIndex(second));

        List<LexerToken> replacement = new Lexer(
                "#line 11 \"changed.pl\"\nwarn 'changed';\n").tokenize();
        first.updateTokens(replacement);
        assertEquals(new ErrorMessageUtil.SourceLocation("changed.pl", 11),
                first.getSourceLocationAccurate(tokenIndex(replacement, "warn", 0)));
        assertEquals(expected, second.getSourceLocationAccurate(warn));
    }

    private static int tokenIndex(
            List<LexerToken> tokens, String text, int occurrence) {
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).text.equals(text) && occurrence-- == 0) {
                return index;
            }
        }
        throw new AssertionError("Missing token: " + text);
    }

    private static Object sourceIndex(ErrorMessageUtil util) throws Exception {
        Field field = ErrorMessageUtil.class.getDeclaredField("sourceDirectiveIndex");
        field.setAccessible(true);
        return field.get(util);
    }

    private static int directiveCount(Object sourceIndex) throws Exception {
        Field field = sourceIndex.getClass().getDeclaredField("tokenIndexes");
        field.setAccessible(true);
        return ((int[]) field.get(sourceIndex)).length;
    }
}

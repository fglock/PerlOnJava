package org.perlonjava;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
public class ErrorMessageUtilLineIndexTest {
    @Test
    void accurateLineNumbersSupportRandomAccessAndInvalidateAfterTokenUpdates() {
        ErrorMessageUtil util = new ErrorMessageUtil("test.pl", List.of(
                token(LexerTokenType.IDENTIFIER, "first"),
                token(LexerTokenType.NEWLINE, "\n"),
                token(LexerTokenType.IDENTIFIER, "second"),
                token(LexerTokenType.NEWLINE, "\n"),
                token(LexerTokenType.EOF, "")
        ));

        assertEquals(3, util.getLineNumberAccurate(3));
        assertEquals(1, util.getLineNumberAccurate(0));
        assertEquals(3, util.getLineNumberAccurate(100));

        util.updateTokens(List.of(
                token(LexerTokenType.IDENTIFIER, "replacement"),
                token(LexerTokenType.EOF, "")
        ));
        assertEquals(1, util.getLineNumberAccurate(100));
    }

    private static LexerToken token(LexerTokenType type, String text) {
        return new LexerToken(type, text);
    }
}

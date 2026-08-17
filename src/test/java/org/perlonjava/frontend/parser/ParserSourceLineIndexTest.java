package org.perlonjava.frontend.parser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class ParserSourceLineIndexTest {
    @Test
    void sourceLinesRemainStableWhenEarlierTokensAreRemoved() {
        List<LexerToken> tokens = new Lexer("q{first};\nq{second};\nq{third};").tokenize();
        Parser parser = new Parser(null, tokens);
        List<LexerToken> quoteOperators = new ArrayList<>();
        for (LexerToken token : tokens) {
            if (token.text.equals("q")) {
                quoteOperators.add(token);
            }
        }

        assertEquals(3, quoteOperators.size());
        assertEquals(1, parser.sourceLineAt(tokens.indexOf(quoteOperators.get(0))));
        assertEquals(2, parser.sourceLineAt(tokens.indexOf(quoteOperators.get(1))));
        assertEquals(3, parser.sourceLineAt(tokens.indexOf(quoteOperators.get(2))));

        tokens.remove(quoteOperators.get(0));
        assertEquals(3, parser.sourceLineAt(tokens.indexOf(quoteOperators.get(2))));

        parser.baseLineNumber = 40;
        assertEquals(42, parser.sourceLineAt(tokens.indexOf(quoteOperators.get(2))));
    }
}

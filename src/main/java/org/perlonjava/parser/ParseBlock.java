package org.perlonjava.parser;

import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.TokenUtils.peek;

public class ParseBlock {
    public static BlockNode parseBlock(Parser parser) {
        int currentIndex = parser.tokenIndex;
        parser.ctx.symbolTable.enterScope();
        List<Node> statements = new ArrayList<>();
        LexerToken token = peek(parser);
        while (token.type != LexerTokenType.EOF
                && !(token.type == LexerTokenType.OPERATOR && token.text.equals("}"))) {
            if (token.text.equals(";")) {
                TokenUtils.consume(parser);
            } else {

                // Check for label:
                String label = null;
                int currentIndexLabel = parser.tokenIndex;
                if (token.type == LexerTokenType.IDENTIFIER) {
                    String id = TokenUtils.consume(parser).text;
                    if (peek(parser).text.equals(":")) {
                        label = id;
                        TokenUtils.consume(parser);
                        token = peek(parser);
                    } else {
                        parser.tokenIndex = currentIndexLabel;  // Backtrack
                    }
                }

//        // Handle multiple labels; create labelNode and return
//        if (token.type == LexerTokenType.IDENTIFIER) {
//            String id = TokenUtils.consume(parser).text;
//            if (peek(parser).text.equals(":")) {
//                TokenUtils.consume(parser); // consume the colon
//                LabelNode labelNode = new LabelNode(id, currentIndex);
//                // Check for more labels
//                if (peek(parser).type == LexerTokenType.IDENTIFIER) {
//                    return labelNode; // Return just the label, let parser continue with next statement
//                }
//                return labelNode;
//            }
//            parser.tokenIndex = currentIndex;  // Backtrack if not a label
//        }

                statements.add(ParseStatement.parseStatement(parser, label));
            }
            token = peek(parser);
        }
        if (statements.isEmpty()) {
            statements.add(new ListNode(parser.tokenIndex));
        }
        parser.ctx.symbolTable.exitScope();
        return new BlockNode(statements, currentIndex);
    }
}

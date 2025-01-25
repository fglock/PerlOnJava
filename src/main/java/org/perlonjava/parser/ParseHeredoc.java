package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.StringNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.StringParser.parseRawString;

public class ParseHeredoc {
    static OperatorNode parseHeredoc(Parser parser, String tokenText) {
        // Check if the token is a tilde, indicating heredoc indentation
        boolean indent = false;
        if (tokenText.equals("~")) {
            indent = true;
            parser.tokenIndex++;
        }
        OperatorNode node = new OperatorNode("HEREDOC", null, parser.tokenIndex);
        node.setAnnotation("indent", indent);

        LexerToken token = TokenUtils.peek(parser);
        parser.ctx.logDebug("Heredoc " + token);
        tokenText = token.text;
        String delimiter = "";
        String identifier = "";
        if (tokenText.length() == 1 && "'`\"".contains(tokenText)) {
            delimiter = tokenText;
        } else if (token.type == LexerTokenType.IDENTIFIER) {
            delimiter = "\"";
            identifier = tokenText;
            TokenUtils.consume(parser);
        } else {
            throw new PerlCompilerException(parser.tokenIndex, "Use of bare << to mean <<\"\" is forbidden", parser.ctx.errorUtil);
        }
        node.setAnnotation("delimiter", delimiter);
        if (identifier.isEmpty()) {
            // Consume identifier string using `q()`
            Node identifierNode = parseRawString(parser, "q");
            if (identifierNode instanceof StringNode stringNode) {
                identifier = stringNode.value;
            } else {
                throw new PerlCompilerException(parser.tokenIndex, "Use of bare << to mean <<\"\" is forbidden", parser.ctx.errorUtil);
            }
        }
        node.setAnnotation("identifier", identifier);

        parser.ctx.logDebug("Heredoc " + node);
        parser.getHeredocNodes().add(node);
        return node;
    }

    static void heredocError(Parser parser) {
        OperatorNode heredoc = parser.getHeredocNodes().getLast();
        throw new PerlCompilerException(parser.tokenIndex, "Can't find string terminator \"" + heredoc.getAnnotation("identifier") + "\" anywhere before EOF", parser.ctx.errorUtil);
    }
}

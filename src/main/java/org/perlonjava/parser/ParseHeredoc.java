package org.perlonjava.parser;

import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

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
        } else {
            throw new PerlCompilerException(parser.tokenIndex, "Use of bare << to mean <<\"\" is forbidden", parser.ctx.errorUtil);
        }
        node.setAnnotation("delimiter", delimiter);
        if (identifier.isEmpty()) {
            // TODO consume identifier string
        }
        node.setAnnotation("identifier", identifier);

        parser.ctx.logDebug("Heredoc " + node);

        // TODO WIP
        throw new PerlCompilerException(parser.tokenIndex, "Not implemented: Heredoc", parser.ctx.errorUtil);

        // return node;
    }
}

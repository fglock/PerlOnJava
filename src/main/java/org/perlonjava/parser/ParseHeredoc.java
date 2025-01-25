package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.StringNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

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

    public static void parseHeredocAfterNewline(Parser parser) {
        List<OperatorNode> heredocNodes = parser.getHeredocNodes();
        List<LexerToken> tokens = parser.tokens;
        int newlineIndex = parser.tokenIndex;

        for (OperatorNode heredocNode : heredocNodes) {
            String delimiter = (String) heredocNode.getAnnotation("delimiter");
            String identifier = (String) heredocNode.getAnnotation("identifier");
            boolean indent = heredocNode.getBooleanAnnotation("indent");

            parser.ctx.logDebug("Whitespace Heredoc " + heredocNode);

            // Consume the heredoc content
            StringBuilder content = new StringBuilder();
            int currentIndex = newlineIndex + 1;
            while (currentIndex < tokens.size()) {
                LexerToken nextToken = tokens.get(currentIndex);
                if (nextToken.text.equals(identifier)) {
                    // End of heredoc
                    break;
                }
                content.append(nextToken.text);
                currentIndex++;
            }

            // Handle indentation
            if (indent) {
                String[] lines = content.toString().split("\n");
                StringBuilder strippedContent = new StringBuilder();
                for (String line : lines) {
                    strippedContent.append(line.stripLeading()).append("\n");
                }
                content = strippedContent;
            }

            // Store the content in the node
            heredocNode.setAnnotation("content", "<<" + content.toString() + ">>");

            parser.ctx.logDebug("Whitespace Heredoc after: " + heredocNode);

            // Update the token index to skip the heredoc content
            newlineIndex = currentIndex;
        }

        // Clear the list of heredoc nodes after processing
        heredocNodes.clear();
        parser.tokenIndex = newlineIndex;
    }
}

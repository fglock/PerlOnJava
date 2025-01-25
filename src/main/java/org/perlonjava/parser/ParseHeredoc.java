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

    // Define states for the state machine
    enum HeredocState {
        START,      // Initial state, waiting for the end marker
        INDENT,     // Detected whitespace before the identifier (indentation case)
        CONTENT,    // Consuming the heredoc content
        END         // Detected the end marker, finished parsing
    }

    public static void parseHeredocAfterNewline(Parser parser) {
        List<OperatorNode> heredocNodes = parser.getHeredocNodes();
        List<LexerToken> tokens = parser.tokens;
        int newlineIndex = parser.tokenIndex;

        for (OperatorNode heredocNode : heredocNodes) {
            String delimiter = (String) heredocNode.getAnnotation("delimiter");
            String identifier = (String) heredocNode.getAnnotation("identifier");
            boolean indent = heredocNode.getBooleanAnnotation("indent");

            parser.ctx.logDebug("Processing heredoc with identifier: " + identifier);

            //------------------------------

            // Consume the heredoc content
            StringBuilder content = new StringBuilder();
            int currentIndex = newlineIndex + 1; // Start after the newline
            String indentWhitespace = "";

            while (currentIndex < tokens.size()) {
                LexerToken token = tokens.get(currentIndex);

                // Debug: Log current token
                parser.ctx.logDebug("Current token: " + token.text + ", type: " + token.type);

                // Check for the end marker
                if (token.type == LexerTokenType.IDENTIFIER && token.text.equals(identifier)) {
                    // Check if the end marker is preceded by whitespace (for indentation)
                    if (indent && currentIndex > 0 && tokens.get(currentIndex - 1).type == LexerTokenType.WHITESPACE) {
                        indentWhitespace = tokens.get(currentIndex - 1).text;
                        parser.ctx.logDebug("Detected indentation: '" + indentWhitespace + "'");
                    }

                    // Check that the identifier is followed by NEWLINE or EOF
                    currentIndex++;
                    if (currentIndex < tokens.size() && tokens.get(currentIndex).type != LexerTokenType.NEWLINE) {
                        throw new PerlCompilerException(currentIndex, "Invalid heredoc end marker: identifier must be followed by NEWLINE or EOF", parser.ctx.errorUtil);
                    }

                    // End of heredoc
                    parser.ctx.logDebug("Detected end marker: " + identifier);
                    break;
                }

                // Append the token to the content
                content.append(token.text);
                currentIndex++;
            }

            // Handle indentation
            if (indent) {
                // Split at \n without removing \n
                String[] lines = content.toString().split("(?<=\\n)");

                StringBuilder strippedContent = new StringBuilder();
                for (String line : lines) {
                    parser.ctx.logDebug("Line: "+line.length()+" <<" + line + ">>");
                    if (line.equals("\n")) {
                        // empty line
                        parser.ctx.logDebug("Line: <<empty>>");
                    } else if (line.startsWith(indentWhitespace)) {
                        // Strip only the common indentation (matching indentWhitespace)
                        parser.ctx.logDebug("Line: remove <<"+indentWhitespace+">>");
                        strippedContent.append(line.substring(indentWhitespace.length()));
                    } else {
                        // The line doesn't start with the expected indentation
                        throw new PerlCompilerException(newlineIndex, "Indentation of here-doc doesn't match delimiter", parser.ctx.errorUtil);
                    }
                }
                content = strippedContent;
            }

            // Store the content in the node
            heredocNode.setAnnotation("content", content.toString());

            // Debug: Log final content
            parser.ctx.logDebug("Final heredoc content: <<" + content.toString() + ">>");

            //------------------------------

            parser.ctx.logDebug("Whitespace Heredoc after: " + heredocNode);

            // TODO
            // Rewrite the heredoc node, according to the delimiter
            heredocNode.operator = "scalar";
            heredocNode.operand = new StringNode(content.toString(), newlineIndex);
            heredocNode.annotations.clear();

            // Update the token index to skip the heredoc content
            newlineIndex = currentIndex;
        }

        // Clear the list of heredoc nodes after processing
        heredocNodes.clear();
        parser.tokenIndex = newlineIndex;
    }
}

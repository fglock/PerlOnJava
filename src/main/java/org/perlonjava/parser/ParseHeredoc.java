package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.StringNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.perlonjava.parser.StringDoubleQuoted.parseDoubleQuotedString;
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

            parser.ctx.logDebug("Processing heredoc with identifier: " + identifier);

            // Consume the heredoc content
            List<String> lines = new ArrayList<>();
            int currentIndex = newlineIndex + 1; // Start after the newline
            String indentWhitespace = "";
            StringBuilder currentLine = new StringBuilder();

            while (currentIndex < tokens.size()) {
                LexerToken token = tokens.get(currentIndex);

                // Debug: Log current token
                parser.ctx.logDebug("Current token: " + token.text + ", type: " + token.type);

                if (token.type == LexerTokenType.NEWLINE) {
                    // End of the current line
                    String line = currentLine.toString();
                    lines.add(line);
                    currentLine.setLength(0); // Reset the current line

                    // Check if this line is the end marker
                    String lineToCompare = line;
                    if (indent) {
                        // Left-trim the line if indentation is enabled
                        lineToCompare = line.stripLeading();
                    }

                    if (lineToCompare.equals(identifier)) {
                        // End of heredoc - remove the end marker line from the content
                        lines.remove(lines.size() - 1);
                        parser.ctx.logDebug("Detected end marker: " + identifier);
                        break;
                    }

                    currentIndex++;
                } else {
                    // Append the token to the current line
                    currentLine.append(token.text);
                    currentIndex++;
                }
            }

            // Handle indentation
            if (indent) {
                // Determine the common indentation (minimum indentation across all lines)
                indentWhitespace = lines.stream()
                        .filter(line -> !line.trim().isEmpty()) // Skip empty lines
                        .map(line -> line.replaceAll("^(\\s*).*", "$1")) // Extract leading whitespace
                        .min(Comparator.comparingInt(String::length)) // Find the minimum indentation
                        .orElse("");

                parser.ctx.logDebug("Detected common indentation: '" + indentWhitespace + "'");

                // Strip the common indentation from each line
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.startsWith(indentWhitespace)) {
                        lines.set(i, line.substring(indentWhitespace.length()));
                    } else if (!line.trim().isEmpty()) {
                        // If the line doesn't start with the expected indentation, throw an error
                        throw new PerlCompilerException(newlineIndex, "Indentation of here-doc doesn't match delimiter", parser.ctx.errorUtil);
                    }
                }
            }

            // Reconstruct the content
            StringBuilder content = new StringBuilder();
            for (String line : lines) {
                content.append(line).append("\n");
            }

            String string = content.toString();
            parser.ctx.logDebug("Final heredoc content: <<" + string + ">>");

            // Rewrite the heredoc node, according to the delimiter
            Node operand = null;
            switch (delimiter) {
                case "'":
                    operand = new StringNode(string, newlineIndex);
                    break;
                case "\"":
                    ArrayList<String> buffers = new ArrayList<>();
                    buffers.add(string);
                    StringParser.ParsedString rawStr = new StringParser.ParsedString(newlineIndex, newlineIndex, buffers, ' ', ' ', ' ', ' ');
                    operand = parseDoubleQuotedString(parser.ctx, rawStr, true);
                    break;
                case "`":
                    // TODO
                default:
                    throw new PerlCompilerException(newlineIndex, "Unsupported delimiter in heredoc", parser.ctx.errorUtil);
            }

            heredocNode.operator = "scalar";
            heredocNode.operand = operand;
            heredocNode.annotations.clear();

            // Update the token index to skip the heredoc content
            newlineIndex = currentIndex;
        }

        // Clear the list of heredoc nodes after processing
        heredocNodes.clear();
        parser.tokenIndex = newlineIndex;
    }
}

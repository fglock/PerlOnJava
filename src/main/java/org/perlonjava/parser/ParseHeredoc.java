package org.perlonjava.parser;

import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.StringNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
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

        // Check for backslash (which means single-quoted heredoc)
        if (token.type == LexerTokenType.OPERATOR && tokenText.equals("\\")) {
            delimiter = "'";
            TokenUtils.consume(parser); // consume the backslash
            token = TokenUtils.peek(parser); // get the identifier
            if (token.type == LexerTokenType.IDENTIFIER) {
                identifier = token.text;
                TokenUtils.consume(parser);
            } else {
                throw new PerlCompilerException(parser.tokenIndex, "Expecting identifier after \\ in heredoc", parser.ctx.errorUtil);
            }
        } else if (tokenText.length() == 1 && "'`\"".contains(tokenText)) {
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
        // Try to get heredoc info if available, otherwise use generic message
        if (!parser.getHeredocNodes().isEmpty()) {
            OperatorNode heredoc = parser.getHeredocNodes().getLast();
            heredocError(parser, heredoc);
        } else {
            // Generic error when we don't have heredoc info
            throw new PerlCompilerException(parser.tokenIndex, "Can't find string terminator anywhere before EOF", parser.ctx.errorUtil);
        }
    }

    static void heredocError(Parser parser, OperatorNode heredoc) {
        throw new PerlCompilerException(parser.tokenIndex, "Can't find string terminator \"" + heredoc.getAnnotation("identifier") + "\" anywhere before EOF", parser.ctx.errorUtil);
    }

    public static void parseHeredocAfterNewline(Parser parser) {
        parser.debugHeredocState("HEREDOC_PROCESSING_START");
        List<OperatorNode> heredocNodes = parser.getHeredocNodes();
        List<LexerToken> tokens = parser.tokens;
        int newlineIndex = parser.tokenIndex;

        while (!heredocNodes.isEmpty()) {
            OperatorNode heredocNode = heredocNodes.removeFirst(); // Remove immediately

            String delimiter = (String) heredocNode.getAnnotation("delimiter");
            String identifier = (String) heredocNode.getAnnotation("identifier");
            boolean indent = heredocNode.getBooleanAnnotation("indent");

            parser.ctx.logDebug("Processing heredoc with identifier: " + identifier);

            // Consume the heredoc content
            List<String> lines = new ArrayList<>();
            int currentIndex = newlineIndex + 1; // Start after the newline
            String indentWhitespace = "";
            StringBuilder currentLine = new StringBuilder();
            boolean foundTerminator = false; // Track if we found the terminator

            while (currentIndex < tokens.size()) {
                LexerToken token = tokens.get(currentIndex);

                // Debug: Log current token
                parser.ctx.logDebug("Current token: " + token.text + ", type: " + token.type);

                if (token.type == LexerTokenType.NEWLINE || (!identifier.isEmpty() && token.type == LexerTokenType.EOF)) {
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
                        lines.removeLast();

                        // Determine the indentation of the end marker
                        indentWhitespace = line.substring(0, line.length() - lineToCompare.length());
                        parser.ctx.logDebug("Detected end marker indentation: '" + indentWhitespace + "'");
                        foundTerminator = true; // Mark that we found the terminator
                        break;
                    }

                    currentIndex++;
                } else {
                    // Append the token to the current line
                    currentLine.append(token.text);
                    currentIndex++;
                }
            }

            // Check if we found the terminator
            if (!foundTerminator) {
                heredocError(parser, heredocNode);
            }

            // Handle indentation
            if (indent) {
                // Strip the end marker's indentation from each line
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
                    operand = interpolateString(parser, string, newlineIndex);
                    break;
                case "`":
                    Node interpolated = interpolateString(parser, string, newlineIndex);
                    List<Node> elements = new ArrayList<>();
                    elements.add(interpolated);
                    ListNode list = new ListNode(elements, newlineIndex);
                    operand = new OperatorNode("qx", list, newlineIndex);
                    break;
                default:
                    throw new PerlCompilerException(newlineIndex, "Unsupported delimiter in heredoc", parser.ctx.errorUtil);
            }

            heredocNode.operator = "scalar";
            heredocNode.operand = operand;
            heredocNode.annotations.clear();

            // Update the token index to skip the heredoc content
            newlineIndex = currentIndex;
        }

        parser.debugHeredocState("HEREDOC_AFTER_CLEAR");
        parser.tokenIndex = newlineIndex;
    }

    private static Node interpolateString(Parser parser, String string, int newlineIndex) {
        ArrayList<String> buffers = new ArrayList<>();
        buffers.add(string);
        StringParser.ParsedString rawStr = new StringParser.ParsedString(newlineIndex, newlineIndex, buffers, ' ', ' ', ' ', ' ');

        // Pass the main parser's heredoc nodes to the string parser
        return StringDoubleQuoted.parseDoubleQuotedString(parser.ctx, rawStr, true, true, false, parser.getHeredocNodes());
    }

    /**
     * Save the current heredoc state (deep copy of all heredoc nodes)
     */
    public static List<OperatorNode> saveHeredocState(Parser parser) {
        List<OperatorNode> savedHeredocNodes = new ArrayList<>();
        for (OperatorNode node : parser.getHeredocNodes()) {
            // Create a deep copy of the OperatorNode with its annotations
            OperatorNode copy = new OperatorNode(node.operator, node.operand, node.getIndex());
            if (node.annotations != null) {
                copy.annotations = new java.util.HashMap<>(node.annotations);
            }
            savedHeredocNodes.add(copy);
        }
        return savedHeredocNodes;
    }

    /**
     * Restore heredoc state if backtracking consumed processed heredocs
     */
    public static void restoreHeredocStateIfNeeded(Parser parser, List<OperatorNode> savedHeredocNodes) {
        if (savedHeredocNodes.size() > parser.getHeredocNodes().size()) {
            // We had heredocs before but they got processed during look-ahead
            // Restore them since we're backtracking
            parser.getHeredocNodes().clear();
            parser.getHeredocNodes().addAll(savedHeredocNodes);
        }
    }
}

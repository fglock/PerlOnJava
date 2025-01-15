package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ListParser.parseZeroOrOneList;

public class SignatureParser {
    public static Node parseSignature(Parser parser, String signature) {
        int tokenIndex = parser.tokenIndex;

        // Tokenize the signature
        Lexer lexer = new Lexer(signature);
        List<LexerToken> tokens = lexer.tokenize();
        Parser sigParser = new Parser(parser.ctx, tokens);
        // sigParser.ctx.logDebug("signature tokens: " + tokens + " at " + sigParser.tokenIndex);

        List<Node> nodes = new ArrayList<>();
        int minParams = 0;
        int maxParams = 0;
        boolean hasSlurpy = false;

        // Loop through the token array until the end
        while (true) {
            LexerToken token = TokenUtils.consume(sigParser);
            if (token.type == LexerTokenType.EOF) {
                break;
            }

            String sigil = token.text;

            // Handle slurpy params (@array or %hash)
            if (sigil.equals("@") || sigil.equals("%")) {
                hasSlurpy = true;
                if (TokenUtils.peek(sigParser).type == LexerTokenType.IDENTIFIER) {
                    String name = TokenUtils.consume(sigParser).text;
                    nodes.add(generateSlurpyAssignment(sigil, name, maxParams, tokenIndex));
                }
                maxParams = Integer.MAX_VALUE;
                break; // Slurpy must be last
            }

            // Handle scalar params
            String name = null;
            if (TokenUtils.peek(sigParser).type == LexerTokenType.IDENTIFIER) {
                name = TokenUtils.consume(sigParser).text;
            }

            Node defaultValue = null;
            LexerToken nextToken = TokenUtils.peek(sigParser);
            if (nextToken.text.equals("=") ||
                    nextToken.text.equals("||=") ||
                    nextToken.text.equals("//=")) {

                String op = TokenUtils.consume(sigParser).text;
                defaultValue = parseZeroOrOneList(sigParser, 0);
                nodes.add(generateDefaultAssignment(name, defaultValue, op, maxParams, tokenIndex));
            } else {
                minParams++;
                nodes.add(generateRequiredAssignment(name, maxParams, tokenIndex));
            }

            maxParams++;

            token = TokenUtils.consume(sigParser);
            if (token.text.equals(",") || token.type == LexerTokenType.EOF) {
                // Ok, we have a comma, so continue
            } else {
                throw new PerlCompilerException("Expected ',' or ')' in signature prototype");
            }
        }

        // Check that all tokens were consumed
        if (TokenUtils.peek(sigParser).type != LexerTokenType.EOF) {
            throw new PerlCompilerException("Expected ')' in signature prototype");
        }

        // Add parameter count validation at start
        nodes.add(0, new BinaryOperatorNode(
                "(",
                new OperatorNode("&",
                        new IdentifierNode("_check_param_count", tokenIndex), tokenIndex),
                new ListNode(
                        List.of(
                                new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex),
                                new NumberNode(Integer.toString(minParams), tokenIndex),
                                new NumberNode(Integer.toString(maxParams), tokenIndex)),
                        tokenIndex),
                tokenIndex)
        );

        return new BlockNode(nodes, 0);
    }

    private static Node generateSlurpyAssignment(String sigil, String name, int paramIndex, int tokenIndex) {
        return new BinaryOperatorNode(
                "=",
                new OperatorNode(sigil, new IdentifierNode(name, tokenIndex), tokenIndex),
                new BinaryOperatorNode(
                        "[",
                        new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex),
                        new BinaryOperatorNode(
                                "..",
                                new NumberNode(Integer.toString(paramIndex), tokenIndex),
                                new OperatorNode("$#", new IdentifierNode("_", tokenIndex), tokenIndex),
                                tokenIndex),
                        tokenIndex),
                tokenIndex);
    }

    private static Node generateDefaultAssignment(String name, Node defaultValue, String op, int paramIndex, int tokenIndex) {
        // placeholder
        return new NumberNode(Integer.toString(paramIndex), tokenIndex);

//        Node arrayAccess = new ArrayAccessNode(
//                new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex),
//                new NumberNode(Integer.toString(paramIndex), tokenIndex),
//                tokenIndex
//        );
//
//        Node condition = switch (op) {
//            case "=" -> new OperatorNode("!",
//                    new OperatorNode("defined", arrayAccess, tokenIndex),
//                    tokenIndex);
//            case "||=" -> new OperatorNode("!", arrayAccess, tokenIndex);
//            case "//=" -> new OperatorNode("!",
//                    new OperatorNode("defined", arrayAccess, tokenIndex),
//                    tokenIndex);
//            default -> throw new IllegalArgumentException("Unknown operator: " + op);
//        };
//
//        return new BinaryOperatorNode(
//                "=",
//                new OperatorNode("$", new IdentifierNode(name, tokenIndex), tokenIndex),
//                new ConditionalNode(condition, defaultValue, arrayAccess, tokenIndex),
//                tokenIndex
//        );
    }

    private static Node generateRequiredAssignment(String name, int paramIndex, int tokenIndex) {
        return new BinaryOperatorNode(
                "=",
                new OperatorNode("$", new IdentifierNode(name, tokenIndex), tokenIndex),
                new BinaryOperatorNode(
                        "[",
                        new OperatorNode("$", new IdentifierNode("_", tokenIndex), tokenIndex),
                        new ArrayLiteralNode(
                                List.of(
                                        new NumberNode(Integer.toString(paramIndex), tokenIndex)),
                                tokenIndex),
                        tokenIndex),
                tokenIndex);
    }
}

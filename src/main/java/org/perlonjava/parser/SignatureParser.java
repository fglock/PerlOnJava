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
    public static ListNode parseSignature(Parser parser, String signature) {
        int tokenIndex = parser.tokenIndex;

        // Tokenize the signature
        Lexer lexer = new Lexer(signature);
        List<LexerToken> tokens = lexer.tokenize();
        Parser sigParser = new Parser(parser.ctx, tokens);
        // sigParser.ctx.logDebug("signature tokens: " + tokens + " at " + sigParser.tokenIndex);

        List<Node> nodes = new ArrayList<>();
        List<Node> variables = new ArrayList<>();
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

            // Special case: $# is tokenized as a single token
            if (sigil.equals("$#")) {
                parser.throwError("'#' not allowed immediately following a sigil in a subroutine signature");
            }

            // Check for valid sigil
            if (!sigil.equals("$") && !sigil.equals("@") && !sigil.equals("%")) {
                parser.throwError("A signature parameter must start with '$', '@' or '%'");
            }

            // Check if we already have a slurpy parameter
            if (hasSlurpy) {
                parser.throwError("Slurpy parameter not last");
            }

            String name = null;
            LexerToken nextToken = TokenUtils.peek(sigParser);

            // Check for double sigil
            if (nextToken.text.equals("$") || nextToken.text.equals("@") || nextToken.text.equals("%")) {
                parser.throwError("Illegal character following sigil in a subroutine signature");
            }

            // Check for invalid character after sigil
            if (nextToken.text.equals("#")) {
                parser.throwError("'#' not allowed immediately following a sigil in a subroutine signature");
            }

            if (nextToken.type == LexerTokenType.IDENTIFIER) {
                name = TokenUtils.consume(sigParser).text;
            }

            // Collect variable list
            Node variable = null;
            if (name != null) {
                variable = new OperatorNode(sigil, new IdentifierNode(name, tokenIndex), tokenIndex);
                variables.add(variable);
            } else {
                variables.add(new OperatorNode("undef", null, tokenIndex));
            }
            sigParser.ctx.logDebug("signature variable: " + variable);

            // Handle slurpy params (@array or %hash)
            if (sigil.equals("@") || sigil.equals("%")) {
                hasSlurpy = true;
                maxParams = Integer.MAX_VALUE;

                // Check if there are more parameters after slurpy
                LexerToken checkToken = TokenUtils.peek(sigParser);
                if (checkToken.text.equals(",")) {
                    TokenUtils.consume(sigParser); // consume comma
                    checkToken = TokenUtils.peek(sigParser);
                    if (checkToken.type != LexerTokenType.EOF) {
                        // Check if it's another slurpy
                        if (checkToken.text.equals("@") || checkToken.text.equals("%")) {
                            parser.throwError("Multiple slurpy parameters not allowed");
                        } else {
                            parser.throwError("Slurpy parameter not last");
                        }
                    }
                }
                break; // Slurpy must be last
            }

            // Handle scalar params
            Node defaultValue = null;
            nextToken = TokenUtils.peek(sigParser);
            if (nextToken.text.equals("=") ||
                    nextToken.text.equals("||=") ||
                    nextToken.text.equals("//=")) {

                String op = TokenUtils.consume(sigParser).text;

                // Check if there's a default expression
                if (TokenUtils.peek(sigParser).type == LexerTokenType.EOF ||
                        TokenUtils.peek(sigParser).text.equals(",")) {
                    parser.throwError("Optional parameter lacks default expression");
                }

                defaultValue = parseZeroOrOneList(sigParser, 0);
                nodes.add(generateDefaultAssignment(defaultValue, op, maxParams, variable, tokenIndex));
            } else {
                minParams++;
            }

            maxParams++;

            token = TokenUtils.peek(sigParser);
            if (token.text.equals(",")) {
                TokenUtils.consume(sigParser); // consume comma
            } else if (token.type == LexerTokenType.EOF) {
                break;
            } else {
                // Check for missing comma between parameters
                if (token.text.equals("$") || token.text.equals("@") || token.text.equals("%")) {
                    parser.throwError("syntax error");
                }
                parser.throwError("Expected ',' or ')' in signature prototype");
            }
        }

        // Check that all tokens were consumed
        if (TokenUtils.peek(sigParser).type != LexerTokenType.EOF) {
            parser.throwError("Expected ')' in signature prototype");
        }

        sigParser.ctx.logDebug("signature min: " + minParams + " max: " + maxParams + " slurpy: " + hasSlurpy);

        // AST:   (10 < @_ < 20) || die "bad number of arguments"
        nodes.add(0, new BinaryOperatorNode(
                "||",
                new ListNode(List.of(
                        new BinaryOperatorNode("<=",
                                new BinaryOperatorNode("<=",
                                        new NumberNode(Integer.toString(minParams), tokenIndex),
                                        new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex),
                                        tokenIndex),
                                new NumberNode(Integer.toString(maxParams), tokenIndex),
                                tokenIndex)
                ), tokenIndex),
                new OperatorNode("die",
                        new ListNode(List.of(
                                new StringNode("Bad number of arguments", tokenIndex)
                        ), tokenIndex),
                        tokenIndex),
                tokenIndex));

        // AST:  my ($a, $b) = @_
        nodes.add(0,
                new BinaryOperatorNode(
                        "=",
                        new OperatorNode("my",
                                new ListNode(variables, tokenIndex),
                                tokenIndex),
                        new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex),
                        tokenIndex));

        return new ListNode(nodes, tokenIndex);
    }

    private static Node generateDefaultAssignment(Node defaultValue, String op, int paramIndex, Node variable, int tokenIndex) {
        if (variable == null) {
            // AST for:  `$=`
            return new ListNode(tokenIndex);
        }
        if (op.equals("=")) {
            // AST for:  `@_ < 3 && $a = 123
            return new BinaryOperatorNode(
                    "&&",
                    new BinaryOperatorNode(
                            "<",
                            new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex),
                            new NumberNode(Integer.toString(paramIndex + 1), tokenIndex),
                            tokenIndex),
                    new BinaryOperatorNode(
                            "=",
                            variable,
                            defaultValue,
                            tokenIndex),
                    tokenIndex);
        }
        // AST for:  `$a //= 123`  `$a ||= 123`
        return new BinaryOperatorNode(
                op,
                variable,
                defaultValue,
                tokenIndex);
    }
}

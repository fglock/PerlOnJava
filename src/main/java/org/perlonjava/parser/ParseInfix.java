package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The ParseInfix class is responsible for parsing infix operations in the source code.
 * It handles binary operators, ternary operators, and special cases like method calls and subscripts.
 */
public class ParseInfix {

    /**
     * Parses infix operators and their right-hand operands.
     * This method handles binary operators, ternary operators, and special cases like method calls and subscripts.
     *
     * @param parser     The parser instance used for parsing.
     * @param left       The left-hand operand of the infix operation.
     * @param precedence The current precedence level for parsing.
     * @return A node representing the parsed infix operation.
     * @throws PerlCompilerException If there's an unexpected infix operator or syntax error.
     */
    public static Node parseInfixOperation(Parser parser, Node left, int precedence) {
        LexerToken token = TokenUtils.consume(parser);

        Node right;

        if (ParserTables.INFIX_OP.contains(token.text)) {
            String operator = token.text;
            boolean operatorEnabled = switch (operator) {
                case "isa" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("isa");
                case "&.", "|.", "^.", "&.=", "|.=", "^.=" ->
                        parser.ctx.symbolTable.isFeatureCategoryEnabled("bitwise");
                case "&", "|", "^", "&=", "|=", "^=" -> {
                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("bitwise")) {
                        operator = "binary" + operator;
                    }
                    yield true;
                }
                default -> true;
            };
            if (!operatorEnabled) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }

            right = parser.parseExpression(precedence);
            if (right == null) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }
            return new BinaryOperatorNode(operator, left, right, parser.tokenIndex);
        }

        switch (token.text) {
            case ",":
            case "=>":
                if (token.text.equals("=>") && left instanceof IdentifierNode) {
                    // Autoquote - Convert IdentifierNode to StringNode
                    left = new StringNode(((IdentifierNode) left).name, ((IdentifierNode) left).tokenIndex);
                }
                token = peek(parser);
                if (token.type == LexerTokenType.EOF || ParserTables.LIST_TERMINATORS.contains(token.text) || token.text.equals(",") || token.text.equals("=>")) {
                    // "postfix" comma
                    return ListNode.makeList(left);
                }
                right = parser.parseExpression(precedence);
                return ListNode.makeList(left, right);
            case "?":
                // Handle ternary operator
                Node middle = parser.parseExpression(0);
                TokenUtils.consume(parser, LexerTokenType.OPERATOR, ":");
                right = parser.parseExpression(precedence);
                return new TernaryOperatorNode(token.text, left, middle, right, parser.tokenIndex);
            case "->":
                // Handle method calls and subscripts
                String nextText = peek(parser).text;
                switch (nextText) {
                    case "(":
                        TokenUtils.consume(parser);
                        right = new ListNode(ListParser.parseList(parser, ")", 0), parser.tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                    case "{":
                        TokenUtils.consume(parser);
                        right = new HashLiteralNode(parseHashSubscript(parser), parser.tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                    case "[":
                        TokenUtils.consume(parser);
                        right = new ArrayLiteralNode(parseArraySubscript(parser), parser.tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                    case "@*":
                        TokenUtils.consume(parser);
                        return new OperatorNode("@", left, parser.tokenIndex);
                    case "$*":
                        TokenUtils.consume(parser);
                        return new OperatorNode("$", left, parser.tokenIndex);
                    case "%*":
                        TokenUtils.consume(parser);
                        return new OperatorNode("%", left, parser.tokenIndex);
                    case "&*":
                        TokenUtils.consume(parser);
                        return new BinaryOperatorNode("(",
                                left,
                                ParserNodeUtils.atUnderscore(parser),
                                parser.tokenIndex);
                    case "@":
                        // ->@[0,-1];
                        TokenUtils.consume(parser);
                        right = ParsePrimary.parsePrimary(parser);
                        if (right instanceof HashLiteralNode) {
                            return new BinaryOperatorNode("{",
                                    new OperatorNode("@", left, parser.tokenIndex),
                                    right,
                                    parser.tokenIndex);
                        } else if (right instanceof ArrayLiteralNode) {
                            return new BinaryOperatorNode("[",
                                    new OperatorNode("@", left, parser.tokenIndex),
                                    right,
                                    parser.tokenIndex);
                        } else {
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                        }
                    default:
                        parser.parsingForLoopVariable = true;
                        if (nextText.equals("$")) {
                            // Method call with ->$var or ->$var()
                            right = parser.parseExpression(precedence);
                        } else {
                            // Method call with ->method or ->method()
                            right = SubroutineParser.parseSubroutineCall(parser, true);
                            parser.ctx.logDebug("method call -> " + right);
                        }
                        parser.parsingForLoopVariable = false;

                        if (right instanceof BinaryOperatorNode && ((BinaryOperatorNode) right).operator.equals("(")) {
                            // Right has parameter list
                        } else {
                            // Insert an empty parameter list
                            right = new BinaryOperatorNode("(", right, new ListNode(parser.tokenIndex), parser.tokenIndex);
                        }

                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                }
            case "(":
                // Handle function calls
                right = new ListNode(ListParser.parseList(parser, ")", 0), parser.tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
            case "{":
                // Handle hash subscripts
                right = new HashLiteralNode(parseHashSubscript(parser), parser.tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
            case "[":
                // Handle array subscripts
                right = new ArrayLiteralNode(parseArraySubscript(parser), parser.tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
            case "--":
            case "++":
                // Handle postfix increment/decrement
                return new OperatorNode(token.text + "postfix", left, parser.tokenIndex);
            default:
                throw new PerlCompilerException(parser.tokenIndex, "Unexpected infix operator: " + token, parser.ctx.errorUtil);
        }
    }

    private static List<Node> parseArraySubscript(Parser parser) {
        int currentIndex = parser.tokenIndex;

        // Handle optional empty parentheses
        LexerToken nextToken = peek(parser);
        if (nextToken.text.equals("(")) {
            consume(parser);
            consume(parser, LexerTokenType.OPERATOR, ")");
            if (peek(parser).text.equals("]")) {
                consume(parser);
                return new ArrayList<>();
            }
        }

        // backtrack
        parser.tokenIndex = currentIndex;
        return ListParser.parseList(parser, "]", 1);

    }

    static List<Node> parseHashSubscript(Parser parser) {
        parser.ctx.logDebug("parseHashSubscript start");
        int currentIndex = parser.tokenIndex;

        LexerToken ident = TokenUtils.consume(parser);
        LexerToken close = TokenUtils.consume(parser);
        if (ident.type == LexerTokenType.IDENTIFIER && close.text.equals("}")) {
            // autoquote
            List<Node> list = new ArrayList<>();
            list.add(new IdentifierNode(ident.text, currentIndex));
            return list;
        }

        // backtrack
        parser.tokenIndex = currentIndex;
        return ListParser.parseList(parser, "}", 1);
    }
}

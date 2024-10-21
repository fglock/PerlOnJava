package org.perlonjava.parser;

import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

public class ListParser {

    // List parser for predeclared function calls with One optional argument,
    // accepts a list with Parentheses or without.
    //
    // Comma is allowed after the argument:   rand, rand 10,
    //
    static ListNode parseZeroOrOneList(Parser parser, int minItems) {
        if (looksLikeEmptyList(parser)) {
            // return an empty list
            if (minItems > 0) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
            return new ListNode(parser.tokenIndex);
        }

        ListNode expr;
        LexerToken token = TokenUtils.peek(parser);
        if (token.text.equals("(")) {
            // argument in parentheses, can be 0 or 1 argument:    rand(), rand(10)
            // Commas are allowed after the single argument:       rand(10,)
            TokenUtils.consume(parser);
            expr = new ListNode(parseList(parser, ")", 0), parser.tokenIndex);
            if (expr.elements.size() > 1) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
        } else if (token.type == LexerTokenType.EOF || Parser.LIST_TERMINATORS.contains(token.text) || token.text.equals(",")) {
            // no argument
            expr = new ListNode(parser.tokenIndex);
        } else {
            // argument without parentheses
            expr = ListNode.makeList(parser.parseExpression(parser.getPrecedence("isa") + 1));
        }
        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        return expr;
    }

    // List parser for predeclared function calls, accepts a list with Parentheses or without
    //
    // The Minimum number of arguments can be set.
    //
    // wantBlockNode:   sort { $a <=> $b } @array
    //
    // obeyParentheses: print ("this", "that"), "not printed"
    //
    // not obeyParentheses:  return ("this", "that"), "this too"
    //
    // wantFileHandle:  print STDOUT "this\n";
    //
    // wantRegex:  split / /, "this";
    //
    static ListNode parseZeroOrMoreList(Parser parser, int minItems, boolean wantBlockNode, boolean obeyParentheses, boolean wantFileHandle, boolean wantRegex) {
        parser.ctx.logDebug("parseZeroOrMoreList start");
        ListNode expr = new ListNode(parser.tokenIndex);

        int currentIndex = parser.tokenIndex;
        boolean hasParen = false;
        LexerToken token;

        if (wantRegex) {
            boolean matched = false;
            if (TokenUtils.peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                hasParen = true;
            }
            if (TokenUtils.peek(parser).text.equals("/") || TokenUtils.peek(parser).text.equals("//")) {
                TokenUtils.consume(parser);
                Node regex = StringParser.parseRawString(parser, "/");
                if (regex != null) {
                    matched = true;
                    expr.elements.add(regex);
                    token = TokenUtils.peek(parser);
                    if (token.type != LexerTokenType.EOF && !Parser.LIST_TERMINATORS.contains(token.text)) {
                        // consume comma
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ",");
                    }
                }
            }
            if (!matched) {
                // backtrack
                parser.tokenIndex = currentIndex;
                hasParen = false;
            }
        }

        if (wantFileHandle) {
            if (TokenUtils.peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                hasParen = true;
            }
            expr.handle = parser.parseFileHandle();
            if (expr.handle == null || !parser.isSpaceAfterPrintBlock()) {
                // backtrack
                parser.tokenIndex = currentIndex;
                hasParen = false;
            }
        }

        if (wantBlockNode) {
            if (TokenUtils.peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                hasParen = true;
            }
            if (TokenUtils.peek(parser).text.equals("{")) {
                TokenUtils.consume(parser);
                expr.handle = parser.parseBlock();
                TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            }
            if (!parser.isSpaceAfterPrintBlock() || looksLikeEmptyList(parser)) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
        }

        if (!looksLikeEmptyList(parser)) {
            // it doesn't look like an empty list
            token = TokenUtils.peek(parser);
            if (obeyParentheses && token.text.equals("(")) {
                // arguments in parentheses, can be 0 or more arguments:    print(), print(10)
                // Commas are allowed after the arguments:       print(10,)
                TokenUtils.consume(parser);
                expr.elements.addAll(parseList(parser, ")", 0));
            } else {
                while (token.type != LexerTokenType.EOF && !Parser.LIST_TERMINATORS.contains(token.text)) {
                    // Argument without parentheses
                    expr.elements.add(parser.parseExpression(parser.getPrecedence(",")));
                    token = TokenUtils.peek(parser);
                    if (token.text.equals(",") || token.text.equals("=>")) {
                        while (token.text.equals(",") || token.text.equals("=>")) {
                            TokenUtils.consume(parser);
                            token = TokenUtils.peek(parser);
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        if (hasParen) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }
        parser.ctx.logDebug("parseZeroOrMoreList end: " + expr);

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        return expr;
    }

    // Generic List parser for Parentheses, Hash literal, Array literal,
    // function arguments, get Array element, get Hash element.
    //
    // The Minimum number of arguments can be set.
    //
    // Example usage:
    //
    //    new ListNode(parseList(")", 0), tokenIndex);
    //    new HashLiteralNode(parseList("}", 1), tokenIndex);
    //    new ArrayLiteralNode(parseList("]", 1), tokenIndex);
    //
    static List<Node> parseList(Parser parser, String close, int minItems) {
        parser.ctx.logDebug("parseList start");
        ListNode expr;

        LexerToken token = TokenUtils.peek(parser);
        parser.ctx.logDebug("parseList start at " + token);
        if (token.text.equals(close)) {
            // empty list
            TokenUtils.consume(parser);
            expr = new ListNode(parser.tokenIndex);
        } else {
            expr = ListNode.makeList(parser.parseExpression(0));
            parser.ctx.logDebug("parseList end at " + TokenUtils.peek(parser));
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, close);
        }

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        parser.ctx.logDebug("parseList end");

        return expr.elements;
    }

    public static boolean looksLikeEmptyList(Parser parser) {
        boolean isEmptyList = false;
        int previousIndex = parser.tokenIndex;
        LexerToken token = TokenUtils.consume(parser);
        LexerToken token1 = parser.tokens.get(parser.tokenIndex); // next token including spaces
        LexerToken nextToken = TokenUtils.peek(parser);  // after spaces

        if (token.type == LexerTokenType.EOF || Parser.LIST_TERMINATORS.contains(token.text)) {
            isEmptyList = true;
        } else if (token.text.equals("-")
                && token1.type == LexerTokenType.IDENTIFIER
                && token1.text.length() == 1) {
            // -d, -e, -f, -l, -p, -x
            isEmptyList = false;
        } else if (Parser.INFIX_OP.contains(token.text) || token.text.equals(",")) {
            // tokenIndex++;
            parser.ctx.logDebug("parseZeroOrMoreList infix `" + token.text + "` followed by `" + nextToken.text + "`");
            if (token.text.equals("&")) {
                // looks like a subroutine call, not an infix `&`
                parser.ctx.logDebug("parseZeroOrMoreList looks like subroutine call");
            } else if (token.text.equals("%") && (nextToken.text.equals("$") || nextToken.type == LexerTokenType.IDENTIFIER)) {
                // looks like a hash deref, not an infix `%`
                parser.ctx.logDebug("parseZeroOrMoreList looks like Hash");
            } else if (token.text.equals(".") && token1.type == LexerTokenType.NUMBER) {
                // looks like a fractional number, not an infix `.`
                parser.ctx.logDebug("parseZeroOrMoreList looks like Number");
            } else {
                // subroutine call with zero arguments, followed by infix operator: `pos = 3`
                parser.ctx.logDebug("parseZeroOrMoreList return zero at `" + parser.tokens.get(parser.tokenIndex) + "`");
                // if (LVALUE_INFIX_OP.contains(token.text)) {
                //    throw new PerlCompilerException(tokenIndex, "Can't modify non-lvalue subroutine call", ctx.errorUtil);
                // }
                isEmptyList = true;
            }
        }
        parser.tokenIndex = previousIndex;
        return isEmptyList;
    }
}

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
        if (parser.looksLikeEmptyList()) {
            // return an empty list
            if (minItems > 0) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
            return new ListNode(parser.tokenIndex);
        }

        ListNode expr;
        LexerToken token = parser.peek();
        if (token.text.equals("(")) {
            // argument in parentheses, can be 0 or 1 argument:    rand(), rand(10)
            // Commas are allowed after the single argument:       rand(10,)
            parser.consume();
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
            if (parser.peek().text.equals("(")) {
                parser.consume();
                hasParen = true;
            }
            if (parser.peek().text.equals("/") || parser.peek().text.equals("//")) {
                parser.consume();
                Node regex = StringParser.parseRawString(parser, "/");
                if (regex != null) {
                    matched = true;
                    expr.elements.add(regex);
                    token = parser.peek();
                    if (token.type != LexerTokenType.EOF && !Parser.LIST_TERMINATORS.contains(token.text)) {
                        // consume comma
                        parser.consume(LexerTokenType.OPERATOR, ",");
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
            if (parser.peek().text.equals("(")) {
                parser.consume();
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
            if (parser.peek().text.equals("(")) {
                parser.consume();
                hasParen = true;
            }
            if (parser.peek().text.equals("{")) {
                parser.consume();
                expr.handle = parser.parseBlock();
                parser.consume(LexerTokenType.OPERATOR, "}");
            }
            if (!parser.isSpaceAfterPrintBlock() || parser.looksLikeEmptyList()) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
        }

        if (!parser.looksLikeEmptyList()) {
            // it doesn't look like an empty list
            token = parser.peek();
            if (obeyParentheses && token.text.equals("(")) {
                // arguments in parentheses, can be 0 or more arguments:    print(), print(10)
                // Commas are allowed after the arguments:       print(10,)
                parser.consume();
                expr.elements.addAll(parseList(parser, ")", 0));
            } else {
                while (token.type != LexerTokenType.EOF && !Parser.LIST_TERMINATORS.contains(token.text)) {
                    // Argument without parentheses
                    expr.elements.add(parser.parseExpression(parser.getPrecedence(",")));
                    token = parser.peek();
                    if (token.text.equals(",") || token.text.equals("=>")) {
                        while (token.text.equals(",") || token.text.equals("=>")) {
                            parser.consume();
                            token = parser.peek();
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        if (hasParen) {
            parser.consume(LexerTokenType.OPERATOR, ")");
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

        LexerToken token = parser.peek();
        parser.ctx.logDebug("parseList start at " + token);
        if (token.text.equals(close)) {
            // empty list
            parser.consume();
            expr = new ListNode(parser.tokenIndex);
        } else {
            expr = ListNode.makeList(parser.parseExpression(0));
            parser.ctx.logDebug("parseList end at " + parser.peek());
            parser.consume(LexerTokenType.OPERATOR, close);
        }

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        parser.ctx.logDebug("parseList end");

        return expr.elements;
    }
}

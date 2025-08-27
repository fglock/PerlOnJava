package org.perlonjava.parser;

import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The ListParser class provides methods to parse lists in various contexts,
 * such as function calls, array literals, and hash literals. It supports
 * parsing lists with or without parentheses and handles optional arguments.
 */
public class ListParser {

    /**
     * Parses a list with zero or one optional argument. The list can be enclosed
     * in parentheses or not. Commas are allowed after the argument.
     * <p>
     * Examples:
     * - rand()
     * - rand(10)
     * - rand, rand 10,
     *
     * @param parser   The parser instance.
     * @param minItems The minimum number of items required in the list.
     * @return A ListNode representing the parsed list.
     * @throws PerlCompilerException If the syntax is incorrect or the minimum number of items is not met.
     */
    static ListNode parseZeroOrOneList(Parser parser, int minItems) {
        if (looksLikeEmptyList(parser)) {
            // Return an empty list if it looks like an empty list
            if (minItems > 0) {
                parser.throwError("syntax error");
            }
            return new ListNode(parser.tokenIndex);
        }

        ListNode expr;
        LexerToken token = TokenUtils.peek(parser);
        if (token.text.equals("(")) {
            // Argument in parentheses, can be 0 or 1 argument: rand(), rand(10)
            // Commas are allowed after the single argument: rand(10,)
            TokenUtils.consume(parser);
            expr = new ListNode(parseList(parser, ")", 0), parser.tokenIndex);
            if (expr.elements.size() > 1) {
                parser.throwError("syntax error");
            }
        } else if (token.type == LexerTokenType.EOF || ParserTables.LIST_TERMINATORS.contains(token.text) || token.text.equals(",")) {
            // No argument
            expr = new ListNode(parser.tokenIndex);
        } else {
            // Argument without parentheses
            expr = ListNode.makeList(parser.parseExpression(parser.getPrecedence("isa") + 1));
        }
        if (expr.elements.size() < minItems) {
            parser.throwError("syntax error");
        }
        return expr;
    }

    /**
     * Parses a list with zero or more arguments. The list can be enclosed in
     * parentheses or not. Supports various parsing contexts such as block nodes,
     * file handles, and regex patterns.
     *
     * @param parser          The parser instance.
     * @param minItems        The minimum number of items required in the list.
     * @param wantBlockNode   Indicates if a block node is expected.
     * @param obeyParentheses Indicates if parentheses should be obeyed.
     * @param wantFileHandle  Indicates if a file handle is expected.
     * @param wantRegex       Indicates if a regex pattern is expected.
     * @return A ListNode representing the parsed list.
     * @throws PerlCompilerException If the syntax is incorrect or the minimum number of items is not met.
     */
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
            if (TokenUtils.peek(parser).text.equals("/") || TokenUtils.peek(parser).text.equals("//") || TokenUtils.peek(parser).text.equals("/=")) {
                TokenUtils.consume(parser);
                Node regex = StringParser.parseRawString(parser, "/");
                if (regex != null) {
                    matched = true;
                    expr.elements.add(regex);
                    token = TokenUtils.peek(parser);
                    if (token.type != LexerTokenType.EOF && !ParserTables.LIST_TERMINATORS.contains(token.text)) {
                        // Consume comma
                        PrototypeArgs.consumeCommaIfPresent(parser, false);
                    }
                }
            }
            if (!matched) {
                // Backtrack
                parser.tokenIndex = currentIndex;
                hasParen = false;
            }
        }

        if (wantFileHandle) {
            if (TokenUtils.peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                hasParen = true;
            }
            expr.handle = FileHandle.parseFileHandle(parser);
            if (expr.handle == null || !isSpaceAfterPrintBlock(parser)) {
                // Backtrack
                parser.debugHeredocState("FILEHANDLE_BEFORE_BACKTRACK");
                expr.handle = null;
                parser.tokenIndex = currentIndex;
                parser.debugHeredocState("FILEHANDLE_AFTER_BACKTRACK");
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
                expr.handle = ParseBlock.parseBlock(parser);
                TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            }
            if (!isSpaceAfterPrintBlock(parser) || looksLikeEmptyList(parser)) {
                parser.throwError("syntax error");
            }
        }

        if (!looksLikeEmptyList(parser)) {
            // It doesn't look like an empty list
            token = TokenUtils.peek(parser);
            if (obeyParentheses && token.text.equals("(")) {
                // Arguments in parentheses, can be 0 or more arguments: print(), print(10)
                // Commas are allowed after the arguments: print(10,)
                TokenUtils.consume(parser);
                expr.elements.addAll(parseList(parser, ")", 0));
            } else {
                while (token.type != LexerTokenType.EOF && !ParserTables.LIST_TERMINATORS.contains(token.text)) {
                    // Argument without parentheses
                    expr.elements.add(parser.parseExpression(parser.getPrecedence(",")));
                    token = TokenUtils.peek(parser);
                    if (isComma(token)) {
                        token = consumeCommas(parser);
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
            parser.throwError("syntax error");
        }
        return expr;
    }

    static boolean isComma(LexerToken token) {
        return token.text.equals(",") || token.text.equals("=>");
    }

    static LexerToken consumeCommas(Parser parser) {
        LexerToken token = TokenUtils.peek(parser);
        while (isComma(token)) {
            TokenUtils.consume(parser);
            token = TokenUtils.peek(parser);
        }
        return token;
    }

    /**
     * Parses a generic list with a specified closing delimiter. This method is
     * used for parsing various constructs like parentheses, hash literals, and
     * array literals.
     * <p>
     * Example usage:
     * - new ListNode(parseList(")", 0), tokenIndex);
     * - new HashLiteralNode(parseList("}", 1), tokenIndex);
     * - new ArrayLiteralNode(parseList("]", 1), tokenIndex);
     *
     * @param parser   The parser instance.
     * @param close    The closing delimiter for the list.
     * @param minItems The minimum number of items required in the list.
     * @return A list of Nodes representing the parsed elements.
     * @throws PerlCompilerException If the syntax is incorrect or the minimum number of items is not met.
     */
    static List<Node> parseList(Parser parser, String close, int minItems) {
        parser.ctx.logDebug("parseList start");
        ListNode expr;

        LexerToken token = TokenUtils.peek(parser);
        parser.ctx.logDebug("parseList start at " + token);
        if (token.text.equals(close)) {
            // Empty list
            TokenUtils.consume(parser);
            expr = new ListNode(parser.tokenIndex);
        } else {
            expr = ListNode.makeList(parser.parseExpression(0));
            parser.ctx.logDebug("parseList end at " + TokenUtils.peek(parser));
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, close);
        }

        if (expr.elements.size() < minItems) {
            parser.throwError("syntax error");
        }
        parser.ctx.logDebug("parseList end");

        return expr.elements;
    }

    /**
     * Determines if the current token sequence looks like an empty list.
     *
     * @param parser The parser instance.
     * @return True if the sequence looks like an empty list, false otherwise.
     */
    public static boolean looksLikeEmptyList(Parser parser) {
        boolean isEmptyList = false;
        int previousIndex = parser.tokenIndex;

        // Save heredoc state before testing
        List<OperatorNode> savedHeredocNodes = ParseHeredoc.saveHeredocState(parser);

        LexerToken token = TokenUtils.consume(parser);
        LexerToken token1 = parser.tokens.get(parser.tokenIndex); // Next token including spaces
        LexerToken nextToken = TokenUtils.peek(parser);  // After spaces

        if (token.type == LexerTokenType.EOF || ParserTables.LIST_TERMINATORS.contains(token.text)
                || token.text.equals("->")) {
            isEmptyList = true;
        } else if (token.text.equals("-")) {
            // -d, -e, -f, -l, -p, -x
            // -$v
            parser.ctx.logDebug("parseZeroOrMoreList looks like file test operator or unary minus");
        } else if (ParserTables.INFIX_OP.contains(token.text) || token.text.equals(",")) {
            parser.ctx.logDebug("parseZeroOrMoreList infix `" + token.text + "` followed by `" + nextToken.text + "`");
            if (token.text.equals("<") || token.text.equals("<<")) {
                // Looks like diamond operator
                parser.ctx.logDebug("parseZeroOrMoreList looks like <>");
            } else if (token.text.equals("+")) {
                // Looks like a prefix `+`, not an infix `+`
                parser.ctx.logDebug("parseZeroOrMoreList looks like prefix plus");
            } else if (token.text.equals("*")) {
                // Looks like a prefix `*`, not an infix `*`
                parser.ctx.logDebug("parseZeroOrMoreList looks like typeglob prefix");
            } else if (token.text.equals("&")) {
                // Looks like a subroutine call, not an infix `&`
                parser.ctx.logDebug("parseZeroOrMoreList looks like subroutine call");
            } else if (token.text.equals("%") && (nextToken.text.equals("$") || nextToken.type == LexerTokenType.IDENTIFIER)) {
                // Looks like a hash deref, not an infix `%`
                parser.ctx.logDebug("parseZeroOrMoreList looks like Hash");
            } else if (token.text.equals(".") && token1.type == LexerTokenType.NUMBER) {
                // Looks like a fractional number, not an infix `.`
                parser.ctx.logDebug("parseZeroOrMoreList looks like Number");
            } else {
                // Subroutine call with zero arguments, followed by infix operator: `pos = 3`
                parser.ctx.logDebug("parseZeroOrMoreList return zero at `" + parser.tokens.get(parser.tokenIndex) + "`");
                // if (LVALUE_INFIX_OP.contains(token.text)) {
                //    throw new PerlCompilerException(tokenIndex, "Can't modify non-lvalue subroutine call", ctx.errorUtil);
                // }
                isEmptyList = true;
            }
        }
        parser.debugHeredocState("LOOKS_LIKE_EMPTY_BEFORE_BACKTRACK");
        parser.tokenIndex = previousIndex;

        // Restore heredoc state if needed
        ParseHeredoc.restoreHeredocStateIfNeeded(parser, savedHeredocNodes);

        parser.debugHeredocState("LOOKS_LIKE_EMPTY_AFTER_BACKTRACK");
        return isEmptyList;
    }

    public static boolean isSpaceAfterPrintBlock(Parser parser) {
        int currentIndex = parser.tokenIndex;
        LexerToken token = peek(parser);
        boolean isSpace = false;
        switch (token.type) {
            case EOF:
            case IDENTIFIER:
            case NUMBER:
            case STRING:
                isSpace = true;
                break;
            case OPERATOR:
                switch (token.text) {
                    case "[", "\"", "//", "\\", "`", "$", "$#", "@", "%", "&", "!", "~",
                         "+", "-", "/", "*", ";", "++", "--", "<", "<<":
                        isSpace = true;
                        break;
                    case ".":
                        // must be followed by NUMBER
                        TokenUtils.consume(parser);
                        if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                            isSpace = true;
                        }
                        parser.tokenIndex = currentIndex;
                        break;
                    case "(", "'":
                        // must have space before
                        TokenUtils.consume(parser);
                        if (parser.tokenIndex != currentIndex) {
                            isSpace = true;
                        }
                        parser.tokenIndex = currentIndex;
                        break;
                }
                break;
        }
        return isSpace;
    }
}

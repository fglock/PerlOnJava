package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

import java.util.List;

import static org.perlonjava.frontend.lexer.LexerTokenType.OPERATOR;
import static org.perlonjava.frontend.parser.TokenUtils.peek;

public class ParseMapGrepSort {
    /**
     * Parses sort operator.
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A BinaryOperatorNode representing the parsed operator.
     */
    static BinaryOperatorNode parseSort(Parser parser, LexerToken token) {
        ListNode operand;
        int currentIndex = parser.tokenIndex;

        LexerToken nextToken = peek(parser);

        // Check for sort( SUBNAME LIST ) — peek inside parens for the SUBNAME pattern
        boolean hasSortParen = false;
        if (nextToken.text.equals("(")) {
            int saveIdx = parser.tokenIndex;
            TokenUtils.consume(parser);
            LexerToken insideToken = peek(parser);
            if ((insideToken.type == LexerTokenType.IDENTIFIER || insideToken.text.equals("::"))
                    && !insideToken.text.equals("{")
                    && !ParserTables.CORE_PROTOTYPES.containsKey(insideToken.text)
                    && !ParsePrimary.isIsQuoteLikeOperator(insideToken.text)) {
                // Check that this identifier is followed by a list (not -> or ()  )
                int identSave = parser.tokenIndex;
                IdentifierParser.parseSubroutineIdentifier(parser);
                String afterIdent = peek(parser).text;
                parser.tokenIndex = identSave;
                if (!afterIdent.equals("->") && !afterIdent.equals("(") && !afterIdent.equals(")")) {
                    hasSortParen = true;
                    nextToken = insideToken;
                } else {
                    parser.tokenIndex = saveIdx;
                    nextToken = peek(parser);
                }
            } else {
                parser.tokenIndex = saveIdx;
                nextToken = peek(parser);
            }
        }

        if ((nextToken.type == LexerTokenType.IDENTIFIER || nextToken.text.equals("::"))
                && !nextToken.text.equals("{")
                && !ParserTables.CORE_PROTOTYPES.containsKey(nextToken.text)
                && !ParsePrimary.isIsQuoteLikeOperator(nextToken.text)) {
            // This could be a subroutine name for comparison (sort mysub LIST)
            // or a class name for method call (sort MyClass->method)
            // Save position and try to determine which
            int identStart = parser.tokenIndex;
            String subName = IdentifierParser.parseSubroutineIdentifier(parser);
            
            // Check if followed by -> (method call) - if so, backtrack and parse as list
            if (peek(parser).text.equals("->")) {
                // This is a method call like "sort MyClass->method" 
                // Backtrack and parse the whole thing as a list expression
                parser.tokenIndex = hasSortParen ? currentIndex : identStart;
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("parseSort method call: " + operand);
            } else {
                // This is a comparison subroutine name
                Node var = new OperatorNode("&",
                        new IdentifierNode(subName, parser.tokenIndex), parser.tokenIndex);
                operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
                operand.handle = var;
                if (hasSortParen) {
                    TokenUtils.consume(parser, OPERATOR, ")");
                }
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("parseSort identifier: " + operand.handle + " : " + operand);
            }
        } else {
            try {
                operand = ListParser.parseZeroOrMoreList(parser, 1, true, false, false, false);
            } catch (PerlCompilerException e) {
                parser.tokenIndex = currentIndex;

                boolean paren = false;
                if (peek(parser).text.equals("(")) {
                    TokenUtils.consume(parser);
                    paren = true;
                }

                parser.parsingForLoopVariable = true;
                Node var = ParsePrimary.parsePrimary(parser);
                parser.parsingForLoopVariable = false;
                operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
                operand.handle = var;

                if (paren) {
                    TokenUtils.consume(parser, OPERATOR, ")");
                }
                if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("parseSort: " + operand.handle + " : " + operand);
            }
        }

        // transform:   { 123 }
        // into:        sub { 123 }
        Node block = operand.handle;
        operand.handle = null;
        if (block == null) {
            // create default block for `sort`: { $a cmp $b }
            // Use the current package's $a and $b variables
            String currentPackage = parser.ctx.symbolTable.getCurrentPackage();
            block = new BlockNode(List.of(new BinaryOperatorNode("cmp", new OperatorNode("$", new IdentifierNode(currentPackage + "::a", parser.tokenIndex), parser.tokenIndex), new OperatorNode("$", new IdentifierNode(currentPackage + "::b", parser.tokenIndex), parser.tokenIndex), parser.tokenIndex)), parser.tokenIndex);
        }
        if (block instanceof BlockNode) {
            // Sort's comparator is a proper subroutine — `return $b <=> $a`
            // must return the comparison value, not propagate as a non-local
            // return through the enclosing sub. So we do NOT set the
            // `isMapGrepBlock` annotation here (contrast `parseMapGrep`
            // below, where `return` is a "pseudo block" escape and must
            // propagate).
            SubroutineNode subNode = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
            block = subNode;
        }
        return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
    }

    /**
     * Parses map and grep operators.
     *
     * @param parser The Parser instance.
     * @param token  The current LexerToken.
     * @return A BinaryOperatorNode representing the parsed operator.
     */
    static BinaryOperatorNode parseMapGrep(Parser parser, LexerToken token) {
        ListNode operand;
        int currentIndex = parser.tokenIndex;
        try {
            // Handle 'map' keyword as a Binary operator with a Code and List operands
            operand = ListParser.parseZeroOrMoreList(parser, 1, true, false, false, false);
        } catch (PerlCompilerException e) {
            // map chr, 1,2,3
            parser.tokenIndex = currentIndex;

            boolean paren = false;
            if (peek(parser).text.equals("(")) {
                TokenUtils.consume(parser);
                paren = true;
            }

            parser.parsingForLoopVariable = true;  // Parentheses are allowed after a variable
            Node var = parser.parseExpression(parser.getPrecedence(","));
            parser.parsingForLoopVariable = false;
            TokenUtils.consume(parser, OPERATOR, ",");
            operand = ListParser.parseZeroOrMoreList(parser, 1, false, false, false, false);
            operand.handle = new BlockNode(List.of(var), parser.tokenIndex);

            if (paren) {
                TokenUtils.consume(parser, OPERATOR, ")");
            }
            if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("parseMap: " + operand.handle + " : " + operand);
        }

        // transform:   { 123 }
        // into:        sub { 123 }
        Node block = operand.handle;
        operand.handle = null;
        if (block == null) {
            // use the first argument as block: 'map ord, 1,2,3'
            if (!operand.elements.isEmpty()) {
                block = new BlockNode(
                        List.of(operand.elements.removeFirst()),
                        parser.tokenIndex);
            }
        }
        if (block instanceof BlockNode) {
            SubroutineNode subNode = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
            subNode.setAnnotation("isMapGrepBlock", true);
            block = subNode;
        }
        return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
    }
}

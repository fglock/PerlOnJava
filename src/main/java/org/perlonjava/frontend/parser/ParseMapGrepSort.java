package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
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

        LexerToken firstToken = peek(parser);
        if (firstToken.type == LexerTokenType.IDENTIFIER
                && !ParserTables.CORE_PROTOTYPES.containsKey(firstToken.text)
                && !ParsePrimary.isIsQuoteLikeOperator(firstToken.text)) {
            int savedIndex = parser.tokenIndex;
            String subName = IdentifierParser.parseSubroutineIdentifier(parser);
            if (subName != null && !subName.isEmpty()) {
                LexerToken afterName = peek(parser);
                if (!afterName.text.equals("(") && !afterName.text.equals("=>")) {
                    String fullName = subName.contains("::")
                            ? subName
                            : NameNormalizer.normalizeVariableName(subName,
                            parser.ctx.symbolTable.getCurrentPackage());
                    operand = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
                    Node block = new OperatorNode("&",
                            new IdentifierNode(fullName, currentIndex), currentIndex);
                    return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
                }
            }
            parser.tokenIndex = savedIndex;
        }

        try {
            // Handle 'sort' keyword as a Binary operator with a Code and List operands
            operand = ListParser.parseZeroOrMoreList(parser, 1, true, false, false, false);
        } catch (PerlCompilerException e) {
            // sort $sub 1,2,3
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
            parser.ctx.logDebug("parseSort: " + operand.handle + " : " + operand);
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
            block = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
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
            parser.ctx.logDebug("parseMap: " + operand.handle + " : " + operand);
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
            block = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
        }
        return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
    }
}

package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The ParseStatement class is responsible for parsing individual statements in the source code.
 * It handles various types of statements such as control structures, declarations, and expressions.
 */
public class ParseStatement {

    /**
     * Parses a single statement from the parser's token stream.
     *
     * @param parser The parser instance used for parsing.
     * @param label  The label for the statement, if any.
     * @return A Node representing the parsed statement.
     */
    public static Node parseStatement(Parser parser, String label) {
        int currentIndex = parser.tokenIndex;
        LexerToken token = peek(parser);
        parser.ctx.logDebug("parseStatement `" + token.text + "`");

        if (token.type == LexerTokenType.IDENTIFIER) {
            switch (token.text) {
                case "CHECK", "INIT", "UNITCHECK", "BEGIN", "END":
                    // Parse special block statements
                    return SpecialBlockParser.parseSpecialBlock(parser);
                case "if", "unless":
                    // Parse conditional statements
                    return StatementParser.parseIfStatement(parser);
                case "for", "foreach":
                    // Parse loop statements
                    return StatementParser.parseForStatement(parser, label);
                case "while", "until":
                    // Parse while/until loop statements
                    return StatementParser.parseWhileStatement(parser, label);
                case "try":
                    // Parse try-catch statements if the feature is enabled
                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("try")) {
                        return StatementParser.parseTryStatement(parser);
                    }
                    break;
                case "package":
                    // Parse package declarations
                    return StatementParser.parsePackageDeclaration(parser, token);
                case "class":
                    // Parse class if the feature is enabled
                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("class")) {
                        return StatementParser.parsePackageDeclaration(parser, token);
                    }
                    break;
                case "use", "no":
                    // Parse use/no declarations
                    return StatementParser.parseUseDeclaration(parser, token);
                case "sub":
                    // Parse subroutine definitions
                    parser.tokenIndex++;
                    if (peek(parser).type == LexerTokenType.IDENTIFIER) {
                        return SubroutineParser.parseSubroutineDefinition(parser, true, "our");
                    }
                    // Otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    break;
                case "our", "my", "state":
                    // Parse variable declarations
                    String declaration = consume(parser).text;
                    if (consume(parser).text.equals("sub") && peek(parser).type == LexerTokenType.IDENTIFIER) {
                        // my sub name
                        return SubroutineParser.parseSubroutineDefinition(parser, true, declaration);
                    }
                    // Otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    break;
            }
        }
        if (token.type == LexerTokenType.OPERATOR) {
            switch (token.text) {
                case "...":
                    // Handle unimplemented operator
                    TokenUtils.consume(parser);
                    return new OperatorNode(
                            "die",
                            new StringNode("Unimplemented", parser.tokenIndex),
                            parser.tokenIndex);
                case "{":
                    // Parse bare-blocks
                    if (!isHashLiteral(parser)) {
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
                        BlockNode block = ParseBlock.parseBlock(parser);
                        block.isLoop = true;
                        block.labelName = label;
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                        return block;
                    }
            }
        }
        // Parse expressions
        Node expression = parser.parseExpression(0);
        token = peek(parser);
        if (token.type == LexerTokenType.IDENTIFIER) {
            // Handle statement modifiers: if, for, etc.
            switch (token.text) {
                case "if":
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    return new BinaryOperatorNode("&&", modifierExpression, expression, parser.tokenIndex);
                case "unless":
                    TokenUtils.consume(parser);
                    modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    return new BinaryOperatorNode("||", modifierExpression, expression, parser.tokenIndex);
                case "for", "foreach":
                    TokenUtils.consume(parser);
                    modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    return new For1Node(
                            null,
                            false,
                            new OperatorNode("$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex),  // $_
                            modifierExpression,
                            expression,
                            null,
                            parser.tokenIndex);
                case "while", "until":
                    TokenUtils.consume(parser);
                    modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    if (token.text.equals("until")) {
                        modifierExpression = new OperatorNode("not", modifierExpression, modifierExpression.getIndex());
                    }
                    boolean isDoWhile = false;
                    if (expression instanceof BlockNode) {
                        // Special case: `do { BLOCK } while CONDITION`
                        // Executes the loop at least once
                        parser.ctx.logDebug("do-while " + expression);
                        isDoWhile = true;
                    }
                    return new For3Node(null,
                            false,
                            null, modifierExpression,
                            null, expression, null,
                            isDoWhile,
                            parser.tokenIndex);
            }
            throw new PerlCompilerException(parser.tokenIndex, "Not implemented: " + token, parser.ctx.errorUtil);
        }
        parseStatementTerminator(parser);
        return expression;
    }

    // disambiguate between Block or Hash literal
    public static boolean isHashLiteral(Parser parser) {
        int currentIndex = parser.tokenIndex;

        // Start after the opening '{'
        consume(parser, LexerTokenType.OPERATOR, "{");

        int braceCount = 1; // Track nested braces
        while (braceCount > 0) {
            LexerToken token = consume(parser);
            parser.ctx.logDebug("isHashLiteral " + token + " braceCount:" + braceCount);
            if (token.type == LexerTokenType.EOF) {
                break; // not a hash literal;
            }
            switch (token.text) {
                case "{", "(", "[":
                    braceCount++;
                    break;
                case ")", "}", "]":
                    braceCount--;
                    break;
                default:
                    if (braceCount == 1) {
                        switch (token.text) {
                            case ",", "=>":
                                parser.ctx.logDebug("isHashLiteral TRUE");
                                parser.tokenIndex = currentIndex;
                                return true; // Likely a hash literal
                            case ";":
                                parser.tokenIndex = currentIndex;
                                return false; // Likely a block
                            case "for", "while", "if", "unless", "until", "foreach":
                                if (!TokenUtils.peek(parser).text.equals("=>")) {
                                    parser.ctx.logDebug("isHashLiteral FALSE");
                                    parser.tokenIndex = currentIndex;
                                    return false; // Likely a block
                                }
                        }
                    }
            }
        }
        parser.ctx.logDebug("isHashLiteral undecided");
        parser.tokenIndex = currentIndex;
        return true;
    }

    public static void parseStatementTerminator(Parser parser) {
        LexerToken token = peek(parser);
        if (token.type != LexerTokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }
        if (token.text.equals(";")) {
            consume(parser);
        }
    }
}

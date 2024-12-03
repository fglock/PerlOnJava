package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

public class ParseStatement {
    public static Node parseStatement(Parser parser) {
        int currentIndex = parser.tokenIndex;
        LexerToken token = peek(parser);
        parser.ctx.logDebug("parseStatement `" + token.text + "`");

        // check for label:
        String label = null;
        if (token.type == LexerTokenType.IDENTIFIER) {
            String id = TokenUtils.consume(parser).text;
            if (peek(parser).text.equals(":")) {
                label = id;
                TokenUtils.consume(parser);
                token = peek(parser);
            } else {
                parser.tokenIndex = currentIndex;  // backtrack
            }
        }

        if (token.type == LexerTokenType.IDENTIFIER) {
            switch (token.text) {
                case "CHECK", "INIT", "UNITCHECK", "BEGIN", "END":
                    return SpecialBlockParser.parseSpecialBlock(parser);
                case "if", "unless":
                    return StatementParser.parseIfStatement(parser);
                case "for", "foreach":
                    return StatementParser.parseForStatement(parser, label);
                case "while", "until":
                    return StatementParser.parseWhileStatement(parser, label);
                case "try":
                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("try")) {
                        return StatementParser.parseTryStatement(parser);
                    }
                    break;
                case "package":
                    return StatementParser.parsePackageDeclaration(parser, token);
                case "use", "no":
                    return StatementParser.parseUseDeclaration(parser, token);
                case "sub":
                    // Must be followed by an identifier
                    parser.tokenIndex++;
                    if (peek(parser).type == LexerTokenType.IDENTIFIER) {
                        return SubroutineParser.parseSubroutineDefinition(parser, true, "our");
                    }
                    // otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    break;
                case "our", "my", "state":
                    String declaration = consume(parser).text;
                    if (consume(parser).text.equals("sub") && peek(parser).type == LexerTokenType.IDENTIFIER) {
                        // my sub name
                        return SubroutineParser.parseSubroutineDefinition(parser, true, declaration);
                    }
                    // otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    break;
            }
        }
        if (token.type == LexerTokenType.OPERATOR) {
            switch (token.text) {
                case "...":
                    TokenUtils.consume(parser);
                    return new OperatorNode(
                            "die",
                            new StringNode("Unimplemented", parser.tokenIndex),
                            parser.tokenIndex);
                case "{":
                    if (!parser.isHashLiteral()) { // bare-block
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
                        BlockNode block = parser.parseBlock();
                        block.isLoop = true;
                        block.labelName = label;
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                        return block;
                    }
            }
        }
        Node expression = parser.parseExpression(0);
        token = peek(parser);
        if (token.type == LexerTokenType.IDENTIFIER) {
            // statement modifier: if, for ...
            switch (token.text) {
                case "if":
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parser.parseStatementTerminator();
                    return new BinaryOperatorNode("&&", modifierExpression, expression, parser.tokenIndex);
                case "unless":
                    TokenUtils.consume(parser);
                    modifierExpression = parser.parseExpression(0);
                    parser.parseStatementTerminator();
                    return new BinaryOperatorNode("||", modifierExpression, expression, parser.tokenIndex);
                case "for", "foreach":
                    TokenUtils.consume(parser);
                    modifierExpression = parser.parseExpression(0);
                    parser.parseStatementTerminator();
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
                    parser.parseStatementTerminator();
                    if (token.text.equals("until")) {
                        modifierExpression = new OperatorNode("not", modifierExpression, modifierExpression.getIndex());
                    }
                    boolean isDoWhile = false;
                    if (expression instanceof BlockNode) {
                        // special case:  `do { BLOCK } while CONDITION`
                        // executes the loop at least once
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
        parser.parseStatementTerminator();
        return expression;
    }
}

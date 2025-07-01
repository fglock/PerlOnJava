package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.ByteCodeSourceMapper;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import static org.perlonjava.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The StatementResolver class is responsible for parsing individual statements in the source code.
 * It handles various types of statements such as control structures, declarations, and expressions.
 */
public class StatementResolver {

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

        // Store the current source location - this will be used for stack trace generation
        ByteCodeSourceMapper.saveSourceLocation(parser.ctx, parser.tokenIndex);

        if (token.type == LexerTokenType.IDENTIFIER) {
            Node result = switch (token.text) {
                case "CHECK", "INIT", "UNITCHECK", "BEGIN", "END" ->
                        SpecialBlockParser.parseSpecialBlock(parser);

                case "if", "unless" ->
                        StatementParser.parseIfStatement(parser);

                case "for", "foreach" ->
                        StatementParser.parseForStatement(parser, label);

                case "while", "until" ->
                        StatementParser.parseWhileStatement(parser, label);

                case "try" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("try")
                        ? StatementParser.parseTryStatement(parser)
                        : null;

                case "package" ->
                        StatementParser.parsePackageDeclaration(parser, token);

                case "class" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("class")
                        ? StatementParser.parsePackageDeclaration(parser, token)
                        : null;

                case "use", "no" ->
                        StatementParser.parseUseDeclaration(parser, token);

                case "sub" -> {
                    parser.tokenIndex++;
                    if (peek(parser).type == LexerTokenType.IDENTIFIER) {
                        yield SubroutineParser.parseSubroutineDefinition(parser, true, "our");
                    }
                    // Otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    yield null;
                }

                case "our", "my", "state" -> {
                    String declaration = consume(parser).text;
                    if (consume(parser).text.equals("sub") && peek(parser).type == LexerTokenType.IDENTIFIER) {
                        // my sub name
                        yield SubroutineParser.parseSubroutineDefinition(parser, true, declaration);
                    }
                    // Otherwise backtrack
                    parser.tokenIndex = currentIndex;
                    yield null;
                }

                default -> null;
            };

            if (result != null) {
                return result;
            }
        }

        // Use pattern matching with switch expression for OPERATOR tokens
        if (token.type == LexerTokenType.OPERATOR) {
            Node result = switch (token.text) {
                case "..." -> {
                    TokenUtils.consume(parser);
                    yield new OperatorNode(
                            "die",
                            new StringNode("Unimplemented", parser.tokenIndex),
                            parser.tokenIndex);
                }

                case "{" -> {
                    if (!isHashLiteral(parser)) {
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
                        BlockNode block = ParseBlock.parseBlock(parser);
                        block.isLoop = true;
                        block.labelName = label;
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                        yield block;
                    }
                    yield null;
                }

                default -> null;
            };

            if (result != null) {
                return result;
            }
        }

        // Parse expressions
        Node expression = parser.parseExpression(0);
        token = peek(parser);

        if (token.type == LexerTokenType.IDENTIFIER) {
            // Handle statement modifiers using switch expression
            return switch (token.text) {
                case "if" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    yield new BinaryOperatorNode("&&", modifierExpression, expression, parser.tokenIndex);
                }

                case "unless" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    yield new BinaryOperatorNode("||", modifierExpression, expression, parser.tokenIndex);
                }

                case "for", "foreach" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    yield new For1Node(
                            null,
                            false,
                            scalarUnderscore(parser),  // $_
                            modifierExpression,
                            expression,
                            null,
                            parser.tokenIndex);
                }

                case "while", "until" -> {
                    TokenUtils.consume(parser);
                    Node modifierExpression = parser.parseExpression(0);
                    parseStatementTerminator(parser);
                    if (token.text.equals("until")) {
                        modifierExpression = new OperatorNode("not", modifierExpression, modifierExpression.getIndex());
                    }
                    boolean isDoWhile = expression instanceof BlockNode;
                    if (isDoWhile) {
                        // Special case: `do { BLOCK } while CONDITION`
                        // Executes the loop at least once
                        parser.ctx.logDebug("do-while " + expression);
                    }
                    yield new For3Node(null,
                            false,
                            null, modifierExpression,
                            null, expression, null,
                            isDoWhile,
                            parser.tokenIndex);
                }

                default -> {
                    parser.throwError("Not implemented: " + token);
                    yield null;
                }
            };
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
        boolean hasHashIndicator = false;  // Found =>, or comma in hash-like context
        boolean hasBlockIndicator = false; // Found ;, or statement modifier

        while (braceCount > 0) {
            LexerToken token = consume(parser);
            parser.ctx.logDebug("isHashLiteral " + token + " braceCount:" + braceCount);
            if (token.type == LexerTokenType.EOF) {
                break; // Let caller handle EOF error
            }

            // Update brace count based on token
            braceCount = switch (token.text) {
                case "{", "(", "[" -> braceCount + 1;
                case ")", "}", "]" -> braceCount - 1;
                default -> braceCount;
            };

            // Only check for indicators at depth 1
            if (braceCount == 1 && !token.text.matches("[{(\\[)}\\]]")) {
                switch (token.text) {
                    case "=>" -> {
                        // Fat comma is a definitive hash indicator
                        hasHashIndicator = true;
                    }
                    case ";" -> {
                        // Semicolon is a definitive block indicator
                        hasBlockIndicator = true;
                    }
                    case "," -> {
                        // Comma alone is not definitive - could be function args or hash
                        // Continue scanning for more evidence
                        parser.ctx.logDebug("isHashLiteral found comma, continuing scan");
                    }
                    case "for", "while", "if", "unless", "until", "foreach" -> {
                        // Check if this is a hash key (followed by =>) or statement modifier
                        LexerToken nextToken = TokenUtils.peek(parser);
                        if (!nextToken.text.equals("=>") && !nextToken.text.equals(",")) {
                            // Statement modifier - definitive block indicator
                            parser.ctx.logDebug("isHashLiteral found statement modifier");
                            hasBlockIndicator = true;
                        }
                    }
                }
            }

            // Early exit if we have definitive evidence
            if (hasBlockIndicator) {
                parser.ctx.logDebug("isHashLiteral FALSE - block indicator found");
                parser.tokenIndex = currentIndex;
                return false;
            }
        }

        parser.tokenIndex = currentIndex;

        // Decision logic:
        // - If we found => it's definitely a hash
        // - If we found block indicators, it's a block
        // - Otherwise, default to hash (empty {} is a hash ref)
        if (hasHashIndicator) {
            parser.ctx.logDebug("isHashLiteral TRUE - hash indicator found");
            return true;
        } else if (hasBlockIndicator) {
            parser.ctx.logDebug("isHashLiteral FALSE - block indicator found");
            return false;
        } else {
            parser.ctx.logDebug("isHashLiteral TRUE - default for ambiguous case");
            return true; // Default: {} is an empty hash ref
        }
    }

    public static void parseStatementTerminator(Parser parser) {
        LexerToken token = peek(parser);
        if (token.type != LexerTokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
            parser.throwError("syntax error");
        }
        if (token.text.equals(";")) {
            consume(parser);
        }
    }
}

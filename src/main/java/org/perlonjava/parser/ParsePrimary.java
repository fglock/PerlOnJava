package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.perlmodule.Subs;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeGlob;

import static org.perlonjava.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.parser.TokenUtils.peek;
import static org.perlonjava.runtime.GlobalVariable.existsGlobalCodeRef;

/**
 * The ParsePrimary class is responsible for parsing primary expressions in the source code.
 * It handles identifiers, numbers, strings, operators, and other primary constructs.
 */
public class ParsePrimary {

    /**
     * Parses a primary expression from the parser's token stream.
     *
     * @param parser The parser instance used for parsing.
     * @return A Node representing the parsed primary expression.
     */
    public static Node parsePrimary(Parser parser) {
        int startIndex = parser.tokenIndex;
        LexerToken token = TokenUtils.consume(parser); // Consume the next token from the input
        String operator = token.text;
        Node operand;

        switch (token.type) {
            case IDENTIFIER:
                return parseIdentifier(parser, startIndex, token, operator);
            case NUMBER:
                // Handle number literals
                return NumberParser.parseNumber(parser, token);
            case STRING:
                // Handle string literals
                return new StringNode(token.text, parser.tokenIndex);
            case OPERATOR:
                return parseOperator(parser, token, operator);
            case EOF:
                // Handle end of input
                return null;
            default:
                // Throw an exception for any unexpected token
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
        }
    }

    private static Node parseIdentifier(Parser parser, int startIndex, LexerToken token, String operator) {
        String nextTokenText = peek(parser).text;
        if (nextTokenText.equals("=>")) {
            // Autoquote
            return new StringNode(token.text, parser.tokenIndex);
        }

        boolean operatorEnabled = false;
        boolean calledWithCore = false;

        // Try to parse a builtin operation; backtrack if it fails
        if (token.text.equals("CORE") && nextTokenText.equals("::")) {
            calledWithCore = true;
            operatorEnabled = true;
            TokenUtils.consume(parser);  // "::"
            token = TokenUtils.consume(parser); // operator
            operator = token.text;
        } else if (!nextTokenText.equals("::")) {
            // Check if the operator is enabled in the current scope
            operatorEnabled = switch (operator) {
                case "say", "fc", "state", "evalbytes" -> parser.ctx.symbolTable.isFeatureCategoryEnabled(operator);
                case "__SUB__" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("current_sub");
                default -> true;
            };
        }

        if (!calledWithCore && operatorEnabled && ParserTables.OVERRIDABLE_OP.contains(operator)) {
            // It is possible to override the core function by defining
            // a subroutine in the current package, or in CORE::GLOBAL::
            //
            // Optimization: only test this if an override was defined
            //
            String fullName = parser.ctx.symbolTable.getCurrentPackage() + "::" + operator;
            if (Subs.isSubs.getOrDefault(fullName, false)) {
                // ' use subs "hex"; sub hex { 456 } print hex("123"), "\n" '
                parser.tokenIndex = startIndex;   // backtrack
                return SubroutineParser.parseSubroutineCall(parser, false);
            }
            String coreGlobalName = "CORE::GLOBAL::" + operator;
            if (RuntimeGlob.isGlobAssigned(coreGlobalName) && existsGlobalCodeRef(coreGlobalName)) {
                // ' BEGIN { *CORE::GLOBAL::hex = sub { 456 } } print hex("123"), "\n" '
                parser.tokenIndex = startIndex;   // backtrack
                // Rewrite the subroutine name
                parser.tokens.add(startIndex, new LexerToken(LexerTokenType.IDENTIFIER, "CORE"));
                parser.tokens.add(startIndex + 1, new LexerToken(LexerTokenType.OPERATOR, "::"));
                parser.tokens.add(startIndex + 2, new LexerToken(LexerTokenType.IDENTIFIER, "GLOBAL"));
                parser.tokens.add(startIndex + 3, new LexerToken(LexerTokenType.OPERATOR, "::"));
                return SubroutineParser.parseSubroutineCall(parser, false);
            }
        }

        if (operatorEnabled) {
            Node operation = CoreOperatorResolver.parseCoreOperator(parser, token, startIndex);
            if (operation != null) {
                return operation;
            }
        }

        if (calledWithCore) {
            throw new PerlCompilerException(parser.tokenIndex, "CORE::" + operator + " is not a keyword", parser.ctx.errorUtil);
        }

        // Handle any other identifier as a subroutine call or identifier node
        parser.tokenIndex = startIndex;   // backtrack
        return SubroutineParser.parseSubroutineCall(parser, false);
    }

    private static Node parseOperator(Parser parser, LexerToken token, String operator) {
        Node operand;
        switch (token.text) {
            case "(":
                // Handle parentheses to parse a nested expression or to construct a list
                return new ListNode(ListParser.parseList(parser, ")", 0), parser.tokenIndex);
            case "{":
                // Handle curly brackets to parse a nested expression
                return new HashLiteralNode(ListParser.parseList(parser, "}", 0), parser.tokenIndex);
            case "[":
                // Handle square brackets to parse a nested expression
                return new ArrayLiteralNode(ListParser.parseList(parser, "]", 0), parser.tokenIndex);
            case ".":
                // Handle fractional numbers
                return NumberParser.parseFractionalNumber(parser);
            case "<", "<<":
                return OperatorParser.parseDiamondOperator(parser, token);
            case "'", "\"", "/", "//", "/=", "`":
                // Handle single and double-quoted strings
                return StringParser.parseRawString(parser, token.text);
            case "::":
                // Handle :: at the beginning, which means main::
                // Transform ::foo into main::foo
                LexerToken nextToken2 = peek(parser);
                if (nextToken2.type == LexerTokenType.IDENTIFIER) {
                    // Insert "main" before the ::
                    parser.tokens.add(parser.tokenIndex - 1, new LexerToken(LexerTokenType.IDENTIFIER, "main"));
                    parser.tokenIndex--; // Go back to process "main"
                    return parseIdentifier(parser, parser.tokenIndex,
                            new LexerToken(LexerTokenType.IDENTIFIER, "main"), "main");
                }
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            case "\\":
                // Take reference
                parser.parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                parser.parsingTakeReference = false;
                return new OperatorNode(token.text, operand, parser.tokenIndex);
            case "$", "$#", "@", "%", "*":
                return Variable.parseVariable(parser, token.text);
            case "&":
                return Variable.parseCoderefVariable(parser, token);
            case "!", "+":
                // Handle unary operators like `! +`
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                return new OperatorNode(token.text, operand, parser.tokenIndex);
            case "~", "~.":
                // Handle unary operators like `~ ~.`
                if (parser.ctx.symbolTable.isFeatureCategoryEnabled("bitwise")) {
                    if (operator.equals("~")) {
                        operator = "binary" + operator;
                    }
                } else {
                    if (operator.equals("~.")) {
                        throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                    }
                }
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                return new OperatorNode(operator, operand, parser.tokenIndex);
            case "--":
            case "++":
                // Handle unary operators like `++`
                operand = parser.parseExpression(parser.getPrecedence(token.text));
                return new OperatorNode(token.text, operand, parser.tokenIndex);
            case "-":
                // Handle unary operators like `- -d`
                LexerToken nextToken = parser.tokens.get(parser.tokenIndex);
                if (nextToken.type == LexerTokenType.IDENTIFIER && nextToken.text.length() == 1) {
                    // Handle `-d`
                    operator = "-" + nextToken.text;
                    parser.tokenIndex++;
                    nextToken = peek(parser);
                    if (nextToken.text.equals("_")) {
                        // Handle `-f _`
                        TokenUtils.consume(parser);
                        operand = new IdentifierNode("_", parser.tokenIndex);
                    } else {
                        operand = ListParser.parseZeroOrOneList(parser, 0);
                        if (((ListNode) operand).elements.isEmpty()) {
                            // create `$_` variable
                            operand = scalarUnderscore(parser);
                        }
                    }
                    return new OperatorNode(operator, operand, parser.tokenIndex);
                }
                // Unary minus
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                if (operand instanceof IdentifierNode identifierNode) {
                    // "-name" return string
                    return new StringNode("-" + identifierNode.name, parser.tokenIndex);
                }
                return new OperatorNode("unaryMinus", operand, parser.tokenIndex);
            default:
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
        }
    }
}

package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.Set;

import static org.perlonjava.parser.TokenUtils.peek;
import static org.perlonjava.runtime.GlobalVariable.existsGlobalCodeRef;

/**
 * The ParsePrimary class is responsible for parsing primary expressions in the source code.
 * It handles identifiers, numbers, strings, operators, and other primary constructs.
 */
public class ParsePrimary {

    // The list below was obtained by running this in the perl git:
    // ack  'CORE::GLOBAL::\w+' | perl -n -e ' /CORE::GLOBAL::(\w+)/ && print $1, "\n" ' | sort -u
    private static final Set<String> OVERRIDABLE_OP = Set.of(
            "caller", "chdir", "close", "connect",
            "die", "do",
            "exit",
            "fork",
            "getpwuid", "glob",
            "hex",
            "kill",
            "oct", "open",
            "readline", "readpipe", "rename", "require",
            "stat",
            "time",
            "uc",
            "warn"
    );

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
                case "say", "fc", "state", "evalbytes" ->
                        parser.ctx.symbolTable.isFeatureCategoryEnabled(operator);
                case "__SUB__" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("current_sub");
                default -> true;
            };
        }

        if (!calledWithCore && operatorEnabled && OVERRIDABLE_OP.contains(operator)) {
            // It is possible to override the core function by defining
            // a subroutine in the current package, or in CORE::GLOBAL::
            //
            // Optimization: only test this if an override was defined
            //
            if (existsGlobalCodeRef(parser.ctx.symbolTable.getCurrentPackage() + "::" + operator)) {
                // ' use subs "hex"; sub hex { 456 } print hex("123"), "\n" '
                parser.tokenIndex = startIndex;   // backtrack
                return SubroutineParser.parseSubroutineCall(parser);
            }
            if (existsGlobalCodeRef("CORE::GLOBAL::" + operator)) {
                // ' BEGIN { *CORE::GLOBAL::hex = sub { 456 } } print hex("123"), "\n" '
                parser.tokenIndex = startIndex;   // backtrack
                // Rewrite the subroutine name
                parser.tokens.add(startIndex, new LexerToken(LexerTokenType.IDENTIFIER, "CORE"));
                parser.tokens.add(startIndex + 1, new LexerToken(LexerTokenType.OPERATOR, "::"));
                parser.tokens.add(startIndex + 2, new LexerToken(LexerTokenType.IDENTIFIER, "GLOBAL"));
                parser.tokens.add(startIndex + 3, new LexerToken(LexerTokenType.OPERATOR, "::"));
                return SubroutineParser.parseSubroutineCall(parser);
            }
        }

        if (operatorEnabled) {
            Node operation = OperatorParser.parseCoreOperator(parser, token, startIndex);
            if (operation != null) {
                return operation;
            }
        }

        if (calledWithCore) {
            throw new PerlCompilerException(parser.tokenIndex, "CORE::" + operator + " is not a keyword", parser.ctx.errorUtil);
        }

        // Handle any other identifier as a subroutine call or identifier node
        parser.tokenIndex = startIndex;   // backtrack
        return SubroutineParser.parseSubroutineCall(parser);
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
                            operand = new OperatorNode(
                                    "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
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

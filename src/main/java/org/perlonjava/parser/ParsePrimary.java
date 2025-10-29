package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeGlob;

import static org.perlonjava.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.parser.TokenUtils.peek;
import static org.perlonjava.runtime.GlobalVariable.existsGlobalCodeRef;

/**
 * The ParsePrimary class is responsible for parsing primary expressions in Perl source code.
 *
 * <p>Primary expressions are the basic building blocks of the language, including:
 * <ul>
 *   <li>Identifiers (variables, subroutines, keywords)</li>
 *   <li>Literals (numbers, strings)</li>
 *   <li>Operators (unary and special operators)</li>
 *   <li>Grouping constructs (parentheses, brackets, braces)</li>
 * </ul>
 *
 * <p>This class handles the complex logic of Perl's syntax, including:
 * <ul>
 *   <li>Core function overriding via CORE::GLOBAL::</li>
 *   <li>Feature-based operator enabling (say, fc, state, etc.)</li>
 *   <li>Autoquoting with the fat comma (=>)</li>
 *   <li>Reference operators (\)</li>
 *   <li>File test operators (-f, -d, etc.)</li>
 * </ul>
 *
 * @see Parser
 * @see Node
 */
public class ParsePrimary {

    /**
     * Parses a primary expression from the parser's token stream.
     *
     * <p>This is the main entry point for parsing primary expressions. It consumes
     * the next token from the input stream and delegates to appropriate parsing
     * methods based on the token type.
     *
     * @param parser The parser instance containing the token stream and parsing context
     * @return A Node representing the parsed primary expression, or null for EOF
     * @throws PerlCompilerException if an unexpected token is encountered
     */
    public static Node parsePrimary(Parser parser) {
        int startIndex = parser.tokenIndex;
        LexerToken token = TokenUtils.consume(parser); // Consume the next token from the input
        String operator = token.text;
        Node operand;

        switch (token.type) {
            case IDENTIFIER:
                // Handle identifiers: variables, subroutines, keywords, etc.
                return parseIdentifier(parser, startIndex, token, operator);
            case NUMBER:
                // Handle numeric literals (integers, floats, hex, octal, binary)
                return NumberParser.parseNumber(parser, token);
            case STRING:
                // Handle string literals (already parsed by lexer)
                return new StringNode(token.text, parser.tokenIndex);
            case OPERATOR:
                // Handle operators and special constructs
                return parseOperator(parser, token, operator);
            case EOF:
                // Handle end of input gracefully
                return null;
            default:
                // Any other token type is a syntax error
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
        }
    }

    /**
     * Parses an identifier token, which could be a keyword, subroutine call, or variable.
     *
     * <p>This method handles several complex cases:
     * <ul>
     *   <li>Autoquoting: identifiers before => are treated as strings</li>
     *   <li>CORE:: prefix: explicit core function calls (CORE::print)</li>
     *   <li>Feature checking: some operators require specific features (say, fc, state)</li>
     *   <li>Function overriding: checks for user-defined overrides of core functions</li>
     *   <li>Core operators: delegates to CoreOperatorResolver for built-in operators</li>
     *   <li>Subroutine calls: defaults to parsing as a subroutine call</li>
     * </ul>
     *
     * @param parser     The parser instance
     * @param startIndex The token index where this identifier started
     * @param token      The identifier token being parsed
     * @param operator   The text of the identifier (same as token.text)
     * @return A Node representing the parsed identifier construct
     * @throws PerlCompilerException if CORE:: is used with a non-keyword
     */
    private static Node parseIdentifier(Parser parser, int startIndex, LexerToken token, String operator) {
        String nextTokenText = parser.tokens.get(parser.tokenIndex).text;
        String peekTokenText = peek(parser).text;

        // Check for autoquoting: bareword => is treated as "bareword"
        if (peekTokenText.equals("=>")) {
            // Autoquote: convert identifier to string literal
            return new StringNode(token.text, parser.tokenIndex);
        }

        boolean operatorEnabled = false;
        boolean calledWithCore = false;

        // Check for quote-like operators that should always be parsed as operators

        // Check if this is an explicit CORE:: call (e.g., CORE::print)
        if (token.text.equals("CORE") && nextTokenText.equals("::")) {
            calledWithCore = true;
            operatorEnabled = true; // CORE:: functions are always enabled
            TokenUtils.consume(parser);  // consume "::"
            token = TokenUtils.consume(parser); // consume the actual operator
            operator = token.text;
        } else if (isIsQuoteLikeOperator(operator)) {
            operatorEnabled = true;
        } else if (!nextTokenText.equals("::")) {
            // Check if the operator is enabled in the current scope
            // Some operators require specific features to be enabled
            operatorEnabled = switch (operator) {
                case "all" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("keyword_all");
                case "any" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("keyword_any");
                case "say", "fc", "state", "evalbytes" -> parser.ctx.symbolTable.isFeatureCategoryEnabled(operator);
                case "__SUB__" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("current_sub");
                case "__CLASS__" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("class");
                case "method" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("class");
                default -> true; // Most operators are always enabled
            };
        }

        // Check for overridable operators (unless explicitly called with CORE::)
        if (!calledWithCore && operatorEnabled && ParserTables.OVERRIDABLE_OP.contains(operator)) {
            // Core functions can be overridden in two ways:
            // 1. By defining a subroutine in the current package
            // 2. By defining a subroutine in CORE::GLOBAL::

            // Special case: 'do' followed by '{' is a do-block, not a function call
            if (operator.equals("do") && peekTokenText.equals("{")) {
                // This is a do block, not a do function call - let CoreOperatorResolver handle it
            } else {

                // Check for local package override
                String fullName = parser.ctx.symbolTable.getCurrentPackage() + "::" + operator;
                if (GlobalVariable.isSubs.getOrDefault(fullName, false)) {
                    // Example: 'use subs "hex"; sub hex { 456 } print hex("123"), "\n"'
                    parser.tokenIndex = startIndex;   // backtrack to reparse as subroutine
                    return SubroutineParser.parseSubroutineCall(parser, false);
                }

                // Check for CORE::GLOBAL:: override
                String coreGlobalName = "CORE::GLOBAL::" + operator;
                if (RuntimeGlob.isGlobAssigned(coreGlobalName) && existsGlobalCodeRef(coreGlobalName)) {
                    // Example: 'BEGIN { *CORE::GLOBAL::hex = sub { 456 } } print hex("123"), "\n"'
                    parser.tokenIndex = startIndex;   // backtrack
                    // Rewrite the tokens to call CORE::GLOBAL::operator
                    parser.tokens.add(startIndex, new LexerToken(LexerTokenType.IDENTIFIER, "CORE"));
                    parser.tokens.add(startIndex + 1, new LexerToken(LexerTokenType.OPERATOR, "::"));
                    parser.tokens.add(startIndex + 2, new LexerToken(LexerTokenType.IDENTIFIER, "GLOBAL"));
                    parser.tokens.add(startIndex + 3, new LexerToken(LexerTokenType.OPERATOR, "::"));
                    return SubroutineParser.parseSubroutineCall(parser, false);
                }
            }
        }

        // Try to parse as a core operator/keyword
        if (operatorEnabled) {
            Node operation = CoreOperatorResolver.parseCoreOperator(parser, token, startIndex);
            if (operation != null) {
                return operation;
            }
        }

        // If CORE:: was used but the operator wasn't recognized, it's an error
        if (calledWithCore) {
            throw new PerlCompilerException(parser.tokenIndex,
                    "CORE::" + operator + " is not a keyword", parser.ctx.errorUtil);
        }

        // Default: treat as a subroutine call or bareword
        parser.tokenIndex = startIndex;   // backtrack
        return SubroutineParser.parseSubroutineCall(parser, false);
    }

    static boolean isIsQuoteLikeOperator(String operator) {
        return operator.equals("q") || operator.equals("qq") ||
                operator.equals("qw") || operator.equals("qx") ||
                operator.equals("m") || operator.equals("s") ||
                operator.equals("tr") || operator.equals("y");
    }

    /**
     * Parses operator tokens and special constructs.
     *
     * <p>This method handles a wide variety of operators and special constructs:
     * <ul>
     *   <li>Grouping operators: (), [], {}</li>
     *   <li>Quote operators: ', ", /, `, etc.</li>
     *   <li>Reference operator: \</li>
     *   <li>Sigils: $, @, %, *, &</li>
     *   <li>Unary operators: !, +, -, ~, ++, --</li>
     *   <li>File test operators: -f, -d, etc.</li>
     *   <li>Special constructs: <<heredoc, diamond operator <></li>
     * </ul>
     *
     * @param parser   The parser instance
     * @param token    The operator token being parsed
     * @param operator The operator text (same as token.text)
     * @return A Node representing the parsed operator construct
     * @throws PerlCompilerException if the operator is not recognized or used incorrectly
     */
    static Node parseOperator(Parser parser, LexerToken token, String operator) {
        // Check for autoquoting: keyword operators before => should be treated as barewords
        // This handles cases like: and => {...}, or => {...}, xor => {...}
        if (operator.equals("and") || operator.equals("or") || operator.equals("xor")) {
            String peekTokenText = peek(parser).text;
            if (peekTokenText.equals("=>")) {
                // Autoquote: convert operator keyword to string literal
                return new StringNode(token.text, parser.tokenIndex);
            }
        }
        
        Node operand = null;
        switch (token.text) {
            case "(":
                // Parentheses create a list context and group expressions
                return new ListNode(ListParser.parseList(parser, ")", 0), parser.tokenIndex);

            case "{":
                // Curly braces create anonymous hash references
                return new HashLiteralNode(ListParser.parseList(parser, "}", 0), parser.tokenIndex);

            case "[":
                // Square brackets create anonymous array references
                return new ArrayLiteralNode(ListParser.parseList(parser, "]", 0), parser.tokenIndex);

            case ".":
                // Dot at the beginning of a primary expression is a fractional number (.5)
                return NumberParser.parseFractionalNumber(parser);

            case "<", "<<":
                // Diamond operator <> or heredoc <<EOF
                return OperatorParser.parseDiamondOperator(parser, token);

            case "'", "\"", "/", "//", "/=", "`":
                // Quote-like operators for strings, regexes, and command execution
                return StringParser.parseRawString(parser, token.text);

            case "::":
                // Leading :: means main:: (e.g., ::foo is main::foo)
                // This allows accessing global variables even when a lexical exists
                LexerToken nextToken2 = peek(parser);
                if (nextToken2.type == LexerTokenType.IDENTIFIER) {
                    // Insert "main" before the :: to create main::identifier
                    parser.tokens.add(parser.tokenIndex - 1, new LexerToken(LexerTokenType.IDENTIFIER, "main"));
                    parser.tokenIndex--; // Go back to process "main"
                    return parseIdentifier(parser, parser.tokenIndex,
                            new LexerToken(LexerTokenType.IDENTIFIER, "main"), "main");
                }
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);

            case "\\":
                // Reference operator: \$var, \@array, \%hash, \&sub
                // Set flag to prevent &sub from being called during parsing
                parser.parsingTakeReference = true;
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                parser.parsingTakeReference = false;

                // Special case for \&{CORE::push} take a reference to an operator
                if (operand instanceof OperatorNode operatorNode && operatorNode.operator.equals("&")) {
                    if (operatorNode.operand instanceof IdentifierNode identifierNode && identifierNode.name.startsWith("CORE::")) {
                        // TODO implement take reference to operator
                        //
                        // ./jperl -e '  BEGIN { *shove = sub (\@@) { CORE::push(@{$_[0]}, @_[1..$#_]) } } shove @array, 1,2,3; print "[@array]\n" '
                        //        [1 2 3]
                        //
                        throw new PerlCompilerException("Not implemented: take reference of operator `\\&" + identifierNode.name + "`");
                    }
                }

                return new OperatorNode(token.text, operand, parser.tokenIndex);

            case "$", "$#", "@", "%", "*":
                // Variable sigils: $scalar, @array, %hash, *glob, $#array
                return Variable.parseVariable(parser, token.text);

            case "&":
                // Code sigil: &subroutine
                return Variable.parseCoderefVariable(parser, token);

            case "!", "+":
                // Simple unary operators
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                if (operand == null) {
                    parser.throwError("syntax error");
                }
                return new OperatorNode(token.text, operand, parser.tokenIndex);

            case "~", "~.":
                // Bitwise complement operators
                // ~ is string bitwise complement by default, binary~ with 'use feature "bitwise"'
                // ~. is always numeric bitwise complement
                if (parser.ctx.symbolTable.isFeatureCategoryEnabled("bitwise")) {
                    if (operator.equals("~")) {
                        operator = "binary" + operator; // Mark as binary bitwise
                    }
                } else {
                    if (operator.equals("~.")) {
                        // ~. requires bitwise feature
                        throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                    }
                }
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                return new OperatorNode(operator, operand, parser.tokenIndex);

            case "~~":
                // Handle ~~ as two separate ~ operators
                // First, handle it as a single ~ operator
                String firstOperator = "~";
                if (parser.ctx.symbolTable.isFeatureCategoryEnabled("bitwise")) {
                    firstOperator = "binary~";
                }

                // Put back a single ~ token for the next parse
                parser.tokenIndex--; // Back up
                LexerToken currentToken = parser.tokens.get(parser.tokenIndex);
                currentToken.text = "~";

                // Parse the operand (which will start with the second ~)
                operand = parser.parseExpression(parser.getPrecedence("~") + 1);
                return new OperatorNode(firstOperator, operand, parser.tokenIndex);

            case "--":
            case "++":
                // Pre-increment/decrement operators
                operand = parser.parseExpression(parser.getPrecedence(token.text));
                return new OperatorNode(token.text, operand, parser.tokenIndex);

            case "-":
                // Unary minus or file test operator (-f, -d, etc.)
                LexerToken nextToken = parser.tokens.get(parser.tokenIndex);
                if (nextToken.type == LexerTokenType.IDENTIFIER && nextToken.text.length() == 1) {
                    // Check if this is a valid file test operator
                    String testOp = nextToken.text;
                    if (isValidFileTestOperator(testOp)) {
                        return parseFileTestOperator(parser, nextToken, operand);
                    }
                }
                // Regular unary minus
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);

                // Check for missing operand - this is a syntax error
                if (operand == null) {
                    parser.throwError("Syntax error: unary minus operator requires an operand");
                }

                if (operand instanceof IdentifierNode identifierNode) {
                    // Special case: -bareword becomes "-bareword" (string)
                    return new StringNode("-" + identifierNode.name, parser.tokenIndex);
                }
                return new OperatorNode("unaryMinus", operand, parser.tokenIndex);

            case "*=":
                // Special variable glob "="
                return new OperatorNode("*", new IdentifierNode("=", parser.tokenIndex), parser.tokenIndex);

            default:
                // Unknown operator
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
        }
    }

    private static Node parseFileTestOperator(Parser parser, LexerToken nextToken, Node operand) {
        String operator;
        // File test operator: -f filename, -d $dir, etc.
        operator = "-" + nextToken.text;
        int startLineNumber = parser.ctx.errorUtil.getLineNumber(parser.tokenIndex - 1); // Save line number before peek() side effects
        parser.tokenIndex++;
        nextToken = peek(parser);

        if (nextToken.text.equals("=>")) {
            // autoquote ` -X => ... `
            return new StringNode(operator, parser.tokenIndex);
        }


        var hasParenthesis = false;
        if (nextToken.text.equals("(")) {
            hasParenthesis = true;
            TokenUtils.consume(parser);
            nextToken = peek(parser);
        }

        if (nextToken.text.equals("_")) {
            // Special case: -f _ uses the stat buffer from the last file test
            TokenUtils.consume(parser);
            operand = new IdentifierNode("_", parser.tokenIndex);
        } else {
            // Parse the filename/handle argument
            ListNode listNode = ListParser.parseZeroOrOneList(parser, 0);
            if (listNode.elements.isEmpty()) {
                // No argument provided, use $_ as default
                operand = scalarUnderscore(parser);
            } else if (listNode.elements.size() == 1) {
                operand = listNode.elements.getFirst();
            } else {
                parser.throwError("syntax error");
            }
        }

        if (hasParenthesis) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        // Check for ambiguous cases like -C- where we have a unary minus without operand
        if (operand instanceof org.perlonjava.astnode.OperatorNode opNode &&
                "unaryMinus".equals(opNode.operator) && opNode.operand == null) {
            // This is an ambiguous case like -C- 
            // Use the saved line number from before peek() side effects
            String warningMsg = "Warning: Use of \"" + operator + "-\" without parentheses is ambiguous at " +
                    parser.ctx.errorUtil.getFileName() + " line " + startLineNumber + ".\n" +
                    "syntax error at " + parser.ctx.errorUtil.getFileName() + " line " + startLineNumber + ", at EOF\n" +
                    "Execution of " + parser.ctx.errorUtil.getFileName() + " aborted due to compilation errors.";
            throw new org.perlonjava.runtime.PerlParserException(warningMsg);
        }

        return new OperatorNode(operator, operand, parser.tokenIndex);
    }

    /**
     * Checks if a single character represents a valid file test operator.
     * Valid operators: r w x o R W X O e z s f d l p S b c t u g k T B M A C
     * Note: 'a' is NOT a valid file test operator (it's used for the -a command line switch)
     */
    private static boolean isValidFileTestOperator(String op) {
        return op.length() == 1 && "rwxoRWXOezsfdlpSbctugkTBMAC".indexOf(op.charAt(0)) >= 0;
    }
}
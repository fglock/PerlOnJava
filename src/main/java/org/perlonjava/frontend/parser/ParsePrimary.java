package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.frontend.parser.ParserNodeUtils.scalarUnderscore;
import static org.perlonjava.frontend.parser.TokenUtils.peek;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.existsGlobalCodeRef;

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

        // Check if this is an explicit CORE:: call (e.g., CORE::print)
        // IMPORTANT: Check this BEFORE lexical subs, because "state sub CORE" shouldn't break CORE::uc
        if (token.text.equals("CORE") && nextTokenText.equals("::")) {
            calledWithCore = true;
            operatorEnabled = true; // CORE:: functions are always enabled
            TokenUtils.consume(parser);  // consume "::"
            token = TokenUtils.consume(parser); // consume the actual operator
            operator = token.text;
        }

        // IMPORTANT: Check for lexical subs AFTER CORE::, but before checking for quote-like operators!
        // This allows "my sub y" to shadow the "y///" transliteration operator
        // But doesn't interfere with CORE:: prefix handling
        // ALSO: Don't treat as lexical sub if :: follows - that's a qualified name like Encode::is_utf8
        if (!calledWithCore && !nextTokenText.equals("::")) {
            String lexicalKey = "&" + operator;
            SymbolTable.SymbolEntry lexicalEntry = parser.ctx.symbolTable.getSymbolEntry(lexicalKey);
            if (lexicalEntry != null && lexicalEntry.ast() instanceof OperatorNode) {
                // This is a lexical sub - parse it as a subroutine call
                parser.tokenIndex = startIndex;   // backtrack
                return SubroutineParser.parseSubroutineCall(parser, false);
            }
        }

        // Check for quote-like operators that should always be parsed as operators

        if (isIsQuoteLikeOperator(operator)) {
            operatorEnabled = true;
        } else if (!nextTokenText.equals("::")) {
            // Check if the operator is enabled in the current scope
            // Some operators require specific features to be enabled
            operatorEnabled = switch (operator) {
                case "all" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("keyword_all");
                case "any" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("keyword_any");
                case "say", "fc", "state", "evalbytes", "isa" -> parser.ctx.symbolTable.isFeatureCategoryEnabled(operator);
                case "__SUB__" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("current_sub");
                case "__CLASS__" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("class");
                case "method" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("class");
                case "try", "catch" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("try");
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
                if (GlobalVariable.getIsSubsMap().getOrDefault(fullName, false) || GlobalVariable.isGlobalCodeRefDefined(fullName)) {
                    // Example: 'use subs "hex"; sub hex { 456 } print hex("123"), "\n"'
                    // Or: 'use Time::HiRes "time"; print time, "\n"' (sub imported at BEGIN time)
                    parser.tokenIndex = startIndex;   // backtrack to reparse as subroutine
                    return SubroutineParser.parseSubroutineCall(parser, false);
                }

                // Check for CORE::GLOBAL:: override
                String coreGlobalName = "CORE::GLOBAL::" + operator;
                if (RuntimeGlob.isGlobAssigned(coreGlobalName) && existsGlobalCodeRef(coreGlobalName)) {
                    // Example: 'BEGIN { *CORE::GLOBAL::hex = sub { 456 } } print hex("123"), "\n"'
                    
                    // Special handling for 'require' - need to convert bareword module name to string
                    // before passing to the override subroutine, to avoid strict subs violation
                    if (operator.equals("require")) {
                        // Parse the require argument using standard require handling
                        Node requireNode = CoreOperatorResolver.parseCoreOperator(parser, token, startIndex);
                        if (requireNode instanceof OperatorNode requireOp && requireOp.operator.equals("require")) {
                            // Convert to CORE::GLOBAL::require subroutine call with the parsed argument
                            // Use &CORE::GLOBAL::require(...) form to properly call as code ref
                            OperatorNode codeRef = new OperatorNode("&",
                                    new IdentifierNode(coreGlobalName, startIndex),
                                    startIndex);
                            // Defensive: ensure operand is a ListNode
                            ListNode operandList = (requireOp.operand instanceof ListNode)
                                    ? (ListNode) requireOp.operand
                                    : ListNode.makeList(requireOp.operand);
                            return new BinaryOperatorNode("(",
                                    codeRef,
                                    operandList,
                                    startIndex);
                        }
                    }
                    
                    // Skip whitespace to find the actual position of the operator token.
                    // startIndex may point to a whitespace token before the operator,
                    // and inserting CORE::GLOBAL:: before whitespace would leave the
                    // whitespace between GLOBAL:: and the operator, causing a parse error.
                    int insertPos = Whitespace.skipWhitespace(parser, startIndex, parser.tokens);
                    parser.tokenIndex = insertPos;   // backtrack to operator position
                    // Rewrite the tokens to call CORE::GLOBAL::operator
                    parser.tokens.add(insertPos, new LexerToken(LexerTokenType.IDENTIFIER, "CORE"));
                    parser.tokens.add(insertPos + 1, new LexerToken(LexerTokenType.OPERATOR, "::"));
                    parser.tokens.add(insertPos + 2, new LexerToken(LexerTokenType.IDENTIFIER, "GLOBAL"));
                    parser.tokens.add(insertPos + 3, new LexerToken(LexerTokenType.OPERATOR, "::"));
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
                operator.equals("qr") ||
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
                // Leading :: can mean either:
                // 1. Function call: ::foo() -> main::foo()
                // 2. Bareword reference: ::foo without parens -> calls main::foo if it exists
                // 3. Bareword string: ::foo when no such sub exists -> '::foo'
                //
                // In Perl, ::foo without parens calls main::foo if the sub exists at compile time.
                // If no such sub exists, it becomes a bareword string '::foo'.
                LexerToken nextToken2 = peek(parser);
                if (nextToken2.type == LexerTokenType.IDENTIFIER) {
                    String identifierName = nextToken2.text;
                    // Look ahead to see if this is a function call with parens
                    int lookAhead = parser.tokenIndex + 1;
                    // Skip whitespace
                    while (lookAhead < parser.tokens.size() && 
                           parser.tokens.get(lookAhead).type == LexerTokenType.WHITESPACE) {
                        lookAhead++;
                    }
                    String afterIdentifier = lookAhead < parser.tokens.size() ? 
                                            parser.tokens.get(lookAhead).text : "";
                    
                    // Check if the sub exists at compile time
                    String fullSubName = "main::" + identifierName;
                    boolean subExists = GlobalVariable.getGlobalCodeRef(fullSubName).getDefinedBoolean();
                    
                    if (afterIdentifier.equals("(") || subExists) {
                        // Function call: ::foo() or ::foo (when sub exists)
                        // Insert "main" before the :: to create main::identifier
                        parser.tokens.add(parser.tokenIndex - 1, new LexerToken(LexerTokenType.IDENTIFIER, "main"));
                        parser.tokenIndex--; // Go back to process "main"
                        return parseIdentifier(parser, parser.tokenIndex,
                                new LexerToken(LexerTokenType.IDENTIFIER, "main"), "main");
                    } else {
                        // Bareword: ::foo -> '::foo' (no such sub exists)
                        parser.tokenIndex++; // Consume the identifier
                        return new StringNode("::" + identifierName, parser.tokenIndex);
                    }
                }
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);

            case "\\":
                // Reference operator: \$var, \@array, \%hash, \&sub
                // Set flag to prevent &sub from being called during parsing
                parser.parsingTakeReference = true;
                operand = parser.parseExpression(parser.getPrecedence(token.text) + 1);
                parser.parsingTakeReference = false;

                // \&CORE::push etc. — the wrapper is generated lazily at runtime
                // by CoreSubroutineGenerator (called from RuntimeCode.createCodeReference)

                return new OperatorNode(token.text, operand, parser.tokenIndex);

            case "$*":
                parser.throwCleanError("$* is no longer supported as of Perl 5.30");

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
                    } else {
                        // Not a valid filetest operator
                        // Check if there's a function with this name
                        String functionName = nextToken.text;
                        String fullName = parser.ctx.symbolTable.getCurrentPackage() + "::" + functionName;
                        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(fullName);
                        if (codeRef.getDefinedBoolean()) {
                            // There's a function with this name, treat as regular unary minus
                            // Don't do anything special here, just fall through to regular unary minus handling
                        } else {
                            // Not a valid filetest operator and no function with this name
                            // Check what comes after to decide how to handle
                            // Skip whitespace to find the next meaningful token
                            int afterIndex = parser.tokenIndex + 1;
                            while (afterIndex < parser.tokens.size() &&
                                    parser.tokens.get(afterIndex).type == LexerTokenType.WHITESPACE) {
                                afterIndex++;
                            }
                            if (afterIndex < parser.tokens.size()) {
                                LexerToken afterNext = parser.tokens.get(afterIndex);
                                // If there's something after the identifier that looks like an operand,
                                // it's probably an attempt to use a filetest operator, so give an error
                                if (afterNext.type == LexerTokenType.NUMBER ||
                                        afterNext.type == LexerTokenType.STRING ||
                                        afterNext.type == LexerTokenType.IDENTIFIER ||
                                        afterNext.text.equals("$") || afterNext.text.equals("@")) {
                                    parser.throwError("syntax error - Invalid filetest operator near \"" + testOp + " 1\"");
                                }
                            }
                            // Otherwise, fall through to regular unary minus handling (will treat as string)
                            // This handles cases like -a in strict mode where it should be "-a"
                        }
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

        // File tests accept bareword filehandles; parse them before generic expression parsing
        // can turn them into subroutine calls. But '_' is special: it refers to the last stat buffer.
        // Don't treat as filehandle if followed by :: (qualified package name like CPAN::find_perl)
        if (nextToken.type == LexerTokenType.IDENTIFIER) {
            String name = nextToken.text;
            LexerToken afterName = parser.tokens.size() > parser.tokenIndex + 1 
                ? parser.tokens.get(parser.tokenIndex + 1) : null;
            boolean isQualifiedName = afterName != null && afterName.text.equals("::");
            if (!isQualifiedName && !name.equals("_") && name.matches("^[A-Z_][A-Z0-9_]*$")) {
                TokenUtils.consume(parser);
                // autovivify filehandle and convert to globref
                GlobalVariable.getGlobalIO(FileHandle.normalizeBarewordHandle(parser, name));
                Node fh = FileHandle.parseBarewordHandle(parser, name);
                operand = fh != null ? fh : new IdentifierNode(name, parser.tokenIndex);
                if (hasParenthesis) {
                    TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
                }
                return new OperatorNode(operator, operand, parser.tokenIndex);
            }
        }

        if (nextToken.text.equals("_")) {
            // Special case: -f _ uses the stat buffer from the last file test
            TokenUtils.consume(parser);
            operand = new IdentifierNode("_", parser.tokenIndex);
        } else if (hasParenthesis) {
            // Inside parentheses, parse full expression (allows assignment like -f ($x = $path))
            // But first check for empty parens -f()
            if (nextToken.text.equals(")")) {
                // Empty parentheses -f() uses $_ as default
                operand = scalarUnderscore(parser);
            } else {
                operand = parser.parseExpression(0);
                if (operand == null) {
                    // No argument provided, use $_ as default
                    operand = scalarUnderscore(parser);
                }
            }
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
        if (operand instanceof OperatorNode opNode &&
                "unaryMinus".equals(opNode.operator) && opNode.operand == null) {
            // This is an ambiguous case like -C- 
            // Use the saved line number from before peek() side effects
            String warningMsg = "Warning: Use of \"" + operator + "-\" without parentheses is ambiguous at " +
                    parser.ctx.errorUtil.getFileName() + " line " + startLineNumber + ".\n" +
                    "syntax error at " + parser.ctx.errorUtil.getFileName() + " line " + startLineNumber + ", at EOF\n" +
                    "Execution of " + parser.ctx.errorUtil.getFileName() + " aborted due to compilation errors.";
            throw new PerlParserException(warningMsg);
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
package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.operators.WarnDie;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.perlmodule.Strict;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParsePrimary.parsePrimary;
import static org.perlonjava.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.parser.TokenUtils.peek;

public class Variable {
    
    /**
     * Check if a field exists in the current class or any parent class.
     * This method walks the inheritance hierarchy looking for field declarations.
     * 
     * @param parser The parser instance
     * @param fieldName The name of the field to check (without sigil)
     * @return true if the field exists in the class hierarchy
     */
    private static boolean isFieldInClassHierarchy(Parser parser, String fieldName) {
        // Get the current package/class name
        String currentClass = parser.ctx.symbolTable.getCurrentPackage();
        
        // Check field in current class
        String fieldSymbol = "field:" + fieldName;
        if (parser.ctx.symbolTable.getVariableIndex(fieldSymbol) != -1) {
            return true;
        }
        
        // For inherited fields, we need to check if we're in a class context
        // and if the parser is currently inside a class block
        // Since we're at parse time, we can't access runtime @ISA arrays
        // Instead, we need to check if the field was registered during parent class parsing
        
        // Check if we're in a class (not just a regular package)
        if (!parser.ctx.symbolTable.currentPackageIsClass()) {
            return false;
        }
        
        // At parse time, we can't easily access parent class fields because
        // @ISA is populated at runtime. However, we can check if there's a
        // parent class annotation stored during :isa() parsing
        
        // For now, return false for inherited fields
        // A complete solution would require tracking parent class fields
        // at parse time or deferring field resolution to runtime
        
        return false;
    }
    /**
     * Parses a variable from the given lexer token.
     *
     * @param parser the parser instance
     * @param sigil  The sigil that starts the variable.
     * @return The parsed variable node.
     * @throws PerlCompilerException If there is a syntax error.
     */
    public static Node parseVariable(Parser parser, String sigil) {
        Node operand;
        var nextToken = peek(parser);

        // Special handling for $ followed by {
        if (nextToken.text.equals("$")) {
            // Check if we have ${...} pattern
            if (parser.tokens.get(parser.tokenIndex + 1).text.equals("{")) {
                // This is ${...}, parse as dereference of ${...}
                // Don't consume the $ token, let it be parsed as part of the variable
                operand = parser.parseExpression(parser.getPrecedence("$") + 1);
                return new OperatorNode(sigil, operand, parser.tokenIndex);
            }
        }

        // Special handling for $#[...]
        if (sigil.equals("$#") && nextToken.text.equals("[")) {
            // This is $#[...] which is mentioned in t/base/lex.t and it returns an empty string
            parsePrimary(parser);
            return new StringNode("", parser.tokenIndex);
        }

        // Store the current position before parsing the identifier
        int startIndex = parser.tokenIndex;

        String varName = IdentifierParser.parseComplexIdentifier(parser);
        parser.ctx.logDebug("Parsing variable: " + varName);

        if (varName != null) {
            IdentifierParser.validateIdentifier(parser, varName, startIndex);

            // Variable name is valid.
            // Check for illegal characters after a variable
            if (!parser.parsingForLoopVariable && peek(parser).text.equals("(") && !sigil.equals("&")) {
                // Parentheses are only allowed after a variable in specific cases:
                // - `for my $v (...`
                // - `&name(...`
                // - `obj->$name(...`
                parser.throwError("syntax error");
            }

            if (sigil.equals("*")) {
                // Vivify the GLOB if it doesn't exist yet
                // This helps distinguish between file handles and other barewords
                String fullName = NameNormalizer.normalizeVariableName(varName, parser.ctx.symbolTable.getCurrentPackage());
                GlobalVariable.getGlobalIO(fullName);
            }

            // Check if we're in a method and this variable is a field
            // Only transform to $self->{field} if:
            // 1. We're inside a method (TODO: need to track method context)
            // 2. The field exists in the current class or parent classes
            // 3. The variable is not locally shadowed
            String localVar = sigil + varName;
            
            // Check if this is a field (in current or parent class) and not a locally declared variable
            if (isFieldInClassHierarchy(parser, varName) 
                && parser.ctx.symbolTable.getVariableIndexInCurrentScope(localVar) == -1) {
                // This is a field and not shadowed by a local variable
                // Transform to $self->{fieldname}
                
                // Create $self
                OperatorNode selfVar = new OperatorNode("$", 
                    new IdentifierNode("self", parser.tokenIndex), parser.tokenIndex);
                
                // Create hash subscript for field access
                List<Node> keyList = new ArrayList<>();
                keyList.add(new IdentifierNode(varName, parser.tokenIndex));
                HashLiteralNode hashSubscript = new HashLiteralNode(keyList, parser.tokenIndex);
                
                // Return $self->{fieldname}
                return new BinaryOperatorNode("->", selfVar, hashSubscript, parser.tokenIndex);
            }
            
            // Create a normal Variable node
            return new OperatorNode(sigil, new IdentifierNode(varName, parser.tokenIndex), parser.tokenIndex);
        } else if (peek(parser).text.equals("{")) {
            // Handle curly brackets - use parseBracedVariable instead of parseBlock
            return parseBracedVariable(parser, sigil, false);
        }

        // Not a variable name, not a block. This could be a dereference like @$a
        // Parse the expression with the appropriate precedence
        operand = parser.parseExpression(parser.getPrecedence("$") + 1);
        return new OperatorNode(sigil, operand, parser.tokenIndex);
    }

    /**
     * Parses array and hash access operations within braces (for ${...} constructs).
     * This is similar to parseArrayHashAccess but stops at the closing brace.
     *
     * @param parser  the parser instance
     * @param operand the base variable node to which access operations are applied
     * @param isRegex whether this is in a regex context (affects bracket handling)
     * @return the modified operand with access operations applied
     */
    static Node parseArrayHashAccessInBraces(Parser parser, Node operand, boolean isRegex) {
        while (true) {
            // Skip whitespace, comments, etc. inside braces
            parser.tokenIndex = Whitespace.skipWhitespace(parser, parser.tokenIndex, parser.tokens);

            if (parser.tokenIndex >= parser.tokens.size()) {
                break;
            }

            var token = parser.tokens.get(parser.tokenIndex);
            if (token.text.equals("}")) {
                // Hit the closing brace, stop parsing
                break;
            }

            var text = token.text;
            try {
                switch (text) {
                    case "[" -> {
                        if (isRegex) {
                            // In regex context, '[' might be a character class
                            // Need sophisticated lookahead to distinguish:
                            // $foo[$A] - array subscript (should interpolate)
                            // $foo[$A-Z] - character class (should NOT interpolate)
                            if (!isArraySubscriptInRegex(parser, parser.tokenIndex)) {
                                return operand; // Stop parsing, let caller handle as character class
                            }
                        }
                        operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                        if (operand == null) {
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error: Missing closing bracket", parser.ctx.errorUtil);
                        }
                        parser.ctx.logDebug("str operand " + operand);
                    }
                    case "{" -> {
                        // Hash access
                        operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                        if (operand == null) {
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error: Missing closing brace", parser.ctx.errorUtil);
                        }
                        parser.ctx.logDebug("str operand " + operand);
                    }
                    case "->" -> {
                        // Method call or dereference
                        var previousIndex = parser.tokenIndex;
                        parser.tokenIndex++;
                        if (parser.tokenIndex < parser.tokens.size()) {
                            text = parser.tokens.get(parser.tokenIndex).text;
                            switch (text) {
                                case "[", "{" -> {
                                    // Dereference followed by access: $var->[0] or $var->{key}
                                    parser.tokenIndex = previousIndex;  // Re-parse "->"
                                    operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                                    if (operand == null) {
                                        throw new PerlCompilerException(parser.tokenIndex, "syntax error: Unterminated dereference", parser.ctx.errorUtil);
                                    }
                                    parser.ctx.logDebug("str operand " + operand);
                                }
                                default -> {
                                    // Not a dereference we can handle
                                    parser.tokenIndex = previousIndex;
                                    return operand; // Stop parsing
                                }
                            }
                        } else {
                            parser.tokenIndex = previousIndex;
                            return operand;
                        }
                    }
                    default -> {
                        // No more access operations we recognize
                        return operand;
                    }
                }
            } catch (Exception e) {
                // If parsing fails, throw a more informative error
                throw new PerlCompilerException(parser.tokenIndex, "syntax error: Unterminated array or hash access", parser.ctx.errorUtil);
            }
        }
        return operand;
    }

    /**
     * Parses array and hash access operations following a variable.
     *
     * @param parser  the parser instance
     * @param operand the base variable node to which access operations are applied
     * @param isRegex whether this is in a regex context (affects bracket handling)
     * @return the modified operand with access operations applied
     */
    static Node parseArrayHashAccess(Parser parser, Node operand, boolean isRegex) {
        outerLoop:
        while (true) {
            if (parser.tokenIndex >= parser.tokens.size()) {
                break;
            }

            var token = parser.tokens.get(parser.tokenIndex);
            if (token.type == LexerTokenType.EOF) {
                break;
            }

            var text = token.text;
            try {
                switch (text) {
                    case "[" -> {
                        if (isRegex) {
                            // In regex context, '[' might be a character class
                            // Critical distinction:
                            // 1. Scalar variables: $foo[$A-Z] -> character class (should NOT interpolate)
                            // 2. Array variables: $X[-1] -> array element (should interpolate)
                            
                            // Enhanced parsing logic that considers strict mode context
                            // Key insight: Strict mode affects how $foo[...] is parsed
                            //
                            // In NON-STRICT mode (like t/base/lex.t):
                            //   - $foo[$A-Z] works as character class (barewords allowed)
                            //   - $X[-1] works as array element access
                            //
                            // In STRICT mode:
                            //   - $foo[$A-Z] may cause parsing issues (barewords not allowed)
                            //   - Need more careful disambiguation
                            
                            boolean shouldTreatAsCharacterClass = false;
                            
                            if (operand instanceof OperatorNode opNode && "$".equals(opNode.operator)) {
                                // This is a scalar variable access like $foo or $X
                                // Use enhanced logic to distinguish array subscripts from character classes
                                if (!isArraySubscriptInRegex(parser, parser.tokenIndex)) {
                                    shouldTreatAsCharacterClass = true;
                                }
                            }
                            
                            if (shouldTreatAsCharacterClass) {
                                // This is a character class pattern
                                break outerLoop; // Stop parsing, let caller handle as character class
                            }
                        }

                        // Check for malformed array access (unclosed bracket)
                        int savedIndex = parser.tokenIndex;
                        Node result = null;
                        try {
                            result = ParseInfix.parseInfixOperation(parser, operand, 0);
                        } catch (Exception e) {
                            parser.tokenIndex = savedIndex;
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error: Unterminated array access", parser.ctx.errorUtil);
                        }

                        if (result == null) {
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error: Missing closing bracket", parser.ctx.errorUtil);
                        }
                        operand = result;
                        parser.ctx.logDebug("str operand " + operand);
                    }
                    case "{" -> {
                        // Hash access
                        int savedIndex = parser.tokenIndex;
                        Node result = null;
                        try {
                            result = ParseInfix.parseInfixOperation(parser, operand, 0);
                        } catch (Exception e) {
                            parser.tokenIndex = savedIndex;
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error: Unterminated hash access", parser.ctx.errorUtil);
                        }

                        if (result == null) {
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error: Missing closing brace", parser.ctx.errorUtil);
                        }
                        operand = result;
                        parser.ctx.logDebug("str operand " + operand);
                    }
                    case "->" -> {
                        // Method call or dereference
                        var previousIndex = parser.tokenIndex;
                        parser.tokenIndex++;
                        if (parser.tokenIndex < parser.tokens.size()) {
                            text = parser.tokens.get(parser.tokenIndex).text;
                            switch (text) {
                                case "[", "{" -> {
                                    // Dereference followed by access: $var->[0] or $var->{key}
                                    parser.tokenIndex = previousIndex;  // Re-parse "->"
                                    Node result = ParseInfix.parseInfixOperation(parser, operand, 0);
                                    if (result == null) {
                                        throw new PerlCompilerException(parser.tokenIndex, "syntax error: Unterminated dereference", parser.ctx.errorUtil);
                                    }
                                    operand = result;
                                    parser.ctx.logDebug("str operand " + operand);
                                }
                                default -> {
                                    // Not a dereference we can handle
                                    parser.tokenIndex = previousIndex;
                                    break outerLoop;
                                }
                            }
                        } else {
                            parser.tokenIndex = previousIndex;
                            break outerLoop;
                        }
                    }
                    default -> {
                        // No more access operations
                        break outerLoop;
                    }
                }
            } catch (PerlCompilerException e) {
                // Re-throw PerlCompilerExceptions as-is
                throw e;
            } catch (Exception e) {
                // Convert other exceptions to PerlCompilerException
                throw new PerlCompilerException(parser.tokenIndex, "syntax error: " + e.getMessage(), parser.ctx.errorUtil);
            }
        }
        return operand;
    }

    /**
     * Parses a code reference variable, handling Perl's `&` code reference parsing rules.
     * This method is responsible for parsing expressions that start with `&`, which in Perl
     * can be used to refer to subroutines or to call them.
     *
     * @param parser the parser instance
     * @param token  The lexer token representing the `&` operator.
     * @return A Node representing the parsed code reference or subroutine call.
     */
    static Node parseCoderefVariable(Parser parser, LexerToken token) {
        int index = parser.tokenIndex;

        // Special case: detect &{sub ...} and parse as code block
        LexerToken nextToken = TokenUtils.peek(parser);
        if (nextToken.text.equals("{")) {
            // Look ahead to see if there's 'sub' after the brace (skipping whitespace)
            int lookAheadIndex = parser.tokenIndex + 1;
            while (lookAheadIndex < parser.tokens.size()) {
                LexerToken afterBrace = parser.tokens.get(lookAheadIndex);
                if (afterBrace.type == LexerTokenType.WHITESPACE) {
                    lookAheadIndex++;
                    continue;
                }
                if (afterBrace.type == LexerTokenType.IDENTIFIER && afterBrace.text.equals("sub")) {
                    // This is &{sub ...} - parse as code block
                    TokenUtils.consume(parser); // consume '{'
                    Node block = ParseBlock.parseBlock(parser);
                    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                    return new OperatorNode("&", block, index);
                }
                break; // Not whitespace and not 'sub', so exit
            }
        }

        // Set a flag to allow parentheses after a variable, as in &$sub(...)
        parser.parsingForLoopVariable = true;
        // Parse the variable following the `&` sigil
        Node node = parseVariable(parser, token.text);
        // Reset the flag after parsing
        parser.parsingForLoopVariable = false;

        // If we are parsing a reference (e.g., \&sub), return the node without adding parameters
        if (parser.parsingTakeReference) {
            return node;
        }

        parser.ctx.logDebug("parse & node: " + node);

        // Check if the node is an OperatorNode with a BinaryOperatorNode operand
        if (node instanceof OperatorNode operatorNode) {
            if (operatorNode.operand instanceof IdentifierNode identifierNode
                    && identifierNode.name.equals("CORE::__SUB__")
                    && parser.ctx.symbolTable.isFeatureCategoryEnabled("current_sub")) {
                // &CORE::__SUB__
                return new OperatorNode("__SUB__", new ListNode(index), index);
            }

            if (operatorNode.operand instanceof BinaryOperatorNode binaryOperatorNode) {
                // If the operator is `(`, return the BinaryOperatorNode directly
                if (binaryOperatorNode.operator.equals("(")) {
                    return binaryOperatorNode;
                }
            }
        }

        Node list;
        // If the next token is not `(`, handle auto-call by transforming `&subr` to `&subr(@_)`
        if (!peek(parser).text.equals("(")) {
            list = atUnderscore(parser);
        } else {
            // Otherwise, parse the list of arguments
            list = ListParser.parseZeroOrMoreList(parser,
                    0,
                    false,
                    true,
                    false,
                    false);
        }

        // Handle cases where the node is an OperatorNode
        if (node instanceof OperatorNode operatorNode) {
            // If the operand is another OperatorNode, transform &$sub to $sub(@_)
            if (operatorNode.operand instanceof OperatorNode) {
                node = operatorNode.operand;
            } else if (operatorNode.operand instanceof BlockNode blockNode) {
                // If the operand is a BlockNode, transform &{$sub} to $sub(@_)
                node = blockNode;
            }
        }

        // Return a new BinaryOperatorNode representing the function call with arguments
        return new BinaryOperatorNode("(", node, list, parser.tokenIndex);
    }

    /**
     * Parses a braced variable expression like ${var} or ${expr}.
     * This method is shared between regular variable parsing and string interpolation.
     */
    public static Node parseBracedVariable(Parser parser, String sigil, boolean isStringInterpolation) {
        int startLineNumber = parser.ctx.errorUtil.getLineNumber(parser.tokenIndex - 1); // Save line number before peek() side effects
        TokenUtils.consume(parser); // Consume the '{'

        // Check if this is an empty ${} construct
        if (TokenUtils.peek(parser).text.equals("}")) {
            TokenUtils.consume(parser); // Consume the '}'
            return new OperatorNode(sigil, new StringNode("", parser.tokenIndex), parser.tokenIndex);
        }

        // For string interpolation, preprocess \" sequences IN PLACE
        if (isStringInterpolation) {
            int startIndex = parser.tokenIndex;
            int braceLevel = 1;

            while (braceLevel > 0 && parser.tokenIndex < parser.tokens.size()) {
                var token = parser.tokens.get(parser.tokenIndex);
                if (token.type == LexerTokenType.EOF) {
                    break;
                }

                if (token.text.equals("{")) {
                    braceLevel++;
                    parser.tokenIndex++;
                } else if (token.text.equals("}")) {
                    braceLevel--;
                    if (braceLevel == 0) {
                        break; // Don't consume the closing brace yet
                    }
                    parser.tokenIndex++;
                } else if (token.text.equals("\\") && parser.tokenIndex + 1 < parser.tokens.size()) {
                    var nextToken = parser.tokens.get(parser.tokenIndex + 1);
                    if (nextToken.text.equals("\"")) {
                        // Just remove the backslash - the quote token will slide down to current position
                        parser.tokens.remove(parser.tokenIndex);
                        // DON'T increment tokenIndex - the quote is now at the current position
                        // and we want to move past it in the next iteration
                    } else {
                        parser.tokenIndex++;
                    }
                } else {
                    parser.tokenIndex++;
                }
            }

            // Reset to start position and continue with original parsing logic
            parser.tokenIndex = startIndex;
        }

        // Continue with original parsing logic - this preserves context for special variables
        int savedIndex = parser.tokenIndex;

        // Check if this starts with ^ (control character variable)
        String bracedVarName = null;
        if (parser.tokens.get(parser.tokenIndex).text.equals("^")) {
            // Save position before trying to parse ^ variable
            int beforeCaret = parser.tokenIndex;
            parser.tokenIndex++; // consume ^

            // Now parse the identifier part after ^
            String identifier = IdentifierParser.parseComplexIdentifierInner(parser, false);
            if (identifier != null && !identifier.isEmpty()) {
                // Convert ^X to control character as parseComplexIdentifier would do
                char firstChar = identifier.charAt(0);
                String ctrlChar;
                if (firstChar >= 'A' && firstChar <= 'Z') {
                    ctrlChar = String.valueOf((char) (firstChar - 'A' + 1));
                } else if (firstChar >= 'a' && firstChar <= 'z') {
                    ctrlChar = String.valueOf((char) (firstChar - 'a' + 1));
                } else if (firstChar == '@') {
                    ctrlChar = String.valueOf((char) 0);
                } else if (firstChar >= '[' && firstChar <= '_') {
                    ctrlChar = String.valueOf((char) (firstChar - '[' + 27));
                } else if (firstChar == '?') {
                    ctrlChar = String.valueOf((char) 127);
                } else {
                    ctrlChar = String.valueOf(firstChar);
                }
                bracedVarName = ctrlChar + identifier.substring(1);
            } else {
                // Failed to parse identifier after ^, restore position
                parser.tokenIndex = beforeCaret;
            }
        }

        // If we didn't parse a ^ variable, try normal parsing
        if (bracedVarName == null) {
            bracedVarName = IdentifierParser.parseComplexIdentifierInner(parser, true);
        }

        if (bracedVarName != null) {
            // Check if this might be ambiguous with an operator
            boolean isAmbiguous = isAmbiguousOperatorName(bracedVarName);

            // Check if this is potentially an operator (s, m, q, etc.) followed by delimiter
            if (isMaybeOperator(bracedVarName, parser)) {
                // Reset and parse as expression
                parser.tokenIndex = savedIndex;
            } else {
                Node operand = new OperatorNode(sigil, new IdentifierNode(bracedVarName, parser.tokenIndex), parser.tokenIndex);

                try {
                    int beforeAccess = parser.tokenIndex;
                    operand = parseArrayHashAccessInBraces(parser, operand, isStringInterpolation);

                    // Check if we successfully parsed to the closing brace
                    if (TokenUtils.peek(parser).text.equals("}")) {
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

                        // Issue ambiguity warning if needed
                        if (isAmbiguous) {
                            String accessType = "";
                            if (operand instanceof BinaryOperatorNode binOp) {
                                if (binOp.operator.equals("[")) {
                                    accessType = "[...]";
                                } else if (binOp.operator.equals("{")) {
                                    accessType = "{...}";
                                }
                            }

                            if (accessType.isEmpty()) {
                                // Simple variable like ${s}
                                WarnDie.warn(
                                        new RuntimeScalar("Ambiguous use of ${" + bracedVarName + "} resolved to $" + bracedVarName),
                                        new RuntimeScalar(parser.ctx.errorUtil.errorMessage(parser.tokenIndex, "")),
                                        null, 0
                                );
                            } else {
                                // Array/hash access like ${tr[10]}
                                WarnDie.warn(
                                        new RuntimeScalar("Ambiguous use of ${" + bracedVarName + accessType + "} resolved to $" + bracedVarName + accessType),
                                        new RuntimeScalar(parser.ctx.errorUtil.errorMessage(parser.tokenIndex, "")),
                                        null, 0
                                );
                            }
                        }

                        return operand;
                    } else {
                        // We didn't reach the closing brace, which means there's more content
                        // that couldn't be parsed as array/hash access. This might be an operator.
                        parser.tokenIndex = savedIndex;
                    }
                } catch (Exception e) {
                    parser.tokenIndex = savedIndex;
                }
            }
        } else {
            parser.tokenIndex = savedIndex;
        }

        // Check for heredoc constructs like ${<<END} which should evaluate to empty string
        // The challenge is distinguishing ${<<END} from ${<...>} where $< is a special variable
        if (parser.tokenIndex < parser.tokens.size()) {
            var currentToken = parser.tokens.get(parser.tokenIndex);
            if (currentToken.text.equals("<")) {
                // Look ahead to see if this is <<IDENTIFIER (heredoc) vs <...> (angle brackets)
                if (parser.tokenIndex + 1 < parser.tokens.size()) {
                    var nextToken = parser.tokens.get(parser.tokenIndex + 1);
                    // If the next token after < is an identifier (not another <), this could be <<IDENTIFIER
                    // We need to check if this pattern matches heredoc syntax
                    if (nextToken.type == LexerTokenType.IDENTIFIER) {
                        // This looks like <<IDENTIFIER - treat as heredoc in ${<<END} context
                        
                        // Skip the < token
                        parser.tokenIndex++;
                        
                        // Get the identifier
                        String identifier = nextToken.text;
                        parser.tokenIndex++; // Skip identifier
                        
                        // Create a heredoc node and add it to the queue for later processing
                        OperatorNode heredocNode = new OperatorNode("HEREDOC", null, parser.tokenIndex);
                        heredocNode.setAnnotation("identifier", identifier);
                        heredocNode.setAnnotation("delimiter", "\""); // Default to double-quoted
                        parser.getHeredocNodes().add(heredocNode);
                        
                        // Consume the closing brace
                        if (!TokenUtils.peek(parser).text.equals("}")) {
                            throw new PerlCompilerException(parser.tokenIndex, "Missing closing brace in variable interpolation", parser.ctx.errorUtil);
                        }
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                        
                        // In ${<<END} context, this evaluates to empty string
                        return new OperatorNode(sigil, new StringNode("", parser.tokenIndex), parser.tokenIndex);
                    }
                }
            }
        }

// Fall back to parsing as a block
        try {
            BlockNode block = ParseBlock.parseBlock(parser);
            if (!TokenUtils.peek(parser).text.equals("}")) {
                throw new PerlCompilerException(parser.tokenIndex, "Missing closing brace in variable interpolation", parser.ctx.errorUtil);
            }
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            if (block.elements.size() == 1 && block.elements.getFirst() instanceof OperatorNode operatorNode && operatorNode.operator.equals("*")) {
                // ${*F} is a fancy way to say $Package::F
                if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                    identifierNode.name = NameNormalizer.normalizeVariableName(identifierNode.name, parser.ctx.symbolTable.getCurrentPackage());
                }
                return new OperatorNode(sigil, operatorNode.operand, parser.tokenIndex);
            }
            return new OperatorNode(sigil, block, parser.tokenIndex);
        } catch (Exception e) {
            // Use the saved line number from before peek() side effects
            String fileName = parser.ctx.errorUtil.getFileName();
            String multiLineError = "Missing right curly or square bracket at " + fileName + " line " + startLineNumber + ", at end of line\n" +
                                  "syntax error at " + fileName + " line " + startLineNumber + ", at EOF\n" +
                                  "Execution of " + fileName + " aborted due to compilation errors.";
            throw new org.perlonjava.runtime.PerlParserException(multiLineError);
        }
    }

    /**
     * Checks if the given identifier might be the start of an operator rather than a variable name.
     * This handles cases like ${s|||} where 's' could be either a variable or the substitution operator.
     */
    private static boolean isMaybeOperator(String identifier, Parser parser) {
        // Check if this could be a quote-like operator
        if (isAmbiguousOperatorName(identifier)) {
            // Look at what follows
            if (parser.tokenIndex < parser.tokens.size()) {
                var nextToken = parser.tokens.get(parser.tokenIndex);
                // If followed by a delimiter character (not alphanumeric, _, [, {, or }), it's likely an operator
                if (nextToken.text.length() == 1) {
                    char ch = nextToken.text.charAt(0);
                    // Exclude [ and { because those indicate array/hash access on a variable
                    return !Character.isLetterOrDigit(ch) && ch != '_' && ch != '}' && ch != '[' && ch != '{';
                }
            }
        }
        return false;
    }

    /**
     * Checks if an identifier name could be ambiguous with a Perl operator.
     */
    private static boolean isAmbiguousOperatorName(String identifier) {
        return "s".equals(identifier) || "m".equals(identifier) || "q".equals(identifier) ||
                "qx".equals(identifier) || "qr".equals(identifier) || "y".equals(identifier) ||
                "tr".equals(identifier) || "qq".equals(identifier) || "qw".equals(identifier);
    }

    /**
     * Determines if a '[' in regex context should be treated as an array subscript
     * rather than a character class by looking ahead for character class patterns.
     * 
     * @param parser the parser instance
     * @param bracketIndex the index of the '[' token
     * @return true if this should be treated as array subscript, false for character class
     */
    private static boolean isArraySubscriptInRegex(Parser parser, int bracketIndex) {
        // Look ahead to distinguish between:
        // $foo[$A] - array subscript (should interpolate)
        // $foo[$A-Z] - character class (should NOT interpolate)
        // $foo[0] - array subscript with number (should interpolate)
        // $foo[a-z] - character class (should NOT interpolate)
        
        int index = bracketIndex + 1; // Skip the '['
        if (index >= parser.tokens.size()) {
            return false; // Incomplete, treat as character class
        }
        
        var firstToken = parser.tokens.get(index);
        
        // Handle different token patterns:
        // Array access (should interpolate):
        //   [$A] -> tokens: $, A, ]
        //   [0] -> tokens: 0, ]  
        //   [-1] -> tokens: -, 1, ]
        // Character class (should NOT interpolate):
        //   [$A-Z] -> tokens: $, A, -, Z, ]
        //   [0-9] -> tokens: 0, -, 9, ]
        //   [a-z] -> tokens: a, -, z, ]
        
        if (firstToken.text.equals("$")) {
            // Variable case: $A, need to skip both $ and A tokens
            index++; // Skip the $ token
            if (index >= parser.tokens.size()) {
                return false; // Incomplete variable, treat as character class
            }
            var variableNameToken = parser.tokens.get(index);
            index++; // Skip the variable name token
        } else if (firstToken.type == LexerTokenType.NUMBER) {
            // Number case: just skip the number token
            index++;
        } else if (firstToken.text.equals("-")) {
            // Negative number case: skip the - and the following number
            index++; // Skip the - token
            if (index >= parser.tokens.size()) {
                return false; // Incomplete, treat as character class
            }
            var numberToken = parser.tokens.get(index);
            if (numberToken.type != LexerTokenType.NUMBER) {
                return false; // Not a negative number, treat as character class
            }
            index++; // Skip the number token
        } else {
            // If it doesn't start with $, number, or -, it's definitely a character class
            return false;
        }
        
        if (index >= parser.tokens.size()) {
            return true; // Just [$A], [0], or [-1] - treat as array subscript
        }
        
        var nextToken = parser.tokens.get(index);
        
        // If followed by '-', it's a character class range
        if (nextToken.text.equals("-")) {
            return false; // Character class pattern like [$A-Z] or [0-9]
        }
        
        // If followed by ']', it's a simple array subscript
        if (nextToken.text.equals("]")) {
            return true; // Array subscript like [$A], [0], or [-1]
        }
        
        // For other cases, default to array subscript behavior
        return true;
    }

}

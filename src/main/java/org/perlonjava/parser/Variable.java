package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.ParsePrimary.parsePrimary;
import static org.perlonjava.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.parser.TokenUtils.peek;

public class Variable {
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

            // Create a Variable node
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
                            // Only treat as array access if followed by $ or number
                            var tokenNext = parser.tokens.get(parser.tokenIndex + 1);
                            parser.ctx.logDebug("str [ " + tokenNext);
                            if (!tokenNext.text.equals("$") && !(tokenNext.type == LexerTokenType.NUMBER)) {
                                return operand; // Stop parsing, let caller handle
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
                            // Only treat as array access if followed by $ or number
                            if (parser.tokenIndex + 1 >= parser.tokens.size()) {
                                break outerLoop;
                            }
                            var tokenNext = parser.tokens.get(parser.tokenIndex + 1);
                            parser.ctx.logDebug("str [ " + tokenNext);
                            if (!tokenNext.text.equals("$") && !(tokenNext.type == LexerTokenType.NUMBER)) {
                                break outerLoop;
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
                    ctrlChar = String.valueOf((char)(firstChar - 'A' + 1));
                } else if (firstChar >= 'a' && firstChar <= 'z') {
                    ctrlChar = String.valueOf((char)(firstChar - 'a' + 1));
                } else if (firstChar == '@') {
                    ctrlChar = String.valueOf((char)0);
                } else if (firstChar >= '[' && firstChar <= '_') {
                    ctrlChar = String.valueOf((char)(firstChar - '[' + 27));
                } else if (firstChar == '?') {
                    ctrlChar = String.valueOf((char)127);
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
            Node operand = new OperatorNode(sigil, new IdentifierNode(bracedVarName, parser.tokenIndex), parser.tokenIndex);

            try {
                operand = parseArrayHashAccessInBraces(parser, operand, isStringInterpolation);
                if (TokenUtils.peek(parser).text.equals("}")) {
                    TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                    return operand;
                } else {
                    parser.tokenIndex = savedIndex;
                }
            } catch (Exception e) {
                parser.tokenIndex = savedIndex;
            }
        } else {
            parser.tokenIndex = savedIndex;
        }

        // Fall back to parsing as a general expression with original context
        try {
            Node operand = parser.parseExpression(0);
            if (!TokenUtils.peek(parser).text.equals("}")) {
                throw new PerlCompilerException(parser.tokenIndex, "Missing closing brace in variable interpolation", parser.ctx.errorUtil);
            }
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            return new OperatorNode(sigil, operand, parser.tokenIndex);
        } catch (Exception e) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error in braced variable: " + e.getMessage(), parser.ctx.errorUtil);
        }
    }
}

package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.symbols.SymbolTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.perlonjava.parser.OperatorParser.ensureOneOperand;
import static org.perlonjava.parser.PrototypeArgs.consumeArgsWithPrototype;
import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The ParseInfix class is responsible for parsing infix operations in the source code.
 * It handles binary operators, ternary operators, and special cases like method calls and subscripts.
 */
public class ParseInfix {

    // Non-chainable comparison operators (cannot be chained with any operator)
    private static final List<String> NON_CHAINABLE_COMPARISON_OPS = Arrays.asList("<=>", "cmp", "~~");

    // Non-chainable relational operators (cannot be chained with any operator)
    private static final List<String> NON_CHAINABLE_RELATIONAL_OPS = List.of("isa");

    // Chainable equality operators (can chain with each other)
    private static final List<String> CHAINABLE_EQUALITY_OPS = Arrays.asList("==", "!=", "eq", "ne");

    // Chainable relational operators (can chain with each other)
    private static final List<String> CHAINABLE_RELATIONAL_OPS = Arrays.asList("<", ">", "<=", ">=", "lt", "gt", "le", "ge");

    /**
     * Parses infix operators and their right-hand operands.
     * This method handles binary operators, ternary operators, and special cases like method calls and subscripts.
     *
     * @param parser     The parser instance used for parsing.
     * @param left       The left-hand operand of the infix operation.
     * @param precedence The current precedence level for parsing.
     * @return A node representing the parsed infix operation.
     * @throws PerlCompilerException If there's an unexpected infix operator or syntax error.
     */
    public static Node parseInfixOperation(Parser parser, Node left, int precedence) {
        LexerToken token = TokenUtils.consume(parser);

        Node right;

        if (ParserTables.INFIX_OP.contains(token.text)) {
            String operator = token.text;

            // Check if left operand is a DECLARED REFERENCE (my \$a, our \@arr, etc.)
            // Most operators cannot be applied to declared references
            if (left instanceof OperatorNode leftOp) {
                String declOperator = leftOp.operator;
                boolean isDeclaredReference = leftOp.getBooleanAnnotation("isDeclaredReference");

                if (isDeclaredReference &&
                    ("my".equals(declOperator) || "our".equals(declOperator) ||
                     "state".equals(declOperator) || "local".equals(declOperator))) {

                    // Allow assignment operators and comma (special handling)
                    boolean isAllowedOperator =
                        operator.equals("=") || operator.equals(",") ||
                        operator.endsWith("="); // +=, -=, .=, etc.

                    if (!isAllowedOperator) {
                        // Get operator name for error message
                        String opName = switch (operator) {
                            case "**" -> "exponentiation (**)";
                            case "+" -> "addition (+)";
                            case "-" -> "subtraction (-)";
                            case "*" -> "multiplication (*)";
                            case "/" -> "division (/)";
                            case "%" -> "modulus (%)";
                            case "x" -> "repetition (x)";
                            case "." -> "concatenation (.)";
                            case ".." -> "range (..)";
                            case "..." -> "flip-flop (...)";
                            case "<<", ">>" -> "shift (" + operator + ")";
                            case "<", ">", "<=", ">=", "==", "!=" -> "comparison (" + operator + ")";
                            case "lt", "gt", "le", "ge", "eq", "ne", "cmp", "<=>" -> "comparison (" + operator + ")";
                            case "~~" -> "smartmatch (~~)";
                            case "&", "|", "^" -> "bitwise (" + operator + ")";
                            case "&&", "||", "//" -> "logical (" + operator + ")";
                            case "and", "or", "xor" -> "logical (" + operator + ")";
                            default -> operator + " (" + operator + ")";
                        };

                        throw new PerlCompilerException(
                            parser.tokenIndex,
                            "Can't declare " + opName + " in " + declOperator,
                            parser.ctx.errorUtil
                        );
                    }
                }
            }

            boolean operatorEnabled = switch (operator) {
                case "isa" -> parser.ctx.symbolTable.isFeatureCategoryEnabled("isa");
                case "&.", "|.", "^.", "&.=", "|.=", "^.=" ->
                        parser.ctx.symbolTable.isFeatureCategoryEnabled("bitwise");
                case "&", "|", "^", "&=", "|=", "^=" -> {
                    if (parser.ctx.symbolTable.isFeatureCategoryEnabled("bitwise")) {
                        operator = "binary" + operator;
                    }
                    yield true;
                }
                default -> true;
            };
            if (!operatorEnabled) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }

            right = parser.parseExpression(precedence);
            if (right == null) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }

            if (operator.equals("..") || operator.equals("...")) {
                // Handle regex in: /3/../5/
                if (left instanceof OperatorNode operatorNode && operatorNode.operator.equals("matchRegex")) {
                    left = new OperatorNode("quoteRegex", operatorNode.operand, operatorNode.tokenIndex);
                }
                if (right instanceof OperatorNode operatorNode && operatorNode.operator.equals("matchRegex")) {
                    right = new OperatorNode("quoteRegex", operatorNode.operand, operatorNode.tokenIndex);
                }
            }

            // Validate operator chaining rules (Perl 5.32+)
            validateOperatorChaining(parser, operator, left, right);

            return new BinaryOperatorNode(operator, left, right, parser.tokenIndex);
        }

        switch (token.text) {
            case ",":
            case "=>":
                if (token.text.equals("=>") && left instanceof IdentifierNode) {
                    // Autoquote - Convert IdentifierNode to StringNode
                    left = new StringNode(((IdentifierNode) left).name, ((IdentifierNode) left).tokenIndex);
                }
                token = peek(parser);
                if (token.type == LexerTokenType.EOF || ListParser.isListTerminator(parser, token) || token.text.equals(",") || token.text.equals("=>")) {
                    // "postfix" comma
                    return ListNode.makeList(left);
                }
                right = parser.parseExpression(precedence);
                return ListNode.makeList(left, right);
            case "?":
                // Handle ternary operator
                Node middle = parser.parseExpression(0);
                TokenUtils.consume(parser, LexerTokenType.OPERATOR, ":");
                right = parser.parseExpression(precedence);
                return new TernaryOperatorNode(token.text, left, middle, right, parser.tokenIndex);
            case "->":
                // Handle method calls and subscripts
                String nextText = peek(parser).text;
                switch (nextText) {
                    case "(":
                        TokenUtils.consume(parser);
                        right = new ListNode(ListParser.parseList(parser, ")", 0), parser.tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                    case "**":
                        // Postfix GLOB dereference: $ref->**
                        // Equivalent to prefix glob deref `*$ref`.
                        TokenUtils.consume(parser);
                        OperatorNode globDeref = new OperatorNode("*", left, parser.tokenIndex);
                        globDeref.setAnnotation("postfixDeref", true);
                        return globDeref;
                    case "*":
                        // Postfix glob slot access: $ref->*{IO} or $ref->*{CODE}
                        // Parse as *$ref{...}
                        TokenUtils.consume(parser); // consume '*'
                        if (peek(parser).text.equals("{")) {
                            TokenUtils.consume(parser); // consume '{'
                            right = new HashLiteralNode(parseHashSubscript(parser), parser.tokenIndex);
                            OperatorNode globForSlot = new OperatorNode("*", left, parser.tokenIndex);
                            globForSlot.setAnnotation("postfixDeref", true);
                            return new BinaryOperatorNode("{",
                                    globForSlot,
                                    right,
                                    parser.tokenIndex);
                        }
                        throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                    case "{":
                        TokenUtils.consume(parser);
                        right = new HashLiteralNode(parseHashSubscript(parser), parser.tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                    case "[":
                        TokenUtils.consume(parser);
                        right = new ArrayLiteralNode(parseArraySubscript(parser), parser.tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                    case "@*":
                        TokenUtils.consume(parser);
                        return new OperatorNode("@", left, parser.tokenIndex);
                    case "$*":
                        TokenUtils.consume(parser);
                        return new OperatorNode("$", left, parser.tokenIndex);
                    case "%*":
                        TokenUtils.consume(parser);
                        return new OperatorNode("%", left, parser.tokenIndex);
                    case "&*":
                        TokenUtils.consume(parser);
                        return new BinaryOperatorNode("(",
                                left,
                                ParserNodeUtils.atUnderscore(parser),
                                parser.tokenIndex);
                    case "$#":
                        // ->$#*
                        TokenUtils.consume(parser);
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "*");
                        left = ensureOneOperand(parser, token, left);
                        return new OperatorNode("$#",
                                left,
                                parser.tokenIndex);
                    case "@":
                        // ->@[0,-1];
                        TokenUtils.consume(parser);
                        right = ParsePrimary.parsePrimary(parser);
                        if (right instanceof HashLiteralNode) {
                            return new BinaryOperatorNode("{",
                                    new OperatorNode("@", left, parser.tokenIndex),
                                    right,
                                    parser.tokenIndex);
                        } else if (right instanceof ArrayLiteralNode) {
                            return new BinaryOperatorNode("[",
                                    new OperatorNode("@", left, parser.tokenIndex),
                                    right,
                                    parser.tokenIndex);
                        } else {
                            throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                        }
                    case "&":
                        // Handle lexical method calls: $obj->&priv()
                        TokenUtils.consume(parser); // consume '&'

                        // Parse the method name
                        LexerToken methodToken = peek(parser);
                        if (methodToken.type != LexerTokenType.IDENTIFIER) {
                            throw new PerlCompilerException(parser.tokenIndex, "Expecting method name after ->&", parser.ctx.errorUtil);
                        }
                        String methodName = methodToken.text;
                        TokenUtils.consume(parser); // consume method name

                        // Look up the lexical method in the symbol table
                        String lexicalKey = "&" + methodName;
                        SymbolTable.SymbolEntry entry = parser.ctx.symbolTable.getSymbolEntry(lexicalKey);

                        if (entry != null && entry.ast() instanceof OperatorNode varNode) {
                            // This is a lexical method - get the hidden variable AST
                            // The AST contains the hidden variable (e.g., $priv__lexmethod_123)
                            // Create a method call using the hidden variable
                            right = varNode; // The hidden variable node

                            // Check for method arguments
                            if (peek(parser).text.equals("(")) {
                                // Method call with arguments: ->&priv(args)
                                ListNode args = consumeArgsWithPrototype(parser, null);
                                right = new BinaryOperatorNode("(", right, args, parser.tokenIndex);
                            }

                            // Return method call via the hidden variable
                            return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                        }

                        // Not a lexical method - treat as a regular code reference call
                        // This creates a call like $obj->(&NAME) which will look up &NAME at runtime
                        Node methodRef = new OperatorNode("&", new IdentifierNode(methodName, parser.tokenIndex), parser.tokenIndex);

                        // Check for method arguments
                        if (peek(parser).text.equals("(")) {
                            ListNode args = consumeArgsWithPrototype(parser, null);
                            right = new BinaryOperatorNode("(", methodRef, args, parser.tokenIndex);
                        } else {
                            // No arguments - just the method reference
                            right = methodRef;
                        }

                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                    default:
                        parser.parsingForLoopVariable = true;
                        if (nextText.equals("$")) {
                            // Method call with ->$var or ->$var()
                            right = parser.parseExpression(precedence);
                            if (peek(parser).text.equals("(")) {
                                // Method call with ->$var()
                                ListNode args = consumeArgsWithPrototype(parser, null);
                                right = new BinaryOperatorNode("(", right, args, parser.tokenIndex);
                            }
                        } else {
                            // Method call with ->method or ->method()
                            right = SubroutineParser.parseSubroutineCall(parser, true);
                            parser.ctx.logDebug("method call -> " + right);
                        }
                        parser.parsingForLoopVariable = false;

                        if (right instanceof BinaryOperatorNode && ((BinaryOperatorNode) right).operator.equals("(")) {
                            // Right has parameter list
                        } else {
                            // Insert an empty parameter list
                            right = new BinaryOperatorNode("(", right, new ListNode(parser.tokenIndex), parser.tokenIndex);
                        }

                        return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
                }
            case "(":
                // Handle function calls
                right = new ListNode(ListParser.parseList(parser, ")", 0), parser.tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
            case "{":
                // Handle hash subscripts
                right = new HashLiteralNode(parseHashSubscript(parser), parser.tokenIndex);
                // Check if left is $$var and transform to $var->{...}
                if (left instanceof OperatorNode leftOp && leftOp.operator.equals("$")
                        && leftOp.operand instanceof OperatorNode innerOp && innerOp.operator.equals("$")) {
                    // Transform $$var{...} to $var->{...}
                    return new BinaryOperatorNode("->", innerOp, right, parser.tokenIndex);
                }
                return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
            case "[":
                // Handle array subscripts
                right = new ArrayLiteralNode(parseArraySubscript(parser), parser.tokenIndex);
                // Check if left is $$var and transform to $var->[...]
                if (left instanceof OperatorNode leftOp && leftOp.operator.equals("$")
                        && leftOp.operand instanceof OperatorNode innerOp && innerOp.operator.equals("$")) {
                    // Transform $$var[...] to $var->[...]
                    return new BinaryOperatorNode("->", innerOp, right, parser.tokenIndex);
                }
                return new BinaryOperatorNode(token.text, left, right, parser.tokenIndex);
            case "--":
            case "++":
                // Handle postfix increment/decrement
                return new OperatorNode(token.text + "postfix", left, parser.tokenIndex);
            default:
                // Special check: if this is an IDENTIFIER that's a quote-like operator, it's not an infix operator
                // This handles cases where qr/q/qq/etc mistakenly reach here due to parser state issues
                if (token.type == LexerTokenType.IDENTIFIER && ParsePrimary.isIsQuoteLikeOperator(token.text)) {
                    // This quote-like operator shouldn't be treated as infix - it should have been parsed as primary
                    // Backtrack so it can be re-parsed correctly
                    parser.tokenIndex--;
                    return left;
                }
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
        }
    }

    private static List<Node> parseArraySubscript(Parser parser) {
        int currentIndex = parser.tokenIndex;

        // Handle optional empty parentheses
        LexerToken nextToken = peek(parser);
        if (nextToken.text.equals("(")) {
            consume(parser);
            LexerToken next = consume(parser);
            ListParser.consumeCommas(parser);
            if (next.text.equals(")") && peek(parser).text.equals("]")) {
                consume(parser);
                return new ArrayList<>();
            }
        }
        // backtrack
        parser.tokenIndex = currentIndex;

        return ListParser.parseList(parser, "]", 1);

    }

    static List<Node> parseHashSubscript(Parser parser) {
        parser.ctx.logDebug("parseHashSubscript start");
        int currentIndex = parser.tokenIndex;

        LexerToken ident = TokenUtils.consume(parser);
        LexerToken close = TokenUtils.consume(parser);
        if (ident.type == LexerTokenType.IDENTIFIER && close.text.equals("}")) {
            // autoquote
            List<Node> list = new ArrayList<>();
            list.add(new IdentifierNode(ident.text, currentIndex));
            return list;
        }
        // backtrack
        parser.tokenIndex = currentIndex;

        // Handle optional empty parentheses
        LexerToken nextToken = peek(parser);
        if (nextToken.text.equals("(")) {
            consume(parser);
            if (peek(parser).text.equals(")")) {
                consume(parser);
                if (peek(parser).text.equals("}")) {
                    consume(parser);
                    return new ArrayList<>();
                }
            }
        }
        // backtrack
        parser.tokenIndex = currentIndex;

        return ListParser.parseList(parser, "}", 1);
    }

    /**
     * Validates operator chaining rules for comparison and relational operators.
     * Perl 5.32+ introduced chained comparison operators with specific rules:
     * - Non-chainable operators (<==>, cmp, ~~, isa) cannot be chained with any operator at the same precedence
     * - Chainable equality operators (==, !=, eq, ne) can only chain with each other
     * - Chainable relational operators (<, >, <=, >=, lt, gt, le, ge) can only chain with each other
     * <p>
     * Note: Operators only chain when they have the same precedence level.
     * For example: "5 < 6 eq '1'" is valid because < (precedence 14) and eq (precedence 13) don't chain.
     *
     * @param parser   The parser instance
     * @param operator The current operator being parsed
     * @param left     The left operand
     * @param right    The right operand
     * @throws PerlCompilerException if operator chaining rules are violated
     */
    private static void validateOperatorChaining(Parser parser, String operator, Node left, Node right) {
        // Only validate if current operator is a comparison/relational operator
        boolean isNonChainableComparison = NON_CHAINABLE_COMPARISON_OPS.contains(operator);
        boolean isNonChainableRelational = NON_CHAINABLE_RELATIONAL_OPS.contains(operator);
        boolean isChainableEquality = CHAINABLE_EQUALITY_OPS.contains(operator);
        boolean isChainableRelational = CHAINABLE_RELATIONAL_OPS.contains(operator);

        if (!isNonChainableComparison && !isNonChainableRelational &&
                !isChainableEquality && !isChainableRelational) {
            return; // Not a comparison/relational operator
        }

        // Get precedence of current operator
        Integer currentPrecedence = ParserTables.precedenceMap.get(operator);
        if (currentPrecedence == null) {
            return;
        }

        // Check if left operand is a comparison/relational operator
        if (left instanceof BinaryOperatorNode leftBinOp) {
            String leftOp = leftBinOp.operator;
            Integer leftPrecedence = ParserTables.precedenceMap.get(leftOp);

            boolean leftIsNonChainableComparison = NON_CHAINABLE_COMPARISON_OPS.contains(leftOp);
            boolean leftIsNonChainableRelational = NON_CHAINABLE_RELATIONAL_OPS.contains(leftOp);
            boolean leftIsChainableEquality = CHAINABLE_EQUALITY_OPS.contains(leftOp);
            boolean leftIsChainableRelational = CHAINABLE_RELATIONAL_OPS.contains(leftOp);

            // Special rule for 'isa': cannot chain with any relational operator regardless of precedence
            if (isNonChainableRelational && leftIsChainableRelational) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }
            if (leftIsNonChainableRelational && isChainableRelational) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }

            // Only validate same-precedence chaining for other operators
            if (leftPrecedence != null && leftPrecedence.equals(currentPrecedence)) {
                // Rule 1: Non-chainable operators cannot be chained with anything at same precedence
                if (leftIsNonChainableComparison || leftIsNonChainableRelational) {
                    throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                }

                // Rule 2: Current operator is non-chainable - cannot chain with anything at same precedence
                if (isNonChainableComparison || isNonChainableRelational) {
                    throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                }

                // Rule 3: Cannot mix chainable equality with chainable relational (even at same precedence)
                // Note: This shouldn't happen since they have different precedence, but check anyway
                if (isChainableEquality && leftIsChainableRelational) {
                    throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                }
                if (isChainableRelational && leftIsChainableEquality) {
                    throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
                }
            }
        }

        // Check if right operand is a comparison/relational operator (for higher precedence operators)
        if (right instanceof BinaryOperatorNode rightBinOp) {
            String rightOp = rightBinOp.operator;

            boolean rightIsNonChainableComparison = NON_CHAINABLE_COMPARISON_OPS.contains(rightOp);
            boolean rightIsNonChainableRelational = NON_CHAINABLE_RELATIONAL_OPS.contains(rightOp);
            boolean rightIsChainableEquality = CHAINABLE_EQUALITY_OPS.contains(rightOp);
            boolean rightIsChainableRelational = CHAINABLE_RELATIONAL_OPS.contains(rightOp);

            // Special rule for 'isa': cannot chain with any relational operator regardless of precedence
            if (isNonChainableRelational && rightIsChainableRelational) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }
            if (rightIsNonChainableRelational && isChainableRelational) {
                throw new PerlCompilerException(parser.tokenIndex, "syntax error", parser.ctx.errorUtil);
            }
        }
    }
}

package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.*;

import static org.perlonjava.parser.TokenUtils.consume;
import static org.perlonjava.parser.TokenUtils.peek;
import static org.perlonjava.runtime.GlobalVariable.existsGlobalCodeRef;

public class Parser {
    public static final Set<String> TERMINATORS =
            Set.of(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when");
    public static final Set<String> LIST_TERMINATORS =
            Set.of(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when", "not", "and", "or");
    public static final Set<String> INFIX_OP = Set.of(
            "or", "xor", "and", "||", "//", "&&", "|", "^", "&", "|.", "^.", "&.",
            "==", "!=", "<=>", "eq", "ne", "cmp", "<", ">", "<=",
            ">=", "lt", "gt", "le", "ge", "<<", ">>", "+", "-", "*",
            "**", "/", "%", ".", "=", "**=", "+=", "*=", "&=", "&.=",
            "<<=", "&&=", "-=", "/=", "|=", "|.=", ">>=", "||=", ".=",
            "%=", "^=", "^.=", "//=", "x=", "=~", "!~", "x", "..", "...", "isa"
    );
    private static final Set<String> LVALUE_INFIX_OP = Set.of(
            "=", "**=", "+=", "*=", "&=", "&.=",
            "<<=", "&&=", "-=", "/=", "|=", "|.=",
            ">>=", "||=", ".=", "%=", "^=", "^.=",
            "//=", "x="
    );
    private static final Set<String> RIGHT_ASSOC_OP = Set.of(
            "=", "**=", "+=", "*=", "&=", "&.=", "<<=", "&&=", "-=", "/=", "|=", "|.=",
            ">>=", "||=", ".=", "%=", "^=", "^.=", "//=", "x=", "**", "?"
    );

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

    private static final Map<String, Integer> precedenceMap = new HashMap<>();

    static {
        addOperatorsToMap(1, "or", "xor");
        addOperatorsToMap(2, "and");
        addOperatorsToMap(3, "not");
        addOperatorsToMap(4, "print");
        addOperatorsToMap(5, ",", "=>");
        addOperatorsToMap(6, "=", "**=", "+=", "*=", "&=", "&.=", "<<=", "&&=", "-=", "/=", "|=", "|.=", ">>=", "||=", ".=", "%=", "^=", "^.=", "//=", "x=");
        addOperatorsToMap(7, "?");
        addOperatorsToMap(8, "..", "...");
        addOperatorsToMap(9, "||", "^^", "//");
        addOperatorsToMap(10, "&&");
        addOperatorsToMap(11, "|", "^", "|.", "^.");
        addOperatorsToMap(12, "&", "&.");
        addOperatorsToMap(13, "==", "!=", "<=>", "eq", "ne", "cmp");
        addOperatorsToMap(14, "<", ">", "<=", ">=", "lt", "gt", "le", "ge");
        addOperatorsToMap(15, "isa");
        addOperatorsToMap(16, "-d");
        addOperatorsToMap(17, ">>", "<<");
        addOperatorsToMap(18, "+", "-", ".");
        addOperatorsToMap(19, "*", "/", "%", "x");
        addOperatorsToMap(20, "=~", "!~");
        addOperatorsToMap(21, "!", "~", "~.", "\\");
        addOperatorsToMap(22, "**");
        addOperatorsToMap(23, "++", "--");
        addOperatorsToMap(24, "->");
    }

    public final EmitterContext ctx;
    public final List<LexerToken> tokens;
    public int tokenIndex = 0;
    public boolean parsingForLoopVariable = false;
    public boolean parsingTakeReference = false;

    public Parser(EmitterContext ctx, List<LexerToken> tokens) {
        this.ctx = ctx;
        this.tokens = tokens;
    }

    private static void addOperatorsToMap(int precedence, String... operators) {
        for (String operator : operators) {
            precedenceMap.put(operator, precedence);
        }
    }

    public int getPrecedence(String operator) {
        return precedenceMap.getOrDefault(operator, 24);
    }

    public Node parse() {
        if (tokens.get(tokenIndex).text.equals("=")) {
            // looks like pod: insert a newline to trigger pod parsing
            tokens.addFirst(new LexerToken(LexerTokenType.NEWLINE, "\n"));
        }
        return parseBlock();
    }

    public BlockNode parseBlock() {
        int currentIndex = tokenIndex;
        ctx.symbolTable.enterScope();
        List<Node> statements = new ArrayList<>();
        LexerToken token = peek(this);
        while (token.type != LexerTokenType.EOF
                && !(token.type == LexerTokenType.OPERATOR && token.text.equals("}"))) {
            if (token.text.equals(";")) {
                TokenUtils.consume(this);
            } else {
                statements.add(parseStatement());
            }
            token = peek(this);
        }
        if (statements.isEmpty()) {
            statements.add(new ListNode(tokenIndex));
        }
        ctx.symbolTable.exitScope();
        return new BlockNode(statements, currentIndex);
    }

    public Node parseStatement() {
        int currentIndex = tokenIndex;
        LexerToken token = peek(this);
        ctx.logDebug("parseStatement `" + token.text + "`");

        // check for label:
        String label = null;
        if (token.type == LexerTokenType.IDENTIFIER) {
            String id = TokenUtils.consume(this).text;
            if (peek(this).text.equals(":")) {
                label = id;
                TokenUtils.consume(this);
                token = peek(this);
            } else {
                tokenIndex = currentIndex;  // backtrack
            }
        }

        if (token.type == LexerTokenType.IDENTIFIER) {
            switch (token.text) {
                case "CHECK", "INIT", "UNITCHECK", "BEGIN", "END":
                    return SpecialBlockParser.parseSpecialBlock(this);
                case "if", "unless":
                    return StatementParser.parseIfStatement(this);
                case "for", "foreach":
                    return StatementParser.parseForStatement(this, label);
                case "while", "until":
                    return StatementParser.parseWhileStatement(this, label);
                case "try":
                    if (ctx.symbolTable.isFeatureCategoryEnabled("try")) {
                        return StatementParser.parseTryStatement(this);
                    }
                    break;
                case "package":
                    return StatementParser.parsePackageDeclaration(this, token);
                case "use", "no":
                    return StatementParser.parseUseDeclaration(this, token);
                case "sub":
                    // Must be followed by an identifier
                    tokenIndex++;
                    if (peek(this).type == LexerTokenType.IDENTIFIER) {
                        return SubroutineParser.parseSubroutineDefinition(this, true, "our");
                    }
                    // otherwise backtrack
                    tokenIndex = currentIndex;
                    break;
                case "our", "my", "state":
                    String declaration = consume(this).text;
                    if (consume(this).text.equals("sub") && peek(this).type == LexerTokenType.IDENTIFIER) {
                        // my sub name
                        return SubroutineParser.parseSubroutineDefinition(this, true, declaration);
                    }
                    // otherwise backtrack
                    tokenIndex = currentIndex;
                    break;
            }
        }
        if (token.type == LexerTokenType.OPERATOR) {
            switch (token.text) {
                case "...":
                    TokenUtils.consume(this);
                    return new OperatorNode(
                            "die",
                            new StringNode("Unimplemented", tokenIndex),
                            tokenIndex);
                case "{":
                    if (!isHashLiteral()) { // bare-block
                        TokenUtils.consume(this, LexerTokenType.OPERATOR, "{");
                        BlockNode block = parseBlock();
                        block.isLoop = true;
                        block.labelName = label;
                        TokenUtils.consume(this, LexerTokenType.OPERATOR, "}");
                        return block;
                    }
            }
        }
        Node expression = parseExpression(0);
        token = peek(this);
        if (token.type == LexerTokenType.IDENTIFIER) {
            // statement modifier: if, for ...
            switch (token.text) {
                case "if":
                    TokenUtils.consume(this);
                    Node modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    return new BinaryOperatorNode("&&", modifierExpression, expression, tokenIndex);
                case "unless":
                    TokenUtils.consume(this);
                    modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    return new BinaryOperatorNode("||", modifierExpression, expression, tokenIndex);
                case "for", "foreach":
                    TokenUtils.consume(this);
                    modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    return new For1Node(
                            null,
                            false,
                            new OperatorNode("$", new IdentifierNode("_", tokenIndex), tokenIndex),  // $_
                            modifierExpression,
                            expression,
                            null,
                            tokenIndex);
                case "while", "until":
                    TokenUtils.consume(this);
                    modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    if (token.text.equals("until")) {
                        modifierExpression = new OperatorNode("not", modifierExpression, modifierExpression.getIndex());
                    }
                    boolean isDoWhile = false;
                    if (expression instanceof BlockNode) {
                        // special case:  `do { BLOCK } while CONDITION`
                        // executes the loop at least once
                        ctx.logDebug("do-while " + expression);
                        isDoWhile = true;
                    }
                    return new For3Node(null,
                            false,
                            null, modifierExpression,
                            null, expression, null,
                            isDoWhile,
                            tokenIndex);
            }
            throw new PerlCompilerException(tokenIndex, "Not implemented: " + token, ctx.errorUtil);
        }
        parseStatementTerminator();
        return expression;
    }

    public void parseStatementTerminator() {
        LexerToken token = peek(this);
        if (token.type != LexerTokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        if (token.text.equals(";")) {
            TokenUtils.consume(this);
        }
    }

    // disambiguate between Block or Hash literal
    public boolean isHashLiteral() {
        int currentIndex = tokenIndex;

        // Start after the opening '{'
        TokenUtils.consume(this, LexerTokenType.OPERATOR, "{");

        int braceCount = 1; // Track nested braces
        while (braceCount > 0) {
            LexerToken token = TokenUtils.consume(this);
            ctx.logDebug("isHashLiteral " + token + " braceCount:" + braceCount);
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
                                ctx.logDebug("isHashLiteral TRUE");
                                tokenIndex = currentIndex;
                                return true; // Likely a hash literal
                            case ";":
                                tokenIndex = currentIndex;
                                return false; // Likely a block
                            case "for", "while", "if", "unless", "until", "foreach":
                                if (!TokenUtils.peek(this).text.equals("=>")) {
                                    ctx.logDebug("isHashLiteral FALSE");
                                    tokenIndex = currentIndex;
                                    return false; // Likely a block
                                }
                        }
                    }
            }
        }
        ctx.logDebug("isHashLiteral undecided");
        tokenIndex = currentIndex;
        return true;
    }

    /**
     * Parses an expression based on operator precedence.
     * <p>
     * Higher precedence means tighter: `*` has higher precedence than `+`
     * <p>
     * Explanation of the  <a href="https://en.wikipedia.org/wiki/Operator-precedence_parser">precedence climbing method</a>
     * can be found in Wikipedia.
     * </p>
     *
     * @param precedence The precedence level of the current expression.
     * @return The root node of the parsed expression.
     */
    public Node parseExpression(int precedence) {
        // First, parse the primary expression (like a number or a variable).
        Node left = parsePrimary();

        // Continuously process tokens until we reach the end of the expression.
        while (true) {
            // Peek at the next token to determine what to do next.
            LexerToken token = peek(this);

            // Check if we have reached the end of the input (EOF) or a terminator (like `;`).
            if (token.type == LexerTokenType.EOF || TERMINATORS.contains(token.text)) {
                break; // Exit the loop if we're done parsing.
            }

            // Get the precedence of the current token.
            int tokenPrecedence = getPrecedence(token.text);

            // If the token's precedence is less than the precedence of the current expression, stop parsing.
            if (tokenPrecedence <= precedence) {
                break;
            }

            // Check for the special case of 'x=' tokens;
            // This handles cases where 'x=' is used as an operator.
            // The token combination is also used in assignments like '$x=3'.
            if (token.text.equals("x") && tokens.get(tokenIndex + 1).text.equals("=")) {
                // Combine 'x' and '=' into a single token 'x='
                token.text = "x=";
                // Set the token type to OPERATOR to reflect its usage
                token.type = LexerTokenType.OPERATOR;
                // Remove the '=' token from the list as it is now part of 'x='
                tokens.remove(tokenIndex + 1);
            }

            // If the operator is right associative (like exponentiation), parse it with lower precedence.
            if (RIGHT_ASSOC_OP.contains(token.text)) {
                ctx.logDebug("parseExpression `" + token.text + "` precedence: " + tokenPrecedence + " right assoc");
                left = parseInfixOperation(left, tokenPrecedence - 1); // Parse the right side with lower precedence.
            } else {
                // Otherwise, parse it normally with the same precedence.
                ctx.logDebug("parseExpression `" + token.text + "` precedence: " + tokenPrecedence + " left assoc");
                left = parseInfixOperation(left, tokenPrecedence);
            }
        }

        // Return the root node of the constructed expression tree.
        return left;
    }

    public Node parsePrimary() {
        int startIndex = tokenIndex;
        LexerToken token = TokenUtils.consume(this); // Consume the next token from the input
        String operator = token.text;
        Node operand;

        switch (token.type) {
            case IDENTIFIER:
                String nextTokenText = peek(this).text;
                if (nextTokenText.equals("=>")) {
                    // Autoquote
                    return new StringNode(token.text, tokenIndex);
                }

                boolean operatorEnabled = true;
                boolean calledWithCore = false;

                // Try to parse a builtin operation; backtrack if it fails
                if (token.text.equals("CORE") && nextTokenText.equals("::")) {
                    calledWithCore = true;
                    TokenUtils.consume(this);  // "::"
                    token = TokenUtils.consume(this); // operator
                    operator = token.text;
                } else {
                    // Check if the operator is enabled in the current scope
                    operatorEnabled = switch (operator) {
                        case "say", "fc", "state", "evalbytes" -> ctx.symbolTable.isFeatureCategoryEnabled(operator);
                        case "__SUB__" -> ctx.symbolTable.isFeatureCategoryEnabled("current_sub");
                        default -> operatorEnabled;
                    };
                }

                if (!calledWithCore && operatorEnabled && OVERRIDABLE_OP.contains(operator)) {
                    // It is possible to override the core function by defining
                    // a subroutine in the current package, or in CORE::GLOBAL::
                    //
                    // Optimization: only test this if an override was defined
                    //

                    if (existsGlobalCodeRef(ctx.symbolTable.getCurrentPackage() + "::" + operator)) {
                        tokenIndex = startIndex;   // backtrack
                        return SubroutineParser.parseSubroutineCall(this);
                    }
                    // if (existsGlobalCodeRef("CORE::GLOBAL::" + operator)) {
                    //     tokenIndex = startIndex;   // backtrack
                    //     return SubroutineParser.parseSubroutineCall(this);
                    // }
                }

                if (operatorEnabled) {
                    Node operation = OperatorParser.parseCoreOperator(this, token, startIndex);
                    if (operation != null) {
                        return operation;
                    }
                }

                if (calledWithCore) {
                    throw new PerlCompilerException(tokenIndex, "CORE::" + operator + " is not a keyword", ctx.errorUtil);
                }

                // Handle any other identifier as a subroutine call or identifier node
                tokenIndex = startIndex;   // backtrack
                return SubroutineParser.parseSubroutineCall(this);
            case NUMBER:
                // Handle number literals
                return NumberParser.parseNumber(this, token);
            case STRING:
                // Handle string literals
                return new StringNode(token.text, tokenIndex);
            case OPERATOR:
                switch (token.text) {
                    case "(":
                        // Handle parentheses to parse a nested expression or to construct a list
                        return new ListNode(ListParser.parseList(this, ")", 0), tokenIndex);
                    case "{":
                        // Handle curly brackets to parse a nested expression
                        return new HashLiteralNode(ListParser.parseList(this, "}", 0), tokenIndex);
                    case "[":
                        // Handle square brackets to parse a nested expression
                        return new ArrayLiteralNode(ListParser.parseList(this, "]", 0), tokenIndex);
                    case ".":
                        // Handle fractional numbers
                        return NumberParser.parseFractionalNumber(this);
                    case "<", "<<":
                        return OperatorParser.parseDiamondOperator(this, token);
                    case "'", "\"", "/", "//", "/=", "`":
                        // Handle single and double-quoted strings
                        return StringParser.parseRawString(this, token.text);
                    case "\\":
                        // Take reference
                        parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        parsingTakeReference = false;
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "$", "$#", "@", "%", "*":
                        return Variable.parseVariable(this, token.text);
                    case "&":
                        return Variable.parseCoderefVariable(this, token);
                    case "!", "+":
                        // Handle unary operators like `! +`
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "~", "~.":
                        // Handle unary operators like `~ ~.`
                        if (ctx.symbolTable.isFeatureCategoryEnabled("bitwise")) {
                            if (operator.equals("~")) {
                                operator = "binary" + operator;
                            }
                        } else {
                            if (operator.equals("~.")) {
                                throw new PerlCompilerException(tokenIndex, "syntax error", ctx.errorUtil);
                            }
                        }
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        return new OperatorNode(operator, operand, tokenIndex);
                    case "--":
                    case "++":
                        // Handle unary operators like `++`
                        operand = parseExpression(getPrecedence(token.text));
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "-":
                        // Handle unary operators like `- -d`
                        LexerToken nextToken = tokens.get(tokenIndex);
                        if (nextToken.type == LexerTokenType.IDENTIFIER && nextToken.text.length() == 1) {
                            // Handle `-d`
                            operator = "-" + nextToken.text;
                            tokenIndex++;
                            nextToken = peek(this);
                            if (nextToken.text.equals("_")) {
                                // Handle `-f _`
                                TokenUtils.consume(this);
                                operand = new IdentifierNode("_", tokenIndex);
                            } else {
                                operand = ListParser.parseZeroOrOneList(this, 0);
                                if (((ListNode) operand).elements.isEmpty()) {
                                    // create `$_` variable
                                    operand = new OperatorNode(
                                            "$", new IdentifierNode("_", this.tokenIndex), this.tokenIndex);
                                }
                            }
                            return new OperatorNode(operator, operand, tokenIndex);
                        }
                        // Unary minus
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        if (operand instanceof IdentifierNode identifierNode) {
                            // "-name" return string
                            return new StringNode("-" + identifierNode.name, tokenIndex);
                        }
                        return new OperatorNode("unaryMinus", operand, tokenIndex);
                }
                break;
            case EOF:
                // Handle end of input
                return null;
            default:
                // Throw an exception for any unexpected token
                throw new PerlCompilerException(tokenIndex, "syntax error", ctx.errorUtil);
        }
        // Throw an exception if no valid case was found
        throw new PerlCompilerException(tokenIndex, "syntax error", ctx.errorUtil);
    }

    /**
     * Parses infix operators and their right-hand operands.
     * This method handles binary operators, ternary operators, and special cases like method calls and subscripts.
     *
     * @param left       The left-hand operand of the infix operation.
     * @param precedence The current precedence level for parsing.
     * @return A node representing the parsed infix operation.
     * @throws PerlCompilerException If there's an unexpected infix operator or syntax error.
     */
    public Node parseInfixOperation(Node left, int precedence) {
        LexerToken token = TokenUtils.consume(this);

        Node right;

        if (INFIX_OP.contains(token.text)) {
            String operator = token.text;
            boolean operatorEnabled = switch (operator) {
                case "isa" -> ctx.symbolTable.isFeatureCategoryEnabled("isa");
                case "&.", "|.", "^.", "&.=", "|.=", "^.=" -> ctx.symbolTable.isFeatureCategoryEnabled("bitwise");
                case "&", "|", "^", "&=", "|=", "^=" -> {
                    if (ctx.symbolTable.isFeatureCategoryEnabled("bitwise")) {
                        operator = "binary" + operator;
                    }
                    yield true;
                }
                default -> true;
            };
            if (!operatorEnabled) {
                throw new PerlCompilerException(tokenIndex, "syntax error", ctx.errorUtil);
            }

            right = parseExpression(precedence);
            if (right == null) {
                throw new PerlCompilerException(tokenIndex, "syntax error", ctx.errorUtil);
            }
            return new BinaryOperatorNode(operator, left, right, tokenIndex);
        }

        switch (token.text) {
            case ",":
            case "=>":
                if (token.text.equals("=>") && left instanceof IdentifierNode) {
                    // Autoquote - Convert IdentifierNode to StringNode
                    left = new StringNode(((IdentifierNode) left).name, ((IdentifierNode) left).tokenIndex);
                }
                token = peek(this);
                if (token.type == LexerTokenType.EOF || LIST_TERMINATORS.contains(token.text) || token.text.equals(",") || token.text.equals("=>")) {
                    // "postfix" comma
                    return ListNode.makeList(left);
                }
                right = parseExpression(precedence);
                return ListNode.makeList(left, right);
            case "?":
                Node middle = parseExpression(0);
                TokenUtils.consume(this, LexerTokenType.OPERATOR, ":");
                right = parseExpression(precedence);
                return new TernaryOperatorNode(token.text, left, middle, right, tokenIndex);
            case "->":
                String nextText = peek(this).text;
                switch (nextText) {
                    case "(":
                        TokenUtils.consume(this);
                        right = new ListNode(ListParser.parseList(this, ")", 0), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
                    case "{":
                        TokenUtils.consume(this);
                        right = new HashLiteralNode(parseHashSubscript(), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
                    case "[":
                        TokenUtils.consume(this);
                        right = new ArrayLiteralNode(ListParser.parseList(this, "]", 1), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
                    case "@*":
                        TokenUtils.consume(this);
                        return new OperatorNode("@", left, tokenIndex);
                    case "$*":
                        TokenUtils.consume(this);
                        return new OperatorNode("$", left, tokenIndex);
                    case "%*":
                        TokenUtils.consume(this);
                        return new OperatorNode("%", left, tokenIndex);
                    case "&*":
                        TokenUtils.consume(this);
                        return new BinaryOperatorNode("(",
                                left,
                                new OperatorNode("@",
                                        new IdentifierNode("_", tokenIndex), tokenIndex),
                                tokenIndex);
                    case "@":
                        // ->@[0,-1];
                        TokenUtils.consume(this);
                        right = parsePrimary();
                        if (right instanceof HashLiteralNode) {
                            return new BinaryOperatorNode("{",
                                    new OperatorNode("@", left, tokenIndex),
                                    right,
                                    tokenIndex);
                        } else if (right instanceof ArrayLiteralNode) {
                            return new BinaryOperatorNode("[",
                                    new OperatorNode("@", left, tokenIndex),
                                    right,
                                    tokenIndex);
                        } else {
                            throw new PerlCompilerException(tokenIndex, "syntax error", ctx.errorUtil);
                        }
                }
                parsingForLoopVariable = true;
                right = parseExpression(precedence);
                parsingForLoopVariable = false;

                if (right instanceof BinaryOperatorNode && ((BinaryOperatorNode) right).operator.equals("(")) {
                    // right has parameter list
                } else {
                    // insert an empty parameter list
                    right = new BinaryOperatorNode("(", right, new ListNode(tokenIndex), tokenIndex);
                }

                return new BinaryOperatorNode(token.text, left, right, tokenIndex);
            case "(":
                right = new ListNode(ListParser.parseList(this, ")", 0), tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, tokenIndex);
            case "{":
                right = new HashLiteralNode(parseHashSubscript(), tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, tokenIndex);
            case "[":
                right = new ArrayLiteralNode(ListParser.parseList(this, "]", 1), tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, tokenIndex);
            case "--":
            case "++":
                return new OperatorNode(token.text + "postfix", left, tokenIndex);
        }
        throw new PerlCompilerException(tokenIndex, "Unexpected infix operator: " + token, ctx.errorUtil);
    }

    public boolean isSpaceAfterPrintBlock() {
        int currentIndex = tokenIndex;
        LexerToken token = peek(this);
        boolean isSpace = false;
        switch (token.type) {
            case EOF:
            case IDENTIFIER:
            case NUMBER:
            case STRING:
                isSpace = true;
                break;
            case OPERATOR:
                switch (token.text) {
                    case "[", "\"", "//", "\\", "`", "$", "$#", "@", "%", "&", "!", "~",
                         "+", "-", "/", "*", ";", "++", "--":
                        isSpace = true;
                        break;
                    case ".":
                        // must be followed by NUMBER
                        TokenUtils.consume(this);
                        if (tokens.get(tokenIndex).type == LexerTokenType.NUMBER) {
                            isSpace = true;
                        }
                        tokenIndex = currentIndex;
                        break;
                    case "(", "'":
                        // must have space before
                        TokenUtils.consume(this);
                        if (tokenIndex != currentIndex) {
                            isSpace = true;
                        }
                        tokenIndex = currentIndex;
                        break;
                }
                break;
        }
        return isSpace;
    }

    private List<Node> parseHashSubscript() {
        ctx.logDebug("parseHashSubscript start");
        int currentIndex = tokenIndex;

        LexerToken ident = TokenUtils.consume(this);
        LexerToken close = TokenUtils.consume(this);
        if (ident.type == LexerTokenType.IDENTIFIER && close.text.equals("}")) {
            // autoquote
            List<Node> list = new ArrayList<>();
            list.add(new IdentifierNode(ident.text, currentIndex));
            return list;
        }

        // backtrack
        tokenIndex = currentIndex;
        return ListParser.parseList(this, "}", 1);
    }

}

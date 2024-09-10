package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalContext;
import org.perlonjava.runtime.ModuleLoader;
import org.perlonjava.runtime.NameCache;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.*;

public class Parser {
    public static final Set<String> TERMINATORS =
            Set.of(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when");
    public static final Set<String> LIST_TERMINATORS =
            Set.of(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when", "not", "and", "or");
    private static final Set<String> INFIX_OP = Set.of(
            "or", "xor", "and", "||", "//", "&&", "|", "^", "&",
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
        addOperatorsToMap(11, "|", "^");
        addOperatorsToMap(12, "&");
        addOperatorsToMap(13, "==", "!=", "<=>", "eq", "ne", "cmp");
        addOperatorsToMap(14, "<", ">", "<=", ">=", "lt", "gt", "le", "ge");
        addOperatorsToMap(15, "isa");
        addOperatorsToMap(17, ">>", "<<");
        addOperatorsToMap(18, "+", "-");
        addOperatorsToMap(19, "*", "/", "%", "x");
        addOperatorsToMap(20, "=~", "!~");
        addOperatorsToMap(21, "!", "~", "\\");
        addOperatorsToMap(22, "**");
        addOperatorsToMap(23, "++", "--");
        addOperatorsToMap(24, "->");
    }

    public final EmitterContext ctx;
    public final List<LexerToken> tokens;
    public int tokenIndex = 0;
    public boolean parsingForLoopVariable = false;
    private boolean parsingTakeReference = false;

    public Parser(EmitterContext ctx, List<LexerToken> tokens) {
        this.ctx = ctx;
        this.tokens = tokens;
    }

    private static void addOperatorsToMap(int precedence, String... operators) {
        for (String operator : operators) {
            precedenceMap.put(operator, precedence);
        }
    }

    public static int skipWhitespace(int tokenIndex, List<LexerToken> tokens) {
        while (tokenIndex < tokens.size()) {
            LexerToken token = tokens.get(tokenIndex);
            if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.NEWLINE) {
                tokenIndex++;
            } else if (token.type == LexerTokenType.OPERATOR && token.text.equals("#")) {
                // Skip the comment until the end of the line
                while (tokenIndex < tokens.size() && tokens.get(tokenIndex).type != LexerTokenType.NEWLINE) {
                    tokenIndex++;
                }
            } else {
                break;
            }
        }
        return tokenIndex;
    }

    public int getPrecedence(String operator) {
        return precedenceMap.getOrDefault(operator, 24);
    }

    public Node parse() {
        return parseBlock();
    }

    public BlockNode parseBlock() {
        ctx.symbolTable.enterScope();
        List<Node> statements = new ArrayList<>();
        LexerToken token = peek();
        while (token.type != LexerTokenType.EOF
                && !(token.type == LexerTokenType.OPERATOR && token.text.equals("}"))) {
            if (token.text.equals(";")) {
                consume();
            } else {
                statements.add(parseStatement());
            }
            token = peek();
        }
        if (statements.isEmpty()) {
            statements.add(new ListNode(tokenIndex));
        }
        ctx.symbolTable.exitScope();
        return new BlockNode(statements, tokenIndex);
    }

    public Node parseStatement() {
        int currentIndex = tokenIndex;
        LexerToken token = peek();
        ctx.logDebug("parseStatement `" + token.text + "`");

        if (token.type == LexerTokenType.IDENTIFIER) {
            switch (token.text) {
                case "if":
                case "unless":
                    return StatementParser.parseIfStatement(this);
                case "for":
                case "foreach":
                    return StatementParser.parseForStatement(this);
                case "while":
                case "until":
                    return StatementParser.parseWhileStatement(this);
                case "package":
                    return StatementParser.parsePackageDeclaration(this, token);
                case "use":
                case "no":
                    return StatementParser.parseUseDeclaration(this, token);
                case "sub":
                    // Must be followed by an identifier
                    tokenIndex++;
                    if (peek().type == LexerTokenType.IDENTIFIER) {
                        return SubroutineParser.parseSubroutineDefinition(this, true);
                    }
                    // otherwise backtrack
                    tokenIndex = currentIndex;
            }
        }
        if (token.type == LexerTokenType.OPERATOR
                && token.text.equals("{")
                && !isHashLiteral()) { // bare-block
            consume(LexerTokenType.OPERATOR, "{");
            Node block = parseBlock();
            consume(LexerTokenType.OPERATOR, "}");
            return block;
        }
        Node expression = parseExpression(0);
        token = peek();
        if (token.type == LexerTokenType.IDENTIFIER) {
            // statement modifier: if, for ...
            switch (token.text) {
                case "if":
                    consume();
                    Node modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    return new BinaryOperatorNode("&&", modifierExpression, expression, tokenIndex);
                case "unless":
                    consume();
                    modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    return new BinaryOperatorNode("||", modifierExpression, expression, tokenIndex);
                case "for":
                case "foreach":
                    consume();
                    modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    return new For1Node(
                            false,
                            new OperatorNode("$", new IdentifierNode("_", tokenIndex), tokenIndex),  // $_
                            modifierExpression,
                            expression,
                            tokenIndex);
                case "while":
                case "until":
                    consume();
                    modifierExpression = parseExpression(0);
                    parseStatementTerminator();
                    if (token.text.equals("until")) {
                        modifierExpression = new OperatorNode("not", modifierExpression, modifierExpression.getIndex());
                    }
                    return new For3Node(false, null, modifierExpression, null, expression, tokenIndex);
            }
            throw new PerlCompilerException(tokenIndex, "Not implemented: " + token, ctx.errorUtil);
        }
        parseStatementTerminator();
        return expression;
    }

    public void parseStatementTerminator() {
        LexerToken token = peek();
        if (token.type != LexerTokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        if (token.text.equals(";")) {
            consume();
        }
    }

    // disambiguate between Block or Hash literal
    public boolean isHashLiteral() {
        int currentIndex = tokenIndex;

        // Start after the opening '{'
        consume(LexerTokenType.OPERATOR, "{");

        int braceCount = 1; // Track nested braces
        bracesLoop:
        while (braceCount > 0) {
            LexerToken token = consume();
            ctx.logDebug("isHashLiteral " + token + " braceCount:" + braceCount);
            if (token.type == LexerTokenType.EOF) {
                break; // not a hash literal;
            }
            if (token.type == LexerTokenType.OPERATOR) {
                switch (token.text) {
                    case "{":
                    case "(":
                        braceCount++;
                        break;
                    case ")":
                    case "}":
                        braceCount--;
                        break;
                    case ",":
                    case "=>":
                        if (braceCount == 1) {
                            ctx.logDebug("isHashLiteral TRUE");
                            tokenIndex = currentIndex;
                            return true; // Likely a hash literal
                        }
                        break;
                    case ";":
                        if (braceCount == 1) {
                            break bracesLoop; // Likely a block
                        }
                        break;
                }
            }
        }
        ctx.logDebug("isHashLiteral FALSE");
        tokenIndex = currentIndex;
        return false;
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
            LexerToken token = peek();

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

            // If the operator is right associative (like exponentiation), parse it with lower precedence.
            if (RIGHT_ASSOC_OP.contains(token.text)) {
                ctx.logDebug("parseExpression `" + token.text + "` precedence: " + tokenPrecedence + " right assoc");
                left = parseInfix(left, tokenPrecedence - 1); // Parse the right side with lower precedence.
            } else {
                // Otherwise, parse it normally with the same precedence.
                ctx.logDebug("parseExpression `" + token.text + "` precedence: " + tokenPrecedence + " left assoc");
                left = parseInfix(left, tokenPrecedence);
            }
        }

        // Return the root node of the constructed expression tree.
        return left;
    }

    public Node parseCoreOperator(LexerToken token) {
        Node operand;

        switch (token.text) {
            case "__LINE__":
                return new NumberNode(Integer.toString(ctx.errorUtil.getLineNumber(tokenIndex)), tokenIndex);
            case "__FILE__":
                return new StringNode(ctx.compilerOptions.fileName, tokenIndex);
            case "__PACKAGE__":
                return new StringNode(ctx.symbolTable.getCurrentPackage(), tokenIndex);
            case "time":
            case "times":
            case "fork":
            case "wait":
            case "wantarray":
                // Handle operators with zero arguments
                return new OperatorNode(token.text, null, tokenIndex);
            case "not":
                // Handle 'not' keyword as a unary operator with an operand
                operand = parseExpression(getPrecedence(token.text) + 1);
                return new OperatorNode(token.text, operand, tokenIndex);
            case "abs":
            case "log":
            case "sqrt":
            case "cos":
            case "sin":
            case "exp":
            case "rand":
            case "undef":
            case "quotemeta":
            case "ref":
            case "oct":
            case "hex":
            case "pop":
            case "shift":
            case "sleep":
            case "int":
            case "chr":
            case "ord":
            case "fc":
            case "lc":
            case "lcfirst":
            case "uc":
            case "ucfirst":
            case "chop":
            case "chomp":
            case "length":
            case "defined":
            case "localtime":
            case "gmtime":
                String text = token.text;
                operand = ListParser.parseZeroOrOneList(this, 0);
                if (((ListNode) operand).elements.isEmpty()) {
                    switch (text) {
                        case "sleep":
                            operand = new NumberNode(Long.toString(Long.MAX_VALUE), tokenIndex);
                            break;
                        case "pop":
                        case "shift":
                            // create `@_` variable
                            // XXX in main program, use `@ARGV`
                            operand = new OperatorNode(
                                    "@", new IdentifierNode("_", tokenIndex), tokenIndex);
                            break;
                        case "localtime":
                        case "gmtime":
                            // empty list
                            break;
                        case "undef":
                            operand = null;
                            break;  // leave it empty
                        case "rand":
                            // create "1"
                            operand = new NumberNode("1", tokenIndex);
                            break;
                        default:
                            // create `$_` variable
                            operand = new OperatorNode(
                                    "$", new IdentifierNode("_", tokenIndex), tokenIndex);
                            break;
                    }
                }
                return new OperatorNode(text, operand, tokenIndex);
            case "rindex":
            case "index":
                operand = ListParser.parseZeroOrMoreList(this, 2, false, true, false, false);
                return new OperatorNode(token.text, operand, tokenIndex);
            case "atan2":
                operand = ListParser.parseZeroOrMoreList(this, 2, false, true, false, false);
                return new OperatorNode("atan2", operand, tokenIndex);
            case "bless":
                operand = ListParser.parseZeroOrMoreList(this, 1, false, true, false, false);
                Node ref = ((ListNode) operand).elements.get(0);
                Node className = ((ListNode) operand).elements.get(1);
                if (className == null) {
                    className = new StringNode("main", tokenIndex);
                }
                return new BinaryOperatorNode("bless", ref, className, tokenIndex);
            case "split":
                // TODO Handle 'split' keyword
                // RuntimeList split(RuntimeScalar quotedRegex, RuntimeScalar string, RuntimeScalar limitArg)
                operand = ListParser.parseZeroOrMoreList(this, 1, false, true, false, true);
                Node separator = ((ListNode) operand).elements.remove(0);
                if (separator instanceof OperatorNode) {
                    if (((OperatorNode) separator).operator.equals("matchRegex")) {
                        ((OperatorNode) separator).operator = "quoteRegex";
                    }
                }
                return new BinaryOperatorNode(token.text, separator, operand, tokenIndex);
            case "push":
            case "unshift":
            case "join":
            case "substr":
            case "sprintf":
                // Handle 'join' keyword as a Binary operator with a RuntimeList operand
                operand = ListParser.parseZeroOrMoreList(this, 1, false, true, false, false);
                separator = ((ListNode) operand).elements.remove(0);
                return new BinaryOperatorNode(token.text, separator, operand, tokenIndex);
            case "sort":
            case "map":
            case "grep":
                // Handle 'sort' keyword as a Binary operator with a Code and List operands
                operand = ListParser.parseZeroOrMoreList(this, 1, true, false, false, false);
                // transform:   { 123 }
                // into:        sub { 123 }
                Node block = ((ListNode) operand).handle;
                ((ListNode) operand).handle = null;
                if (block instanceof BlockNode) {
                    block = new AnonSubNode(null, null, null, block, false, tokenIndex);
                }
                return new BinaryOperatorNode(token.text, block, operand, tokenIndex);
            case "reverse":
            case "splice":
                operand = ListParser.parseZeroOrMoreList(this, 0, false, true, false, false);
                return new OperatorNode(token.text, operand, tokenIndex);
            case "readline":
            case "eof":
            case "tell":
            case "getc":
            case "open":
            case "close":
            case "seek":
                // Handle 'open' keyword as a Binary operator with a FileHandle and List operands
                operand = ListParser.parseZeroOrMoreList(this, 0, false, true, false, false);
                // Node handle = ((ListNode) operand).handle;
                // ((ListNode) operand).handle = null;
                Node handle = ((ListNode) operand).elements.remove(0);
                if (handle == null) {
                    handle = new IdentifierNode("main::STDOUT", tokenIndex);
                }
                return new BinaryOperatorNode(token.text, handle, operand, tokenIndex);
            case "printf":
            case "print":
            case "say":
                // Handle 'print' keyword as a Binary operator with a FileHandle and List operands
                operand = ListParser.parseZeroOrMoreList(this, 0, false, true, true, false);
                handle = ((ListNode) operand).handle;
                ((ListNode) operand).handle = null;
                if (handle == null) {
                    handle = new IdentifierNode("main::STDOUT", tokenIndex);
                }
                return new BinaryOperatorNode(token.text, handle, operand, tokenIndex);
            case "delete":
            case "exists":
                operand = ListParser.parseZeroOrOneList(this, 1);
                return new OperatorNode(token.text, operand, tokenIndex);
            case "scalar":
            case "values":
            case "keys":
            case "each":
                operand = parsePrimary();
                return new OperatorNode(token.text, operand, tokenIndex);
            case "our":
            case "my":
                // Handle 'my' keyword as a unary operator with an operand
                operand = parsePrimary();
                return new OperatorNode(token.text, operand, tokenIndex);
            case "return":
                // Handle 'return' keyword as a unary operator with an operand;
                // Parentheses are ignored.
                // operand = parseExpression(getPrecedence("print") + 1);
                operand = ListParser.parseZeroOrMoreList(this, 0, false, false, false, false);
                return new OperatorNode("return", operand, tokenIndex);
            case "eval":
                return parseEval();
            case "do":
                return parseDoOperator();
            case "require":
                return parseRequire();
            case "sub":
                // Handle 'sub' keyword to parse an anonymous subroutine
                return SubroutineParser.parseSubroutineDefinition(this, false);
            case "q":
            case "qq":
            case "qx":
            case "qw":
            case "qr":
            case "tr":
            case "y":
            case "s":
            case "m":
                // Handle special-quoted domain-specific arguments
                return StringParser.parseRawString(this, token.text);
        }
        return null;
    }

    private Node parseRequire() {
        LexerToken token;
        // Handle 'require' keyword which can be followed by a version, bareword or filename
        token = peek();
        Node operand;
        if (token.type == LexerTokenType.IDENTIFIER) {
            // TODO `require` version

            // `require` module
            String moduleName = IdentifierParser.parseSubroutineIdentifier(this);
            ctx.logDebug("name `" + moduleName + "`");
            if (moduleName == null) {
                throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
            }
            String fileName = ModuleLoader.moduleToFilename(moduleName);
            operand = ListNode.makeList(new StringNode(fileName, tokenIndex));
        } else {
            // `require` file
            operand = ListParser.parseZeroOrOneList(this, 1);
        }
        return new OperatorNode("require", operand, tokenIndex);
    }

    private Node parseDoOperator() {
        LexerToken token;
        Node block;
        // Handle 'do' keyword which can be followed by a block or filename
        token = peek();
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            consume(LexerTokenType.OPERATOR, "{");
            block = parseBlock();
            consume(LexerTokenType.OPERATOR, "}");
            return block;
        }
        // `do` file
        Node operand = ListParser.parseZeroOrOneList(this, 1);
        return new OperatorNode("doFile", operand, tokenIndex);
    }

    private AbstractNode parseEval() {
        Node block;
        Node operand;
        LexerToken token;
        // Handle 'eval' keyword which can be followed by a block or an expression
        token = peek();
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // If the next token is '{', parse a block
            consume(LexerTokenType.OPERATOR, "{");
            block = parseBlock();
            consume(LexerTokenType.OPERATOR, "}");
            // transform:  eval { 123 }
            // into:  sub { 123 }->()  with useTryCatch flag
            return new BinaryOperatorNode("->",
                    new AnonSubNode(null, null, null, block, true, tokenIndex), new ListNode(tokenIndex), tokenIndex);
        } else {
            // Otherwise, parse a primary expression
            operand = parsePrimary();
        }
        return new OperatorNode("eval", operand, tokenIndex);
    }

    public Node parsePrimary() {
        int startIndex = tokenIndex;
        LexerToken token = consume(); // Consume the next token from the input
        Node operand;

        switch (token.type) {
            case IDENTIFIER:
                LexerToken nextToken = peek();
                if (nextToken.text.equals("=>")) {
                    // Autoquote
                    return new StringNode(token.text, tokenIndex);
                }

                // Try to parse a builtin operation; backtrack if it fails
                if (token.text.equals("CORE") && nextToken.text.equals("::")) {
                    consume();  // "::"
                    token = consume(); // operator
                }
                Node operation = parseCoreOperator(token);
                if (operation != null) {
                    return operation;
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
                    case "'":
                    case "\"":
                    case "/":
                    case "//":
                    case "`":
                        // Handle single and double-quoted strings
                        return StringParser.parseRawString(this, token.text);
                    case "\\":
                        // Take reference
                        parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        parsingTakeReference = false;
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "$":
                    case "$#":
                    case "@":
                    case "%":
                    case "&":
                    case "*":
                        return parseVariable(token.text);
                    case "!":
                    case "~":
                    case "-":
                    case "+":
                    case "--":
                    case "++":
                        // Handle unary operators like `! + ++`
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        return new OperatorNode(token.text, operand, tokenIndex);
                }
                break;
            case EOF:
                // Handle end of input
                return null;
            default:
                // Throw an exception for any unexpected token
                throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, ctx.errorUtil);
        }
        // Throw an exception if no valid case was found
        throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, ctx.errorUtil);
    }

    /**
     * Parses a variable from the given lexer token.
     *
     * @param sigil The sigil that starts the variable.
     * @return The parsed variable node.
     * @throws PerlCompilerException If there is a syntax error.
     */
    private Node parseVariable(String sigil) {
        Node operand;
        String varName = IdentifierParser.parseComplexIdentifier(this);

        if (varName != null) {
            // Variable name is valid.
            // Check for illegal characters after a variable
            if (peek().text.equals("(") && !sigil.equals("&") && !parsingForLoopVariable) {
                // Parentheses are only allowed after a variable in specific cases:
                // - `for my $v (...`
                // - `&name(...`
                // - `obj->$name(...`
                throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
            }

            // Create a Variable node
            Node opNode = new OperatorNode(sigil, new IdentifierNode(varName, tokenIndex), tokenIndex);

            // Handle auto-call: transform `&subr` to `&subr(@_)`
            if (!peek().text.equals("(") && sigil.equals("&") && !parsingTakeReference) {
                Node list = new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex);
                return new BinaryOperatorNode("(", opNode, list, tokenIndex);
            }

            return opNode;
        } else if (peek().text.equals("{")) {
            // Handle curly brackets to parse a nested expression `${v}`
            consume(); // Consume the '{'
            Node block = parseBlock(); // Parse the block inside the curly brackets
            consume(LexerTokenType.OPERATOR, "}"); // Consume the '}'
            return new OperatorNode(sigil, block, tokenIndex);
        }

        // Not a variable name, not a block. This could be a dereference like @$a
        // Parse the expression with the appropriate precedence
        operand = parseExpression(getPrecedence(sigil) + 1);
        return new OperatorNode(sigil, operand, tokenIndex);
    }

    public Node parseInfix(Node left, int precedence) {
        LexerToken token = consume();

        Node right;

        if (INFIX_OP.contains(token.text)) {
            right = parseExpression(precedence);
            return new BinaryOperatorNode(token.text, left, right, tokenIndex);
        }

        switch (token.text) {
            case ",":
            case "=>":
                if (token.text.equals("=>") && left instanceof IdentifierNode) {
                    // Autoquote - Convert IdentifierNode to StringNode
                    left = new StringNode(((IdentifierNode) left).name, ((IdentifierNode) left).tokenIndex);
                }
                token = peek();
                if (token.type == LexerTokenType.EOF || LIST_TERMINATORS.contains(token.text) || token.text.equals(",") || token.text.equals("=>")) {
                    // "postfix" comma
                    return ListNode.makeList(left);
                }
                right = parseExpression(precedence);
                return ListNode.makeList(left, right);
            case "?":
                Node middle = parseExpression(0);
                consume(LexerTokenType.OPERATOR, ":");
                right = parseExpression(precedence);
                return new TernaryOperatorNode(token.text, left, middle, right, tokenIndex);
            case "->":
                String nextText = peek().text;
                switch (nextText) {
                    case "(":
                        consume();
                        right = new ListNode(ListParser.parseList(this, ")", 0), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
                    case "{":
                        consume();
                        right = new HashLiteralNode(parseHashSubscript(), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
                    case "[":
                        consume();
                        right = new ArrayLiteralNode(ListParser.parseList(this, "]", 1), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
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

    public LexerToken peek() {
        tokenIndex = skipWhitespace(tokenIndex, tokens);
        if (tokenIndex >= tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return tokens.get(tokenIndex);
    }

    public String comsumeChar() {
        String str;
        if (tokenIndex >= tokens.size()) {
            str = "";
        } else {
            LexerToken token = tokens.get(tokenIndex);
            if (token.text.length() == 1) {
                str = token.text;
                tokenIndex++;
            } else {
                str = token.text.substring(0, 1);
                token.text = token.text.substring(1);
            }
        }
        return str;
    }

    public LexerToken consume() {
        tokenIndex = skipWhitespace(tokenIndex, tokens);
        if (tokenIndex >= tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return tokens.get(tokenIndex++);
    }

    public LexerToken consume(LexerTokenType type) {
        LexerToken token = consume();
        if (token.type != type) {
            throw new PerlCompilerException(
                    tokenIndex, "Expected token " + type + " but got " + token, ctx.errorUtil);
        }
        return token;
    }

    public void consume(LexerTokenType type, String text) {
        LexerToken token = consume();
        if (token.type != type || !token.text.equals(text)) {
            throw new PerlCompilerException(
                    tokenIndex,
                    "Expected token " + type + " with text " + text + " but got " + token,
                    ctx.errorUtil);
        }
    }

    // List parsers

    public boolean looksLikeEmptyList() {
        boolean isEmptyList = false;
        int previousIndex = tokenIndex;
        LexerToken token = consume();
        LexerToken token1 = tokens.get(tokenIndex); // next token including spaces
        LexerToken nextToken = peek();  // after spaces

        if (token.type == LexerTokenType.EOF || LIST_TERMINATORS.contains(token.text)) {
            isEmptyList = true;
        } else if (INFIX_OP.contains(token.text) || token.text.equals(",")) {
            // tokenIndex++;
            ctx.logDebug("parseZeroOrMoreList infix `" + token.text + "` followed by `" + nextToken.text + "`");
            if (token.text.equals("%") && (nextToken.text.equals("$") || nextToken.type == LexerTokenType.IDENTIFIER)) {
                // looks like a hash deref, not an infix `%`
                ctx.logDebug("parseZeroOrMoreList looks like Hash");
            } else if (token.text.equals(".") && token1.type == LexerTokenType.NUMBER) {
                // looks like a fractional number, not an infix `.`
                ctx.logDebug("parseZeroOrMoreList looks like Number");
            } else {
                // subroutine call with zero arguments, followed by infix operator
                ctx.logDebug("parseZeroOrMoreList return zero at `" + tokens.get(tokenIndex) + "`");
                if (LVALUE_INFIX_OP.contains(token.text)) {
                    throw new PerlCompilerException(tokenIndex, "Can't modify non-lvalue subroutine call", ctx.errorUtil);
                }
                isEmptyList = true;
            }
        }
        tokenIndex = previousIndex;
        return isEmptyList;
    }

    public Node parseFileHandle() {
        boolean hasBracket = false;
        if (peek().text.equals("{")) {
            consume();
            hasBracket = true;
        }
        LexerToken token = peek();
        Node fileHandle = null;
        if (token.type == LexerTokenType.IDENTIFIER) {
            // bareword
            // Test for bareword like STDOUT, STDERR, FILE
            String name = IdentifierParser.parseSubroutineIdentifier(this);
            if (name != null) {
                String packageName = ctx.symbolTable.getCurrentPackage();
                if (name.equals("STDOUT") || name.equals("STDERR") || name.equals("STDIN")) {
                    packageName = "main";
                }
                name = NameCache.normalizeVariableName(name, packageName);
                if (GlobalContext.existsGlobalIO(name)) {
                    // FileHandle name exists
                    fileHandle = new IdentifierNode(name, tokenIndex);
                }
            }
        } else if (token.text.equals("$")) {
            // variable name
            fileHandle = parsePrimary();
            if (!hasBracket) {
                // assert that is not followed by infix
                String nextText = peek().text;
                if (INFIX_OP.contains(nextText) || "{[".contains(nextText) || "->".equals(nextText)) {
                    // print $fh + 2;  # not a file handle
                    fileHandle = null;
                }
                // assert that list is not empty
                if (looksLikeEmptyList()) {
                    // print $fh;  # not a file handle
                    fileHandle = null;
                }
            }
        }
        if (hasBracket) {
            consume(LexerTokenType.OPERATOR, "}");
        }
        return fileHandle;
    }

    public boolean isSpaceAfterPrintBlock() {
        int currentIndex = tokenIndex;
        LexerToken token = peek();
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
                    case "[":
                    case "\"":
                    case "//":
                    case "\\":
                    case "$":
                    case "$#":
                    case "@":
                    case "%":
                    case "&":
                    case "!":
                    case "~":
                    case "+":
                    case "-":
                    case "/":
                    case "*":
                    case ";":
                    case "++":
                    case "--":
                        isSpace = true;
                        break;
                    case ".":
                        // must be followed by NUMBER
                        consume();
                        if (tokens.get(tokenIndex).type == LexerTokenType.NUMBER) {
                            isSpace = true;
                        }
                        tokenIndex = currentIndex;
                        break;
                    case "(":
                    case "'":
                        // must have space before
                        consume();
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

        LexerToken ident = consume();
        LexerToken close = consume();
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

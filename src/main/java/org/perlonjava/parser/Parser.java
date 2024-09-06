package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalContext;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.*;

public class Parser {
    private static final Set<String> TERMINATORS =
            Set.of(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when");
    private static final Set<String> LIST_TERMINATORS =
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

    private int getPrecedence(String operator) {
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
                case "sub":
                    // Must be followed by an identifier
                    tokenIndex++;
                    if (peek().type == LexerTokenType.IDENTIFIER) {
                        return StatementParser.parseSubroutineDefinition(this, true);
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
     * Parses a subroutine call.
     *
     * @return A Node representing the parsed subroutine call.
     */
    private Node parseSubroutineCall() {
        // Parse the subroutine name as a complex identifier
        // Alternately, this could be a v-string like v10.20.30   XXX TODO

        String subName = IdentifierParser.parseSubroutineIdentifier(this);
        ctx.logDebug("SubroutineCall subName `" + subName + "` package " + ctx.symbolTable.getCurrentPackage());
        if (subName == null) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }

        // Normalize the subroutine name to include the current package
        String fullName = GlobalContext.normalizeVariableName(subName, ctx.symbolTable.getCurrentPackage());

        // Create an identifier node for the subroutine name
        IdentifierNode nameNode = new IdentifierNode(subName, tokenIndex);

        // Check if the subroutine exists in the global namespace
        boolean subExists = GlobalContext.existsGlobalCodeRef(fullName);
        String prototype = null;
        if (subExists) {
            // Fetch the subroutine reference
            RuntimeScalar codeRef = GlobalContext.getGlobalCodeRef(fullName);
            prototype = ((RuntimeCode) codeRef.value).prototype;
        }
        ctx.logDebug("SubroutineCall exists " + subExists + " prototype `" + prototype + "`");

        // Check if the subroutine call has parentheses
        boolean hasParentheses = peek().text.equals("(");
        if (!subExists && !hasParentheses) {
            // If the subroutine does not exist and there are no parentheses, it is not a subroutine call
            return nameNode;
        }

        // Handle the parameter list for the subroutine call
        Node arguments = null;
        if (prototype == null) {
            // no prototype
            arguments = parseZeroOrMoreList(0, false, true, false, false);
        } else if (prototype.isEmpty()) {
            // prototype is empty string
            arguments = new ListNode(tokenIndex);
        } else if (prototype.equals("$")) {
            // prototype is `$`
            arguments = parseZeroOrOneList(1);
        } else if (prototype.equals(";$")) {
            // prototype is `;$`
            arguments = parseZeroOrOneList(0);
        } else {
            // XXX TODO: Handle more prototypes or parameter variables
            arguments = parseZeroOrMoreList(0, false, true, false, false);
        }

        // Rewrite and return the subroutine call as `&name(arguments)`
        return new BinaryOperatorNode("(",
                new OperatorNode("&", nameNode, nameNode.tokenIndex),
                arguments,
                tokenIndex);
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
                switch (token.text) {
                    case "__LINE__":
                        return new NumberNode(Integer.toString(ctx.errorUtil.getLineNumber(tokenIndex)), tokenIndex);
                    case "__FILE__":
                        return new StringNode(ctx.compilerOptions.fileName, tokenIndex);
                    case "__PACKAGE__":
                        return new StringNode(ctx.symbolTable.getCurrentPackage(), tokenIndex);
                    case "time":
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
                    case "rand":
                    case "undef":
                    case "quotemeta":
                    case "ref":
                    case "pop":
                    case "shift":
                    case "int":
                    case "length":
                    case "defined":
                        String text = token.text;
                        operand = parseZeroOrOneList(0);
                        if (((ListNode) operand).elements.isEmpty()) {
                            switch (text) {
                                case "pop":
                                case "shift":
                                    // create `@_` variable
                                    // XXX in main program, use `@ARGV`
                                    operand = new OperatorNode(
                                            "@", new IdentifierNode("_", tokenIndex), tokenIndex);
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
                    case "bless":
                        operand = parseZeroOrMoreList(1, false, true, false, false);
                        Node ref = ((ListNode) operand).elements.get(0);
                        Node className = ((ListNode) operand).elements.get(1);
                        if (className == null) {
                            className = new StringNode("main", tokenIndex);
                        }
                        return new BinaryOperatorNode("bless", ref, className, tokenIndex);
                    case "split":
                        // TODO Handle 'split' keyword
                        // RuntimeList split(RuntimeScalar quotedRegex, RuntimeScalar string, RuntimeScalar limitArg)
                        operand = parseZeroOrMoreList(1, false, true, false, true);
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
                        operand = parseZeroOrMoreList(1, false, true, false, false);
                        separator = ((ListNode) operand).elements.remove(0);
                        return new BinaryOperatorNode(token.text, separator, operand, tokenIndex);
                    case "sort":
                    case "map":
                    case "grep":
                        // Handle 'sort' keyword as a Binary operator with a Code and List operands
                        operand = parseZeroOrMoreList(1, true, false, false, false);
                        // transform:   { 123 }
                        // into:        sub { 123 }
                        Node block = ((ListNode) operand).handle;
                        ((ListNode) operand).handle = null;
                        if (block instanceof BlockNode) {
                            block = new AnonSubNode(null, null, null, block, false, tokenIndex);
                        }
                        return new BinaryOperatorNode(token.text, block, operand, tokenIndex);
                    case "splice":
                        operand = parseZeroOrMoreList(0, false, true, false, false);
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "readline":
                    case "eof":
                    case "tell":
                    case "getc":
                    case "open":
                    case "close":
                    case "seek":
                        // Handle 'open' keyword as a Binary operator with a FileHandle and List operands
                        operand = parseZeroOrMoreList(0, false, true, false, false);
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
                        operand = parseZeroOrMoreList(0, false, true, true, false);
                        handle = ((ListNode) operand).handle;
                        ((ListNode) operand).handle = null;
                        if (handle == null) {
                            handle = new IdentifierNode("main::STDOUT", tokenIndex);
                        }
                        return new BinaryOperatorNode(token.text, handle, operand, tokenIndex);
                    case "delete":
                    case "exists":
                        operand = parseZeroOrOneList(1);
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "scalar":
                    case "values":
                    case "keys":
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
                        operand = parseZeroOrMoreList(0, false, false, false, false);
                        return new OperatorNode("return", operand, tokenIndex);
                    case "eval":
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
                    case "do":
                        // Handle 'do' keyword which can be followed by a block or filename
                        token = peek();
                        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
                            consume(LexerTokenType.OPERATOR, "{");
                            block = parseBlock();
                            consume(LexerTokenType.OPERATOR, "}");
                            return block;
                        }
                        break;
                    case "sub":
                        // Handle 'sub' keyword to parse an anonymous subroutine
                        return StatementParser.parseSubroutineDefinition(this, false);
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
                        return parseRawString(token.text);
                    default:
                        // Handle any other identifier as a subroutine call or identifier node
                        tokenIndex = startIndex;   // re-parse
                        return parseSubroutineCall();
                }
                break;
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
                        return new ListNode(parseList(")", 0), tokenIndex);
                    case "{":
                        // Handle curly brackets to parse a nested expression
                        return new HashLiteralNode(parseList("}", 0), tokenIndex);
                    case "[":
                        // Handle square brackets to parse a nested expression
                        return new ArrayLiteralNode(parseList("]", 0), tokenIndex);
                    case ".":
                        // Handle fractional numbers
                        return NumberParser.parseFractionalNumber(this);
                    case "'":
                    case "\"":
                    case "/":
                    case "//":
                    case "`":
                        // Handle single and double-quoted strings
                        return parseRawString(token.text);
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

    public Node parseRawString(String operator) {
        // handle special quotes for operators: q qq qx qw // s/// m//
        if (operator.equals("'") || operator.equals("\"") || operator.equals("/") || operator.equals("//")
                || operator.equals("`")) {
            tokenIndex--;   // will reparse the quote
        }
        StringParser.ParsedString rawStr;
        int stringParts = 1;
        switch (operator) {
            case "s":
            case "tr":
            case "y":
                stringParts = 3;    // s{str}{str}modifier
                break;
            case "m":
            case "qr":
            case "/":
            case "//":
                stringParts = 2;    // m{str}modifier
                break;
        }
        rawStr = StringParser.parseRawStrings(ctx, tokens, tokenIndex, stringParts);
        tokenIndex = rawStr.next;

        switch (operator) {
            case "`":
            case "qx":
                return StringParser.parseSystemCommand(ctx, operator, rawStr);
            case "'":
            case "q":
                return StringParser.parseSingleQuotedString(rawStr);
            case "m":
            case "qr":
            case "/":
            case "//":
                return StringParser.parseRegexMatch(ctx, operator, rawStr);
            case "s":
                return StringParser.parseRegexReplace(ctx, rawStr);
            case "\"":
            case "qq":
                return StringParser.parseDoubleQuotedString(ctx, rawStr, true);
            case "qw":
                return StringParser.parseWordsString(rawStr);
        }

        ListNode list = new ListNode(rawStr.index);
        int size = rawStr.buffers.size();
        for (int i = 0; i < size; i++) {
            list.elements.add(new StringNode(rawStr.buffers.get(i), rawStr.index));
        }
        return new OperatorNode(operator, list, rawStr.index);
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
                        right = new ListNode(parseList(")", 0), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
                    case "{":
                        consume();
                        right = new HashLiteralNode(parseList("}", 1), tokenIndex);
                        return new BinaryOperatorNode(token.text, left, right, tokenIndex);
                    case "[":
                        consume();
                        right = new ArrayLiteralNode(parseList("]", 1), tokenIndex);
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
                right = new ListNode(parseList(")", 0), tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, tokenIndex);
            case "{":
                right = new HashLiteralNode(parseList("}", 1), tokenIndex);
                return new BinaryOperatorNode(token.text, left, right, tokenIndex);
            case "[":
                right = new ArrayLiteralNode(parseList("]", 1), tokenIndex);
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

    // List parser for predeclared function calls with One optional argument,
    // accepts a list with Parentheses or without.
    //
    // Comma is allowed after the argument:   rand, rand 10,
    //
    private ListNode parseZeroOrOneList(int minItems) {
        if (looksLikeEmptyList()) {
            // return an empty list
            if (minItems > 0) {
                throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
            }
            return new ListNode(tokenIndex);
        }

        ListNode expr;
        LexerToken token = peek();
        if (token.text.equals("(")) {
            // argument in parentheses, can be 0 or 1 argument:    rand(), rand(10)
            // Commas are allowed after the single argument:       rand(10,)
            consume();
            expr = new ListNode(parseList(")", 0), tokenIndex);
            if (expr.elements.size() > 1) {
                throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
            }
        } else if (token.type == LexerTokenType.EOF || LIST_TERMINATORS.contains(token.text) || token.text.equals(",")) {
            // no argument
            expr = new ListNode(tokenIndex);
        } else {
            // argument without parentheses
            expr = ListNode.makeList(parseExpression(getPrecedence("isa") + 1));
        }
        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        return expr;
    }

    private boolean looksLikeEmptyList() {
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

    // List parser for predeclared function calls, accepts a list with Parentheses or without
    //
    // The Minimum number of arguments can be set.
    //
    // wantBlockNode:   sort { $a <=> $b } @array
    //
    // obeyParentheses: print ("this", "that"), "not printed"
    //
    // not obeyParentheses:  return ("this", "that"), "this too"
    //
    // wantFileHandle:  print STDOUT "this\n";
    //
    // wantRegex:  split / /, "this";
    //
    private ListNode parseZeroOrMoreList(int minItems, boolean wantBlockNode, boolean obeyParentheses, boolean wantFileHandle, boolean wantRegex) {
        ctx.logDebug("parseZeroOrMoreList start");
        ListNode expr = new ListNode(tokenIndex);

        int currentIndex = tokenIndex;
        boolean hasParen = false;
        LexerToken token;

        if (wantRegex) {
            boolean matched = false;
            if (peek().text.equals("(")) {
                consume();
                hasParen = true;
            }
            if (peek().text.equals("/") || peek().text.equals("//")) {
                consume();
                Node regex = parseRawString("/");
                if (regex != null) {
                    matched = true;
                    expr.elements.add(regex);
                    token = peek();
                    if (token.type != LexerTokenType.EOF && !LIST_TERMINATORS.contains(token.text)) {
                        // consume comma
                        consume(LexerTokenType.OPERATOR, ",");
                    }
                }
            }
            if (!matched) {
                // backtrack
                tokenIndex = currentIndex;
                hasParen = false;
            }
        }

        if (wantFileHandle) {
            if (peek().text.equals("(")) {
                consume();
                hasParen = true;
            }
            expr.handle = parseFileHandle();
            if (expr.handle == null || !isSpaceAfterPrintBlock()) {
                // backtrack
                tokenIndex = currentIndex;
                hasParen = false;
            }
        }

        if (wantBlockNode) {
            if (peek().text.equals("(")) {
                consume();
                hasParen = true;
            }
            if (peek().text.equals("{")) {
                consume();
                expr.handle = parseBlock();
                consume(LexerTokenType.OPERATOR, "}");
            }
            if (!isSpaceAfterPrintBlock() || looksLikeEmptyList()) {
                throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
            }
        }

        if (!looksLikeEmptyList()) {
            // it doesn't look like an empty list
            token = peek();
            if (obeyParentheses && token.text.equals("(")) {
                // arguments in parentheses, can be 0 or more arguments:    print(), print(10)
                // Commas are allowed after the arguments:       print(10,)
                consume();
                expr.elements.addAll(parseList(")", 0));
            } else {
                while (token.type != LexerTokenType.EOF && !LIST_TERMINATORS.contains(token.text)) {
                    // Argument without parentheses
                    expr.elements.add(parseExpression(getPrecedence(",")));
                    token = peek();
                    if (token.text.equals(",") || token.text.equals("=>")) {
                        while (token.text.equals(",") || token.text.equals("=>")) {
                            consume();
                            token = peek();
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        if (hasParen) {
            consume(LexerTokenType.OPERATOR, ")");
        }
        ctx.logDebug("parseZeroOrMoreList end: " + expr);

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        return expr;
    }

    private Node parseFileHandle() {
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
                name = GlobalContext.normalizeVariableName(name, packageName);
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

    private boolean isSpaceAfterPrintBlock() {
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

    // Generic List parser for Parentheses, Hash literal, Array literal,
    // function arguments, get Array element, get Hash element.
    //
    // The Minimum number of arguments can be set.
    //
    // Example usage:
    //
    //    new ListNode(parseList(")", 0), tokenIndex);
    //    new HashLiteralNode(parseList("}", 1), tokenIndex);
    //    new ArrayLiteralNode(parseList("]", 1), tokenIndex);
    //
    private List<Node> parseList(String close, int minItems) {
        ctx.logDebug("parseList start");
        ListNode expr;

        LexerToken token = peek();
        ctx.logDebug("parseList start at " + token);
        if (token.text.equals(close)) {
            // empty list
            consume();
            expr = new ListNode(tokenIndex);
        } else {
            expr = ListNode.makeList(parseExpression(0));
            ctx.logDebug("parseList end at " + peek());
            consume(LexerTokenType.OPERATOR, close);
        }

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        ctx.logDebug("parseList end");

        return expr.elements;
    }
}

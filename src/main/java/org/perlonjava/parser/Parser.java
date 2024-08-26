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
            new HashSet<>(Arrays.asList(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when"));
    private static final Set<String> LISTTERMINATORS =
            new HashSet<>(Arrays.asList(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when", "not", "and", "or"));
    private static final Set<String> UNARY_OP =
            new HashSet<>(Arrays.asList("!", "~", "\\", "-", "+", "--", "++", "$", "@", "%", "*", "&", "$#"));
    private static final Set<String> INFIX_OP = new HashSet<>(Arrays.asList(
            "or", "xor", "and", "||", "//", "&&", "|", "^", "&",
            "==", "!=", "<=>", "eq", "ne", "cmp", "<", ">", "<=",
            ">=", "lt", "gt", "le", "ge", "<<", ">>", "+", "-", "*",
            "**", "/", "%", ".", "=", "**=", "+=", "*=", "&=", "&.=",
            "<<=", "&&=", "-=", "/=", "|=", "|.=", ">>=", "||=", ".=",
            "%=", "^=", "^.=", "//=", "x=", "=~", "!~", "x", "..", "..."
    ));
    private static final Set<String> LVALUE_INFIX_OP = new HashSet<>(Arrays.asList(
            "=", "**=", "+=", "*=", "&=", "&.=",
            "<<=", "&&=", "-=", "/=", "|=", "|.=",
            ">>=", "||=", ".=", "%=", "^=", "^.=",
            "//=", "x="
    ));

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
        addOperatorsToMap(16, ">>", "<<");
        addOperatorsToMap(17, "+", "-");
        addOperatorsToMap(18, "*", "/", "%", "x");
        addOperatorsToMap(19, "=~", "!~");
        addOperatorsToMap(20, "!", "~", "\\");
        addOperatorsToMap(21, "**");
        addOperatorsToMap(22, "++", "--");
        addOperatorsToMap(23, "->");
    }

    private final List<LexerToken> tokens;
    private final EmitterContext ctx;
    public int tokenIndex = 0;
    private boolean parsingForLoopVariable = false;
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

    public static boolean isSigil(String s) {
        switch (s) {
            case "$":
            case "@":
            case "%":
                return true;
            default:
                return false;
        }
    }

    private int getPrecedence(String operator) {
        return precedenceMap.getOrDefault(operator, 24);
    }

    public Node parse() {
        return parseBlock();
    }

    private BlockNode parseBlock() {
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
        LexerToken token = peek();
        ctx.logDebug("parseStatement `" + token.text + "`");

        if (token.type == LexerTokenType.IDENTIFIER) {
            switch (token.text) {
                case "if":
                case "unless":
                    return parseIfStatement();
                case "for":
                case "foreach":
                    return parseForStatement();
                case "while":
                case "until":
                    return parseWhileStatement();
                case "package":
                    consume();
                    String packageName = parseSubroutineIdentifier();
                    if (packageName == null) {
                        throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
                    }
                    IdentifierNode nameNode = new IdentifierNode(packageName, tokenIndex);
                    OperatorNode packageNode = new OperatorNode(token.text, nameNode, tokenIndex);

                    // Parse Version string; throw away the result
                    // XXX use the Version string
                    parseOptionalPackageVersion();

                    BlockNode block = parseOptionalPackageBlock(nameNode, packageNode);
                    if (block != null) return block;

                    parseStatementTerminator();
                    ctx.symbolTable.setCurrentPackage(nameNode.name);
                    return packageNode;
                case "sub":
                    consume();
                    return parseSubroutineDefinition(true);

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

    private void parseStatementTerminator() {
        LexerToken token = peek();
        if (token.type != LexerTokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        if (token.text.equals(";")) {
            consume();
        }
    }

    private BlockNode parseOptionalPackageBlock(IdentifierNode nameNode, OperatorNode packageNode) {
        LexerToken token;
        token = peek();
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // package NAME BLOCK
            consume(LexerTokenType.OPERATOR, "{");
            ctx.symbolTable.enterScope();
            ctx.symbolTable.setCurrentPackage(nameNode.name);
            BlockNode block = parseBlock();

            // Insert packageNode as first statement in block
            block.elements.add(0, packageNode);

            ctx.symbolTable.exitScope();
            consume(LexerTokenType.OPERATOR, "}");
            return block;
        }
        return null;
    }

    private void parseOptionalPackageVersion() {
        LexerToken token;
        token = peek();
        if (token.type == LexerTokenType.NUMBER) {
            parseNumber(consume());
        } else if (token.text.startsWith("v")) {
            // parseDottedDecimalVersion
            StringBuilder version = new StringBuilder(token.text); // start with 'v'
            consume();

            int componentCount = 0;

            // Loop through components separated by '.'
            while (true) {
                if (!peek().text.equals(".")) {
                    if (componentCount < 2) { // Ensures at least 3 components (v1.2.3)
                        throw new PerlCompilerException(tokenIndex, "Dotted-decimal version must have at least 3 components", ctx.errorUtil);
                    } else {
                        break; // Stop if there's no '.' and we have enough components
                    }
                }

                version.append(consume().text); // consume '.'

                if (peek().type == LexerTokenType.NUMBER) {
                    version.append(consume().text); // consume number
                    componentCount++;
                } else {
                    throw new PerlCompilerException(tokenIndex, "Invalid dotted-decimal format", ctx.errorUtil);
                }
            }

            ctx.logDebug("Dotted-decimal Version: " + version);
        }
    }

    // disambiguate between Block or Hash literal
    private boolean isHashLiteral() {
        int index = tokenIndex + 1; // Start after the opening '{'
        int braceCount = 1; // Track nested braces
        while (braceCount > 0) {
            LexerToken token = tokens.get(index++);
            if (token.type == LexerTokenType.EOF) {
                return false; // not a hash literal;
            }
            if (token.type == LexerTokenType.OPERATOR) {
                switch (token.text) {
                    case "{":
                        braceCount++;
                        break;
                    case "}":
                        braceCount--;
                        break;
                    case ",":
                    case "=>":
                        return true; // Likely a hash literal
                    case ";":
                        return false; // Likely a block
                }
            }
        }
        return false;
    }

    private Node parseSubroutineDefinition(boolean wantName) {
        // This method is responsible for parsing an anonymous subroutine (a subroutine without a name)
        // or a named subroutine based on the 'wantName' flag.

        // Initialize the subroutine name to null. This will store the name of the subroutine if 'wantName' is true.
        String subName = null;

        // If the 'wantName' flag is true and the next token is an identifier, we parse the subroutine name.
        if (wantName && peek().type == LexerTokenType.IDENTIFIER) {
            // 'parseSubroutineIdentifier' is called to handle cases where the subroutine name might be complex
            // (e.g., namespaced, fully qualified names). It may return null if no valid name is found.
            subName = parseSubroutineIdentifier();
        }

        // Initialize the prototype node to null. This will store the prototype of the subroutine if it exists.
        String prototype = null;

        // Check if the next token is an opening parenthesis '(' indicating a prototype.
        if (peek().text.equals("(")) {
            // If a prototype exists, we parse it using 'parseRawString' method which handles it like the 'q()' operator.
            // This means it will take everything inside the parentheses as a literal string.
            prototype = ((StringNode) parseRawString("q")).value;
        }

        // Initialize a list to store any attributes the subroutine might have.
        List<String> attributes = new ArrayList<>();

        // While there are attributes (denoted by a colon ':'), we keep parsing them.
        while (peek().text.equals(":")) {
            // Consume the colon operator.
            consume(LexerTokenType.OPERATOR, ":");
            // Consume the attribute name (an identifier) and add it to the attributes list.
            attributes.add(consume(LexerTokenType.IDENTIFIER).text);
        }

        // After parsing name, prototype, and attributes, we expect an opening curly brace '{' to denote the start of the subroutine block.
        consume(LexerTokenType.OPERATOR, "{");

        // Parse the block of the subroutine, which contains the actual code.
        Node block = parseBlock();

        // After the block, we expect a closing curly brace '}' to denote the end of the subroutine.
        consume(LexerTokenType.OPERATOR, "}");

        // Now we check if the next token is one of the illegal characters that cannot follow a subroutine.
        // These are '(', '{', and '['. If any of these follow, we throw a syntax error.
        LexerToken token = peek();
        if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
            // Throw an exception indicating a syntax error.
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }

        // Finally, we create a new 'AnonSubNode' object with the parsed data: the name, prototype, attributes, block,
        // `useTryCatch` flag, and token position.
        AnonSubNode anonSubNode = new AnonSubNode(subName, prototype, attributes, block, false, tokenIndex);

        if (subName != null) {
            // Additional steps for named subroutine:
            // - register the subroutine in the namespace
            // - add the typeglob assignment:  *name = sub () :attr {...}

            // register the named subroutine
            String fullName = GlobalContext.normalizeVariableName(subName, ctx.symbolTable.getCurrentPackage());
            RuntimeCode codeRef = new RuntimeCode(prototype);
            GlobalContext.getGlobalCodeRef(fullName).set(new RuntimeScalar(codeRef));

            // return typeglob assignment
            return new BinaryOperatorNode("=",
                    new OperatorNode("*",
                            new IdentifierNode(fullName, tokenIndex),
                            tokenIndex),
                    anonSubNode,
                    tokenIndex);
        }

        // return anonymous subroutine
        return anonSubNode;
    }


    private Node parseWhileStatement() {
        LexerToken operator = consume(LexerTokenType.IDENTIFIER); // "while" "until"

        consume(LexerTokenType.OPERATOR, "(");
        Node condition = parseExpression(0);
        consume(LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        consume(LexerTokenType.OPERATOR, "{");
        Node body = parseBlock();
        consume(LexerTokenType.OPERATOR, "}");

        if (operator.text.equals("until")) {
            condition = new OperatorNode("not", condition, condition.getIndex());
        }
        return new For3Node(true, null, condition, null, body, tokenIndex);
    }

    private Node parseForStatement() {
        consume(LexerTokenType.IDENTIFIER); // "for" "foreach"

        Node varNode = null;
        LexerToken token = peek(); // "my" "$" "("
        if (token.text.equals("my") || token.text.equals("$")) {
            parsingForLoopVariable = true;
            varNode = parsePrimary();
            parsingForLoopVariable = false;
        }

        consume(LexerTokenType.OPERATOR, "(");

        // Parse the initialization part
        Node initialization = null;
        if (!peek().text.equals(";")) {
            initialization = parseExpression(0);

            token = peek();
            if (token.text.equals(")")) {
                // 1-argument for
                consume();

                // Parse the body of the loop
                consume(LexerTokenType.OPERATOR, "{");
                Node body = parseBlock();
                consume(LexerTokenType.OPERATOR, "}");

                if (varNode == null) {
                    varNode = new OperatorNode(
                            "$", new IdentifierNode("_", tokenIndex), tokenIndex);  // $_
                }
                return new For1Node(true, varNode, initialization, body, tokenIndex);
            }
        }
        // 3-argument for
        if (varNode != null) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        consume(LexerTokenType.OPERATOR, ";");

        // Parse the condition part
        Node condition = null;
        if (!peek().text.equals(";")) {
            condition = parseExpression(0);
        }
        consume(LexerTokenType.OPERATOR, ";");

        // Parse the increment part
        Node increment = null;
        if (!peek().text.equals(")")) {
            increment = parseExpression(0);
        }
        consume(LexerTokenType.OPERATOR, ")");

        // Parse the body of the loop
        consume(LexerTokenType.OPERATOR, "{");
        Node body = parseBlock();
        consume(LexerTokenType.OPERATOR, "}");

        return new For3Node(true, initialization, condition, increment, body, tokenIndex);
    }

    /**
     * Parses a subroutine call in the code.
     *
     * @return A Node representing the parsed subroutine call.
     */
    private Node parseSubroutineCall() {
        // Parse the subroutine name as a complex identifier
        // Alternately, this could be a v-string like v10.20.30   XXX TODO

        String subName = parseSubroutineIdentifier();
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
            arguments = parseZeroOrMoreList(0);
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
            arguments = parseZeroOrMoreList(0);
        }

        // Rewrite and return the subroutine call as `&name(arguments)`
        return new BinaryOperatorNode("(",
                new OperatorNode("&", nameNode, nameNode.tokenIndex),
                arguments,
                tokenIndex);
    }

    private Node parseIfStatement() {
        LexerToken operator = consume(LexerTokenType.IDENTIFIER); // "if", "unless", "elsif"
        consume(LexerTokenType.OPERATOR, "(");
        Node condition = parseExpression(0);
        consume(LexerTokenType.OPERATOR, ")");
        consume(LexerTokenType.OPERATOR, "{");
        Node thenBranch = parseBlock();
        consume(LexerTokenType.OPERATOR, "}");
        Node elseBranch = null;
        LexerToken token = peek();
        if (token.text.equals("else")) {
            consume(LexerTokenType.IDENTIFIER); // "else"
            consume(LexerTokenType.OPERATOR, "{");
            elseBranch = parseBlock();
            consume(LexerTokenType.OPERATOR, "}");
        } else if (token.text.equals("elsif")) {
            elseBranch = parseIfStatement();
        }
        return new IfNode(operator.text, condition, thenBranch, elseBranch, tokenIndex);
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
            if (isRightAssociative(token.text)) {
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

    private Node parsePrimary() {
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
                    case "not":
                        // Handle 'not' keyword as a unary operator with an operand
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        return new OperatorNode("not", operand, tokenIndex);
                    case "abs":
                    case "log":
                    case "rand":
                    case "undef":
                        String text = token.text;
                        operand = parseZeroOrOneList(0);
                        if (((ListNode) operand).elements.isEmpty()) {
                            switch (text) {
                                case "undef":
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
                    case "join":
                        // Handle 'join' keyword as a Binary operator with a RuntimeList operand
                        operand = parseZeroOrMoreList(1);
                        Node separator = ((ListNode) operand).elements.remove(0);
                        return new BinaryOperatorNode("join", separator, operand, tokenIndex);
                    case "print":
                    case "say":
                        // Handle 'say' keyword as a unary operator with a RuntimeList operand
                        operand = parseZeroOrMoreList(0);
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "scalar":
                    case "values":
                    case "keys":
                        operand = parseZeroOrOneList(1);
                        return new OperatorNode(token.text, ((ListNode) operand).elements.get(0), tokenIndex);
                    case "our":
                    case "my":
                        // Handle 'my' keyword as a unary operator with an operand
                        operand = parsePrimary();
                        return new OperatorNode(token.text, operand, tokenIndex);
                    case "return":
                        // Handle 'return' keyword as a unary operator with an operand;
                        // Parenthensis are ignored.
                        operand = parseExpression(getPrecedence("print") + 1);
                        return new OperatorNode("return", operand, tokenIndex);
                    case "eval":
                        // Handle 'eval' keyword which can be followed by a block or an expression
                        token = peek();
                        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
                            // If the next token is '{', parse a block
                            consume(LexerTokenType.OPERATOR, "{");
                            Node block = parseBlock();
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
                            Node block = parseBlock();
                            consume(LexerTokenType.OPERATOR, "}");
                            return block;
                        }
                        break;
                    case "sub":
                        // Handle 'sub' keyword to parse an anonymous subroutine
                        return parseSubroutineDefinition(false);
                    case "q":
                    case "qq":
                    case "qx":
                    case "qw":
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
                return parseNumber(token);
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
                        return parseFractionalNumber();
                    case "'":
                    case "\"":
                    case "/":
                    case "//":
                        // Handle single and double-quoted strings
                        return parseRawString(token.text);
                    case "\\":
                        // Take reference
                        parsingTakeReference = true;    // don't call `&subr` while parsing "Take reference"
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        parsingTakeReference = false;
                        return new OperatorNode(token.text, operand, tokenIndex);
                    default:
                        // Handle unary operators like `! + ++` and sigils `$ @ % * *`
                        if (UNARY_OP.contains(token.text)) {
                            String text = token.text;
                            if (isSigil(text) || text.equals("&") || text.equals("*")) {
                                String varName = parseComplexIdentifier();
                                if (varName != null) {
                                    // some characters are illegal after a variable
                                    if (peek().text.equals("(") && !text.equals("&") && !parsingForLoopVariable) {
                                        // parentheses is only allowed after a variable in these cases:
                                        //  `for my $v (...`
                                        //  `&name(...
                                        throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
                                    }
                                    // create a Variable
                                    Node opNode = new OperatorNode(
                                            text, new IdentifierNode(varName, tokenIndex), tokenIndex);
                                    if (!peek().text.equals("(") && text.equals("&") && !parsingTakeReference) {
                                        // allow `&subr` to "auto-call"
                                        // rewrite to `&subr(@_)`
                                        Node list = new OperatorNode("@", new IdentifierNode("_", tokenIndex), tokenIndex);
                                        return new BinaryOperatorNode(
                                                "(",
                                                opNode, list, tokenIndex);
                                    }
                                    return opNode;
                                } else if (peek().text.equals("{")) {
                                    // Handle curly brackets to parse a nested expression
                                    //  `${v}`
                                    consume();
                                    return new OperatorNode(
                                            text, new HashLiteralNode(parseList("}", 1), tokenIndex), tokenIndex);
                                }
                            }
                            operand = parseExpression(getPrecedence(text) + 1);
                            return new OperatorNode(text, operand, tokenIndex);
                        }
                        break;
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
     * Parses a complex Perl identifier from the list of tokens, excluding the sigil.
     *
     * @return The parsed identifier as a String, or null if there is no valid identifier.
     */
    public String parseComplexIdentifier() {
        // Save the current token index to allow backtracking if needed
        int saveIndex = tokenIndex;

        // Skip any leading whitespace
        tokenIndex = skipWhitespace(tokenIndex, tokens);

        // Check if the identifier is enclosed in braces
        boolean insideBraces = false;
        if (tokens.get(tokenIndex).text.equals("{")) {
            insideBraces = true;
            tokenIndex++; // Consume the opening brace
        }

        // Parse the identifier using the inner method
        String identifier = parseComplexIdentifierInner();

        // If an identifier was found and it was inside braces, ensure the braces are properly closed
        if (identifier != null && insideBraces) {
            // Skip any whitespace after the identifier
            tokenIndex = skipWhitespace(tokenIndex, tokens);

            // Check for the closing brace
            if (tokens.get(tokenIndex).text.equals("}")) {
                tokenIndex++; // Consume the closing brace
                return identifier;
            } else {
                // If the closing brace is not found, backtrack to the saved index
                // This indicates that we found `${expression}` instead of `${identifier}`
                tokenIndex = saveIndex;
                return null;
            }
        }

        // Return the parsed identifier, or null if no valid identifier was found
        return identifier;
    }

    private String parseComplexIdentifierInner() {
        tokenIndex = skipWhitespace(tokenIndex, tokens);

        boolean isFirstToken = true;
        StringBuilder variableName = new StringBuilder();

        LexerToken token = tokens.get(tokenIndex);
        LexerToken nextToken = tokens.get(tokenIndex + 1);
        while (true) {
            if (token.type == LexerTokenType.OPERATOR || token.type == LexerTokenType.NUMBER || token.type == LexerTokenType.STRING) {
                if (token.text.equals("$") && (nextToken.text.equals("$")
                        || nextToken.type == LexerTokenType.IDENTIFIER
                        || nextToken.type == LexerTokenType.NUMBER)) {
                    // `@$` `$$` can't be followed by `$` or name or number
                    return null;
                }
                if (token.text.equals("^") && nextToken.type == LexerTokenType.IDENTIFIER && Character.isUpperCase(nextToken.text.charAt(0))) {
                    // `$^` can be followed by an optional uppercase identifier: `$^A`
                    variableName.append(token.text);
                    variableName.append(nextToken.text);
                    tokenIndex += 2;
                    return variableName.toString();
                }
                if (isFirstToken && token.type == LexerTokenType.NUMBER) {
                    // finish because $1 can't be followed by `::`
                    variableName.append(token.text);
                    tokenIndex++;
                    return variableName.toString();
                }
                if (!token.text.equals("::") && !(token.type == LexerTokenType.NUMBER)) {
                    // `::` or number can continue the loop
                    // XXX STRING token type needs more work (Unicode, control characters)
                    variableName.append(token.text);
                    tokenIndex++;
                    return variableName.toString();
                }
            } else if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.EOF || token.type == LexerTokenType.NEWLINE) {
                return variableName.toString();
            }
            isFirstToken = false;
            variableName.append(token.text);

            if ((token.type == LexerTokenType.IDENTIFIER || token.type == LexerTokenType.NUMBER)
                    && (!nextToken.text.equals("::"))
            ) {
                tokenIndex++;
                return variableName.toString();
            }

            tokenIndex++;
            token = tokens.get(tokenIndex);
            nextToken = tokens.get(tokenIndex + 1);
        }
    }

    private String parseSubroutineIdentifier() {
        tokenIndex = skipWhitespace(tokenIndex, tokens);
        StringBuilder variableName = new StringBuilder();
        LexerToken token = tokens.get(tokenIndex);
        LexerToken nextToken = tokens.get(tokenIndex + 1);
        if (token.type == LexerTokenType.NUMBER) {
            return null;
        }
        while (true) {
            if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.EOF || token.type == LexerTokenType.NEWLINE || (token.type == LexerTokenType.OPERATOR && !token.text.equals("::"))) {
                return variableName.toString();
            }
            variableName.append(token.text);
            if ((token.type == LexerTokenType.IDENTIFIER || token.type == LexerTokenType.NUMBER)
                    && (!nextToken.text.equals("::"))
            ) {
                tokenIndex++;
                return variableName.toString();
            }
            tokenIndex++;
            token = tokens.get(tokenIndex);
            nextToken = tokens.get(tokenIndex + 1);
        }
    }

    private Node parseRawString(String operator) {
        // handle special quotes for operators: q qq qx qw // s/// m//
        if (operator.equals("'") || operator.equals("\"") || operator.equals("/") || operator.equals("//")) {
            tokenIndex--;   // will re-parse the quote
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
            case "/":
            case "//":
                stringParts = 2;    // m{str}modifier
                break;
        }
        rawStr = StringParser.parseRawStrings(ctx, tokens, tokenIndex, stringParts);
        tokenIndex = rawStr.next;

        switch (operator) {
            case "'":
            case "q":
                return StringParser.parseSingleQuotedString(rawStr.buffers.get(0), rawStr.startDelim, rawStr.endDelim, rawStr.index);
            case "\"":
            case "qq":
                return StringParser.parseDoubleQuotedString(ctx, rawStr.buffers.get(0), ctx.errorUtil, rawStr.index);
            case "qw":
                // Use a regular expression to split the string.
                // " +" matches one or more ASCII space characters
                String[] words = rawStr.buffers.get(0).trim().split(" +");
                ListNode list = new ListNode(rawStr.index);
                int size = words.length;
                for (int i = 0; i < size; i++) {
                    list.elements.add(new StringNode(words[i], rawStr.index));
                }
                return list;
        }

        ListNode list = new ListNode(rawStr.index);
        int size = rawStr.buffers.size();
        for (int i = 0; i < size; i++) {
            list.elements.add(new StringNode(rawStr.buffers.get(i), rawStr.index));
        }
        return new OperatorNode(operator, list, rawStr.index);
    }

    private Node parseNumber(LexerToken token) {
        StringBuilder number = new StringBuilder(token.text);

        // Check for fractional part
        if (tokens.get(tokenIndex).text.equals(".")) {
            number.append(consume().text); // consume '.'
            if (tokens.get(tokenIndex).type == LexerTokenType.NUMBER) {
                number.append(consume().text); // consume digits after '.'
            }
        }
        // Check for exponent part
        checkNumberExponent(number);

        return new NumberNode(number.toString(), tokenIndex);
    }

    private Node parseFractionalNumber() {
        StringBuilder number = new StringBuilder("0.");

        LexerToken token = tokens.get(tokenIndex++);
        if (token.type != LexerTokenType.NUMBER) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }

        number.append(token.text); // consume digits after '.'
        // Check for exponent part
        checkNumberExponent(number);
        return new NumberNode(number.toString(), tokenIndex);
    }

    private void checkNumberExponent(StringBuilder number) {
        // Check for exponent part
        String exponentPart = peek().text;
        if (exponentPart.startsWith("e")
                || exponentPart.startsWith("E")) {
            consume(); // consume 'e' or 'E' and possibly more 'E10'

            // Check if the rest of the token contains digits (e.g., "E10")
            int index = 1;
            for (; index < exponentPart.length(); index++) {
                if (!Character.isDigit(exponentPart.charAt(index)) && exponentPart.charAt(index) != '_') {
                    throw new PerlCompilerException(tokenIndex, "Malformed number", ctx.errorUtil);
                }
            }
            number.append(exponentPart);

            // If the exponent part was not fully consumed, check for separate tokens
            if (index == 1) {
                // Check for optional sign
                if (tokens.get(tokenIndex).text.equals("-") || tokens.get(tokenIndex).text.equals("+")) {
                    number.append(consume().text); // consume '-' or '+'
                }

                // Consume exponent digits
                number.append(consume(LexerTokenType.NUMBER).text);
            }
        }
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
                if (token.type == LexerTokenType.EOF || LISTTERMINATORS.contains(token.text) || token.text.equals(",") || token.text.equals("=>")) {
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
                right = parseExpression(precedence);
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

    private boolean isRightAssociative(String s) {
        // Define right associative operators
        switch (s) {
            case "=":
            case "**=":
            case "+=":
            case "*=":
            case "&=":
            case "&.=":
            case "<<=":
            case "&&=":
            case "-=":
            case "/=":
            case "|=":
            case "|.=":
            case ">>=":
            case "||=":
            case ".=":
            case "%=":
            case "^=":
            case "^.=":
            case "//=":
            case "x=":
            case "**":
            case "?":
                return true;
            default:
                return false;
        }
    }

    // List parsers

    // List parser for predeclared function calls with One optional argument,
    // accepts a list with Parentheses or without.
    //
    // Comma is allowed after the argument:   rand, rand 10,
    //
    private ListNode parseZeroOrOneList(int minItems) {
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
        } else if (token.type == LexerTokenType.EOF || LISTTERMINATORS.contains(token.text) || token.text.equals(",")) {
            // no argument
            expr = new ListNode(tokenIndex);
        } else {
            // argument without parentheses
            expr = ListNode.makeList(parseExpression(getPrecedence(",") + 1));
        }
        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        return expr;
    }

    // List parser for predeclared function calls, accepts a list with Parentheses or without
    //
    // The Minimum number of arguments can be set.
    //
    private ListNode parseZeroOrMoreList(int minItems) {
        int previousIndex = tokenIndex;
        LexerToken token = consume();
        LexerToken token1 = tokens.get(tokenIndex); // next token including spaces
        LexerToken nextToken = peek();  // after spaces

        if (INFIX_OP.contains(token.text) || token.text.equals(",")) {
            // tokenIndex++;
            ctx.logDebug("parseZeroOrMoreList infix `" + token.text + "` followed by `" + nextToken.text + "`");
            if (token.text.equals("%") && nextToken.text.equals("$")) {
                // looks like a hash deref, not an infix `%`
                ctx.logDebug("parseZeroOrMoreList looks like Hash");
            } else if (token.text.equals(".") && token1.type == LexerTokenType.NUMBER) {
                // looks like a fractional number, not an infix `.`
                ctx.logDebug("parseZeroOrMoreList looks like Number");
            } else {
                // subroutine call with zero arguments, followed by infix operator
                tokenIndex = previousIndex;
                ctx.logDebug("parseZeroOrMoreList return zero at `" + tokens.get(tokenIndex) + "`");
                if (LVALUE_INFIX_OP.contains(token.text)) {
                    throw new PerlCompilerException(tokenIndex, "Can't modify non-lvalue subroutine call", ctx.errorUtil);
                }

                // return an empty list
                if (minItems > 0) {
                    throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
                }
                return new ListNode(tokenIndex);
            }
        }
        tokenIndex = previousIndex;

        if (token.text.equals("(")) {
            // arguments in parentheses, can be 0 or more arguments:    print(), print(10)
            // Commas are allowed after the arguments:       print(10,)
            consume();
            return new ListNode(parseList(")", 0), tokenIndex);
        }

        ListNode expr = new ListNode(tokenIndex);
        while (token.type != LexerTokenType.EOF && !LISTTERMINATORS.contains(token.text)) {
            // argument without parentheses
            expr.elements.add(parseExpression(getPrecedence(",") + 1));
            token = peek();
            if (token.text.equals(",") || token.text.equals("=>")) {
                while (token.text.equals(",") || token.text.equals("=>")) {
                    consume();
                    token = peek();
                }
            } else {
                return expr;
            }
        }

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }
        return expr;
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
        ListNode expr;

        LexerToken token = peek();
        if (token.text.equals(close)) {
            // empty list
            consume();
            expr = new ListNode(tokenIndex);
        } else {
            expr = ListNode.makeList(parseExpression(0));
            consume(LexerTokenType.OPERATOR, close);
        }

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", ctx.errorUtil);
        }

        return expr.elements;
    }
}

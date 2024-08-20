package org.perlonjava;

import org.perlonjava.node.*;

import java.util.*;

public class Parser {
    private final List<LexerToken> tokens;
    private final ErrorMessageUtil errorUtil;
    private int tokenIndex = 0;
    private boolean parsingForLoopVariable = false;
    private static final Set<String> TERMINATORS =
            new HashSet<>(Arrays.asList(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when"));
    private static final Set<String> LISTTERMINATORS =
            new HashSet<>(Arrays.asList(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when", "not", "and", "or"));
    private static final Set<String> UNARY_OP =
            new HashSet<>(
                    Arrays.asList(
                            "!",
                            "~",
                            "\\",
                            "-",
                            "+",
                            "--",
                            "++", // operators
                            "$",
                            "@",
                            "%",
                            "*",
                            "&",
                            "$#" // sigils
                    ));

    public Parser(ErrorMessageUtil errorUtil, List<LexerToken> tokens) {
        this.errorUtil = errorUtil;
        this.tokens = tokens;
    }

    public Node parse() {
        return parseBlock();
    }

    private Node parseBlock() {
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
        return new BlockNode(statements, tokenIndex);
    }

    public Node parseStatement() {
        LexerToken token = peek();

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
                    return new BinaryOperatorNode("&&", parseExpression(0), expression, tokenIndex);
                case "unless":
                    consume();
                    return new BinaryOperatorNode("||", parseExpression(0), expression, tokenIndex);
                case "for":
                case "foreach":
                    consume();
                    return new For1Node(
                            false,
                            new UnaryOperatorNode("$", new IdentifierNode("_", tokenIndex), tokenIndex),  // $_
                            parseExpression(0),
                            expression,
                            tokenIndex);
                case "while":
                case "until":
                    consume();
                    Node condition = parseExpression(0);
                    if (token.text.equals("until")) {
                        condition = new UnaryOperatorNode("not", condition, condition.getIndex());
                    }
                    return new For3Node(false, null, condition, null, expression, tokenIndex);
            }
            throw new PerlCompilerException(tokenIndex, "Not implemented: " + token, errorUtil);
        }
        if (token.type != LexerTokenType.EOF && !token.text.equals("}") && !token.text.equals(";")) {
            throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, errorUtil);
        }
        if (token.text.equals(";")) {
            consume();
        }
        return expression;
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

    private Node parseAnonSub() {
        // token == "sub"
        // TODO - optional name, subroutine prototype
        consume(LexerTokenType.OPERATOR, "{");
        Node block = parseBlock();
        consume(LexerTokenType.OPERATOR, "}");

        // some characters are illegal after an anon sub
        LexerToken token = peek();
        if (token.text.equals("(") || token.text.equals("{") || token.text.equals("[")) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
        }

        return new AnonSubNode(block, false, tokenIndex);
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
            condition = new UnaryOperatorNode("not", condition, condition.getIndex());
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
                    varNode = new UnaryOperatorNode(
                            "$", new IdentifierNode("_", tokenIndex), tokenIndex);  // $_
                }
                return new For1Node(true, varNode, initialization, body, tokenIndex);
            }
        }
        // 3-argument for
        if (varNode != null) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
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
     *
     * @param precedence The precedence level of the current expression.
     * @return The root node of the parsed expression.
     */
    private Node parseExpression(int precedence) {
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
            if (tokenPrecedence < precedence) {
                break;
            }

            // If the operator is right associative (like exponentiation), parse it with lower precedence.
            if (isRightAssociative(token.text)) {
                left = parseInfix(left, tokenPrecedence - 1); // Parse the right side with lower precedence.
            } else {
                // Otherwise, parse it normally with the same precedence.
                left = parseInfix(left, tokenPrecedence);
            }
        }

        // Return the root node of the constructed expression tree.
        return left;
    }

    private Node parsePrimary() {
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
                    case "not":
                        // Handle 'not' keyword as a unary operator with an operand
                        operand = parseExpression(getPrecedence(token.text) + 1);
                        return new UnaryOperatorNode("not", operand, tokenIndex);
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
                                    operand = new UnaryOperatorNode(
                                        "$", new IdentifierNode("_", tokenIndex), tokenIndex);
                                    break;
                            }
                        }
                        return new UnaryOperatorNode(text, operand, tokenIndex);
                    case "join":
                        // Handle 'join' keyword as a Binary operator with a RuntimeList operand
                        operand = parseZeroOrMoreList(1);
                        Node separator = ((ListNode) operand).elements.remove(0);
                        return new BinaryOperatorNode("join", separator, operand, tokenIndex);
                    case "print":
                    case "say":
                        // Handle 'say' keyword as a unary operator with a RuntimeList operand
                        operand = parseZeroOrMoreList(0);
                        return new UnaryOperatorNode(token.text, operand, tokenIndex);
                    case "values":
                    case "keys":
                        operand = parseZeroOrOneList(1);
                        return new UnaryOperatorNode(token.text, ((ListNode) operand).elements.get(0), tokenIndex);
                    case "our":
                    case "my":
                        // Handle 'my' keyword as a unary operator with an operand
                        operand = parsePrimary();
                        return new UnaryOperatorNode(token.text, operand, tokenIndex);
                    case "return":
                        // Handle 'return' keyword as a unary operator with an operand;
                        // Parenthensis are ignored.
                        operand = parseExpression(getPrecedence("print") + 1);
                        return new UnaryOperatorNode("return", operand, tokenIndex);
                    case "eval":
                        // Handle 'eval' keyword which can be followed by a block or an expression
                        token = peek();
                        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
                            // If the next token is '{', parse a block
                            consume(LexerTokenType.OPERATOR, "{");
                            Node block = parseBlock();
                            consume(LexerTokenType.OPERATOR, "}");
                            // transform:  eval { 123 }
                            // into:  sub { 123 }->()
                            //
                            //   BinaryOperatorNode: ->
                            //     AnonSubNode:
                            //       BlockNode:
                            //         NumberNode: 123
                            //     ListNode:
                            return new BinaryOperatorNode("->",
                                    new AnonSubNode(block, true, tokenIndex),
                                    new ListNode(tokenIndex), tokenIndex);
                        } else {
                            // Otherwise, parse a primary expression
                            operand = parsePrimary();
                        }
                        return new UnaryOperatorNode("eval", operand, tokenIndex);
                    case "do":
                        // Handle 'do' keyword which can be followed by a block
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
                        return parseAnonSub();
                    case "q":
                    case "qq":
                    case "qx":
                    case "qw":
                        // Handle special-quoted strings
                        return parseRawString(token.text);
                    default:
                        // Handle any other identifier as a simple identifier node
                        return new IdentifierNode(token.text, tokenIndex);
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
                        return new HashLiteralNode(parseList("}", 1), tokenIndex);
                    case "[":
                        // Handle square brackets to parse a nested expression
                        return new ArrayLiteralNode(parseList("]", 1), tokenIndex);
                    case ".":
                        // Handle fractional numbers
                        return parseFractionalNumber();
                    case "'":
                    case "\"":
                        // Handle single and double-quoted strings
                        return parseRawString(token.text);
                    default:
                        // Handle unary operators
                        if (UNARY_OP.contains(token.text)) {

                            String text = token.text;
                            int saveIndex = tokenIndex;

                            nextToken = peek(); // operator or identifier
                            if (isSigil(text)
                                    && (nextToken.type == LexerTokenType.OPERATOR
                                    || nextToken.type == LexerTokenType.IDENTIFIER
                                    || nextToken.type == LexerTokenType.NUMBER)) {
                                // Handle normal variables and special variables like $@

                                consume(); // operator or identifier

                                // handle the special case for $$a
                                if (nextToken.text.equals("$")
                                        && (peek().text.equals("$")
                                        || peek().type == LexerTokenType.IDENTIFIER
                                        || peek().type == LexerTokenType.NUMBER)) {
                                    // wrong guess: this is not a special variable
                                    tokenIndex = saveIndex; // backtrack
                                } else {

                                    // some characters are illegal after a variable
                                    if (peek().text.equals("(") && !parsingForLoopVariable) {
                                        // not parsing "for my $v (..."
                                        throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
                                    }

                                    // create a Variable
                                    return new UnaryOperatorNode(
                                            text, new IdentifierNode(nextToken.text, tokenIndex), tokenIndex);
                                }
                            }

                            operand = parseExpression(getPrecedence(text) + 1);
                            return new UnaryOperatorNode(text, operand, tokenIndex);
                        }
                        break;
                }
                break;
            case EOF:
                // Handle end of input
                return null;
            default:
                // Throw an exception for any unexpected token
                throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, errorUtil);
        }
        // Throw an exception if no valid case was found
        throw new PerlCompilerException(tokenIndex, "Unexpected token: " + token, errorUtil);
    }

    private Node parseRawString(String operator) {
        // WIP - handle special quotes for operators: q qq qx qw
        if (operator.equals("'") || operator.equals("\"")) {
            tokenIndex--;   // will re-parse the quote
        }
        StringParser.ParsedString rawStr = StringParser.parseRawStringWithDelimiter(tokens, tokenIndex, false);
        tokenIndex = rawStr.next;

        switch (operator) {
            case "'":
            case "q":
                return StringParser.parseSingleQuotedString(rawStr.buffer, rawStr.startDelim, rawStr.endDelim, rawStr.index);
            case "\"":
            case "qq":
                return StringParser.parseDoubleQuotedString(rawStr.buffer, errorUtil, rawStr.index);
        }

        return new UnaryOperatorNode(operator, new StringNode(rawStr.buffer, rawStr.index), rawStr.index);
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

        number.append(consume(LexerTokenType.NUMBER).text); // consume digits after '.'
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
                    throw new PerlCompilerException(tokenIndex, "Malformed number", errorUtil);
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

    private Node parseInfix(Node left, int precedence) {
        LexerToken token = consume();

        Node right;
        switch (token.text) {
            case "or":
            case "xor":
            case "and":
            case "||":
            case "//":
            case "&&":
            case "|":
            case "^":
            case "&":
            case "==":
            case "!=":
            case "<=>":
            case "eq":
            case "ne":
            case "cmp":
            case "<":
            case ">":
            case "<=":
            case ">=":
            case "lt":
            case "gt":
            case "le":
            case "ge":
            case "<<":
            case ">>":
            case "+":
            case "-":
            case "*":
            case "**":
            case "/":
            case "%":
            case ".":
            case "=":
            case "=~":
            case "!~":
            case "x":
            case "..":
            case "...":
                right = parseExpression(precedence);
                return new BinaryOperatorNode(token.text, left, right, tokenIndex);
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
                return new UnaryOperatorNode(token.text + "postfix", left, tokenIndex);
        }
        throw new PerlCompilerException(tokenIndex, "Unexpected infix operator: " + token, errorUtil);
    }

    private void skipWhitespace() {
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
    }

    private LexerToken peek() {
        skipWhitespace();
        if (tokenIndex >= tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return tokens.get(tokenIndex);
    }

    private LexerToken consume() {
        skipWhitespace();
        if (tokenIndex >= tokens.size()) {
            return new LexerToken(LexerTokenType.EOF, "");
        }
        return tokens.get(tokenIndex++);
    }

    private LexerToken consume(LexerTokenType type) {
        LexerToken token = consume();
        if (token.type != type) {
            throw new PerlCompilerException(
                    tokenIndex, "Expected token " + type + " but got " + token, errorUtil);
        }
        return token;
    }

    private void consume(LexerTokenType type, String text) {
        LexerToken token = consume();
        if (token.type != type || !token.text.equals(text)) {
            throw new PerlCompilerException(
                    tokenIndex,
                    "Expected token " + type + " with text " + text + " but got " + token,
                    errorUtil);
        }
    }

    private int getPrecedence(String operator) {
        // Define precedence levels for operators
        switch (operator) {
            case "or":
            case "xor":
                return 1;
            case "and":
                return 2;
            case "not":
                return 3;
            case "print":
                return 4;
            case ",":
            case "=>":
                return 5;
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
                return 6;
            case "?":
                return 7;
            case "..":
            case "...":
                return 8;
            case "||":
            case "^^":
            case "//":
                return 9;
            case "&&":
                return 10;

            case "|":
            case "^":
                return 11;
            case "&":
                return 12;

            case "==":
            case "!=":
            case "<=>":
            case "eq":
            case "ne":
            case "cmp":
                return 13;
            case "<":
            case ">":
            case "<=":
            case ">=":
            case "lt":
            case "gt":
            case "le":
            case "ge":
                return 14;
            case ">>":
            case "<<":
                return 16;
            case "+":
            case "-":
                return 17;
            case "*":
            case "/":
            case "%":
            case "x":
                return 18;
            case "=~":
            case "!~":
                return 19;
            case "!":
            case "~":
            case "\\":
                return 20;
            case "**":
                return 21;
            case "++":
            case "--":
                return 22;
            case "->":
                return 23;
            default:
                return 24;
        }
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

    private boolean isRightAssociative(String s) {
        // Define right associative operators
        switch (s) {
            case "=":
            case "-=":
            case "+=":
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
                throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
            }
        } else if (token.type == LexerTokenType.EOF || LISTTERMINATORS.contains(token.text) || token.text.equals(",")) {
            // no argument
            expr = new ListNode(tokenIndex);
        } else {
            // argument without parentheses
            expr = ListNode.makeList(parseExpression(getPrecedence(",") + 1));
        }
        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
        }
        return expr;
    }

    // List parser for predeclared function calls, accepts a list with Parentheses or without
    //
    // The Minimum number of arguments can be set.
    //
    private ListNode parseZeroOrMoreList(int minItems) {
        LexerToken token = peek();
        if (token.text.equals("(")) {
            // arguments in parentheses, can be 0 or more arguments:    print(), print(10)
            // Commas are allowed after the arguments:       print(10,)
            consume();
            return new ListNode(parseList(")", 0), tokenIndex);
        }

        ListNode expr = new ListNode(tokenIndex);
        while (token.type != LexerTokenType.EOF) {
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
            throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
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
            List<Node> list = new ArrayList<>();
            expr = new ListNode(list, tokenIndex);
        } else {
            expr = ListNode.makeList(parseExpression(0));
            consume(LexerTokenType.OPERATOR, close);
        }

        if (expr.elements.size() < minItems) {
            throw new PerlCompilerException(tokenIndex, "Syntax error", errorUtil);
        }

        return expr.elements;
    }

    public static void main(String[] args) throws Exception {
        String fileName = "example.pl";
        String code = "my $var = 42; 1 ? 2 : 3; print \"Hello, World!\\n\";";
        if (args.length >= 2 && args[0].equals("-e")) {
            code = args[1]; // Read the code from the command line parameter
            fileName = "-e";
        }
        Lexer lexer = new Lexer(code);
        List<LexerToken> tokens = lexer.tokenize();
        ErrorMessageUtil errorMessageUtil = new ErrorMessageUtil(fileName, tokens);
        Parser parser = new Parser(errorMessageUtil, tokens);
        Node ast = parser.parse();
        System.out.println(ast);
    }
}

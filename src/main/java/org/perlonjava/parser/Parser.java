package org.perlonjava.parser;

import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.*;

import static org.perlonjava.parser.TokenUtils.peek;

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
                statements.add(ParseStatement.parseStatement(this));
            }
            token = peek(this);
        }
        if (statements.isEmpty()) {
            statements.add(new ListNode(tokenIndex));
        }
        ctx.symbolTable.exitScope();
        return new BlockNode(statements, currentIndex);
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
        Node left = ParsePrimary.parsePrimary(this);

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
                left = ParseInfix.parseInfixOperation(this, left, tokenPrecedence - 1); // Parse the right side with lower precedence.
            } else {
                // Otherwise, parse it normally with the same precedence.
                ctx.logDebug("parseExpression `" + token.text + "` precedence: " + tokenPrecedence + " left assoc");
                left = ParseInfix.parseInfixOperation(this, left, tokenPrecedence);
            }
        }

        // Return the root node of the constructed expression tree.
        return left;
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

    List<Node> parseHashSubscript() {
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

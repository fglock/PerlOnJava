package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.*;

import static org.perlonjava.parser.TokenUtils.peek;

/**
 * The Parser class is responsible for parsing a list of tokens into an abstract syntax tree (AST).
 * It handles operator precedence, associativity, and special token combinations.
 */
public class Parser {

    // Context for code emission.
    public final EmitterContext ctx;
    // List of tokens to be parsed.
    public final List<LexerToken> tokens;
    // List to store heredoc nodes encountered during parsing.
    private final List<OperatorNode> heredocNodes = new ArrayList<>();
    // Current index in the token list.
    public int tokenIndex = 0;
    // Flags to indicate special parsing states.
    public boolean parsingForLoopVariable = false;
    public boolean parsingTakeReference = false;

    /**
     * Constructs a Parser with the given context and tokens.
     *
     * @param ctx    The context for code emission.
     * @param tokens The list of tokens to parse.
     */
    public Parser(EmitterContext ctx, List<LexerToken> tokens) {
        this.ctx = ctx;
        this.tokens = tokens;
    }

    /**
     * Returns the list of heredoc nodes encountered during parsing.
     *
     * @return The list of heredoc nodes.
     */
    public List<OperatorNode> getHeredocNodes() {
        return heredocNodes;
    }

    /**
     * Retrieves the precedence of the given operator.
     *
     * @param operator The operator to check.
     * @return The precedence level of the operator.
     */
    public int getPrecedence(String operator) {
        return ParserTables.precedenceMap.getOrDefault(operator, 24);
    }

    /**
     * Parses the tokens into an abstract syntax tree (AST).
     *
     * @return The root node of the parsed AST.
     */
    public Node parse() {
        if (tokens.get(tokenIndex).text.equals("=")) {
            // looks like pod: insert a newline to trigger pod parsing
            tokens.addFirst(new LexerToken(LexerTokenType.NEWLINE, "\n"));
        }
        Node ast = ParseBlock.parseBlock(this);
        if (!getHeredocNodes().isEmpty()) {
            ParseHeredoc.heredocError(this);
        }
        return ast;
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
            if (isExpressionTerminator(token)) {
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
            if (ParserTables.RIGHT_ASSOC_OP.contains(token.text)) {
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

    public static boolean isExpressionTerminator(LexerToken token) {
        return token.type == LexerTokenType.EOF || ParserTables.TERMINATORS.contains(token.text);
    }

    public void throwError(String message) {
        throw new PerlCompilerException(this.tokenIndex, message, this.ctx.errorUtil);
    }

}

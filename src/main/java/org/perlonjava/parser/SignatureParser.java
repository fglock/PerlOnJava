package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ListParser.parseZeroOrOneList;
import static org.perlonjava.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.parser.PrototypeArgs.consumeArgsWithPrototype;

/**
 * SignatureParser handles parsing of Perl subroutine signatures.
 *
 * <p>Perl signatures define the parameters that a subroutine accepts, including:
 * <ul>
 *   <li>Positional parameters: {@code sub foo($a, $b)}</li>
 *   <li>Optional parameters with defaults: {@code sub foo($a = 10)}</li>
 *   <li>Slurpy parameters: {@code sub foo($a, @rest)} or {@code sub foo(%opts)}</li>
 * </ul>
 *
 * <p>The parser generates AST nodes that:
 * <ol>
 *   <li>Validate the number of arguments passed</li>
 *   <li>Assign arguments from @_ to the signature variables</li>
 *   <li>Apply default values for optional parameters</li>
 * </ol>
 */
public class SignatureParser {

    /**
     * Parses a Perl subroutine signature and generates the corresponding AST.
     *
     * <p>The generated AST includes:
     * <ul>
     *   <li>Argument count validation</li>
     *   <li>Variable declaration and assignment from @_</li>
     *   <li>Default value assignments for optional parameters</li>
     * </ul>
     *
     * @param parser The parent parser instance for error reporting
     * @param signature The signature string to parse (e.g., "$a, $b = 10, @rest")
     * @return A ListNode containing the generated AST nodes
     * @throws PerlCompilerException if the signature syntax is invalid
     */
    public static ListNode parseSignature(Parser parser, String signature) {
        int tokenIndex = parser.tokenIndex;

        // Tokenize the signature string into lexer tokens
        Lexer lexer = new Lexer(signature);
        List<LexerToken> tokens = lexer.tokenize();
        Parser sigParser = new Parser(parser.ctx, tokens);
        // sigParser.ctx.logDebug("signature tokens: " + tokens + " at " + sigParser.tokenIndex);

        // Initialize collections for building the AST
        List<Node> nodes = new ArrayList<>();       // All generated AST nodes
        List<Node> variables = new ArrayList<>();   // Variable nodes for "my (...) = @_"
        int minParams = 0;                          // Minimum required parameters
        int maxParams = 0;                          // Maximum allowed parameters
        boolean hasSlurpy = false;                  // Whether signature has @array or %hash

        // Parse each parameter in the signature
        while (true) {
            LexerToken token = TokenUtils.consume(sigParser);
            if (token.type == LexerTokenType.EOF) {
                break;
            }

            String sigil = token.text;

            // Special case: $# is tokenized as a single token but is invalid in signatures
            if (sigil.equals("$#")) {
                parser.throwError("'#' not allowed immediately following a sigil in a subroutine signature");
            }

            // Validate that parameter starts with a valid sigil ($, @, or %)
            if (!sigil.equals("$") && !sigil.equals("@") && !sigil.equals("%")) {
                parser.throwError("A signature parameter must start with '$', '@' or '%'");
            }

            // Check if we already have a slurpy parameter (which must be last)
            if (hasSlurpy) {
                parser.throwError("Slurpy parameter not last");
            }

            String name = null;
            LexerToken nextToken = TokenUtils.peek(sigParser);

            // Check for double sigil (e.g., $$) which is invalid
            if (nextToken.text.equals("$") || nextToken.text.equals("@") || nextToken.text.equals("%")) {
                parser.throwError("Illegal character following sigil in a subroutine signature");
            }

            // Check for invalid character after sigil
            if (nextToken.text.equals("#")) {
                parser.throwError("'#' not allowed immediately following a sigil in a subroutine signature");
            }

            // Parse the variable name if present
            if (nextToken.type == LexerTokenType.IDENTIFIER) {
                name = TokenUtils.consume(sigParser).text;
            }

            // Create variable node or undef placeholder
            Node variable = null;
            if (name != null) {
                // Named parameter: $foo becomes OperatorNode("$", IdentifierNode("foo"))
                variable = new OperatorNode(sigil, new IdentifierNode(name, tokenIndex), tokenIndex);
                variables.add(variable);
            } else {
                // Anonymous parameter: $ becomes undef
                variables.add(new OperatorNode("undef", null, tokenIndex));
            }
            sigParser.ctx.logDebug("signature variable: " + variable);

            // Handle slurpy parameters (@array or %hash)
            if (sigil.equals("@") || sigil.equals("%")) {
                hasSlurpy = true;
                maxParams = Integer.MAX_VALUE;  // Slurpy can accept unlimited arguments

                // Verify no parameters come after the slurpy
                LexerToken checkToken = TokenUtils.peek(sigParser);
                if (checkToken.text.equals(",")) {
                    TokenUtils.consume(sigParser); // consume comma
                    checkToken = TokenUtils.peek(sigParser);
                    if (checkToken.type != LexerTokenType.EOF) {
                        // Check if it's another slurpy
                        if (checkToken.text.equals("@") || checkToken.text.equals("%")) {
                            parser.throwError("Multiple slurpy parameters not allowed");
                        } else {
                            parser.throwError("Slurpy parameter not last");
                        }
                    }
                }
                break; // Slurpy must be last parameter
            }

            // Handle optional scalar parameters with default values
            Node defaultValue = null;
            nextToken = TokenUtils.peek(sigParser);
            if (nextToken.text.equals("=") ||
                    nextToken.text.equals("||=") ||
                    nextToken.text.equals("//=")) {

                String op = TokenUtils.consume(sigParser).text;

                // Ensure there's a default expression after the assignment operator
                if (TokenUtils.peek(sigParser).type == LexerTokenType.EOF ||
                        TokenUtils.peek(sigParser).text.equals(",")) {
                    parser.throwError("Optional parameter lacks default expression");
                }

                // Parse the default value expression
                ListNode arguments = consumeArgsWithPrototype(sigParser, "$", false);
                defaultValue = arguments.elements.getFirst();

                // Generate conditional assignment for the default value
                nodes.add(generateDefaultAssignment(defaultValue, op, maxParams, variable, parser));
            } else {
                // Required parameter (no default value)
                minParams++;
            }

            maxParams++;

            // Handle comma or end of signature
            token = TokenUtils.peek(sigParser);
            if (token.text.equals(",")) {
                TokenUtils.consume(sigParser); // consume comma
            } else if (token.type == LexerTokenType.EOF) {
                break;
            } else {
                // Check for missing comma between parameters
                if (token.text.equals("$") || token.text.equals("@") || token.text.equals("%")) {
                    parser.throwError("syntax error");
                }
                parser.throwError("Expected ',' or ')' in signature prototype");
            }
        }

        // Verify all tokens were consumed
        if (TokenUtils.peek(sigParser).type != LexerTokenType.EOF) {
            parser.throwError("Expected ')' in signature prototype");
        }

        sigParser.ctx.logDebug("signature min: " + minParams + " max: " + maxParams + " slurpy: " + hasSlurpy);

        // Generate argument count validation
        // AST: (minParams <= @_ <= maxParams) || die "Bad number of arguments"
        nodes.add(0, new BinaryOperatorNode(
                "||",
                new ListNode(List.of(
                        new BinaryOperatorNode("<=",
                                new BinaryOperatorNode("<=",
                                        new NumberNode(Integer.toString(minParams), tokenIndex),
                                        atUnderscore(parser),
                                        tokenIndex),
                                new NumberNode(Integer.toString(maxParams), tokenIndex),
                                tokenIndex)
                ), tokenIndex),
                new OperatorNode("die",
                        new ListNode(List.of(
                                new StringNode("Bad number of arguments", tokenIndex)
                        ), tokenIndex),
                        tokenIndex),
                tokenIndex));

        // Generate parameter assignment from @_
        // AST: my ($a, $b, @rest) = @_
        nodes.add(0,
                new BinaryOperatorNode(
                        "=",
                        new OperatorNode("my",
                                new ListNode(variables, tokenIndex),
                                tokenIndex),
                        atUnderscore(parser),
                        tokenIndex));

        return new ListNode(nodes, tokenIndex);
    }

    /**
     * Generates AST nodes for default parameter value assignment.
     *
     * <p>Handles three types of default assignments:
     * <ul>
     *   <li>{@code $a = 10} - Simple assignment if parameter not provided</li>
     *   <li>{@code $a //= 10} - Assign if parameter is undefined</li>
     *   <li>{@code $a ||= 10} - Assign if parameter is false</li>
     * </ul>
     *
     * @param defaultValue The default value expression
     * @param op The assignment operator (=, //=, or ||=)
     * @param paramIndex The parameter's position in the signature (0-based)
     * @param variable The variable node to assign to
     * @param parser Parser instance
     * @return AST node for the conditional default assignment
     */
    private static Node generateDefaultAssignment(Node defaultValue, String op, int paramIndex, Node variable, Parser parser) {
        int tokenIndex = parser.tokenIndex;
        if (variable == null) {
            // Anonymous parameter with default: $ = value
            // This is valid syntax but effectively a no-op
            return new ListNode(tokenIndex);
        }

        if (op.equals("=")) {
            // Simple default: assign if not enough arguments provided
            // AST: @_ < (paramIndex + 1) && ($var = defaultValue)
            return new BinaryOperatorNode(
                    "&&",
                    new BinaryOperatorNode(
                            "<",
                            atUnderscore(parser),
                            new NumberNode(Integer.toString(paramIndex + 1), tokenIndex),
                            tokenIndex),
                    new BinaryOperatorNode(
                            "=",
                            variable,
                            defaultValue,
                            tokenIndex),
                    tokenIndex);
        }

        // Defined-or (//=) and logical-or (||=) assignments
        // These apply the default based on the parameter's value, not its presence
        // AST: $var //= defaultValue or $var ||= defaultValue
        return new BinaryOperatorNode(
                op,
                variable,
                defaultValue,
                tokenIndex);
    }
}

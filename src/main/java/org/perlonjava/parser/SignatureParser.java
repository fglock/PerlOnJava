package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.parser.PrototypeArgs.consumeArgsWithPrototype;

/**
 * SignatureParser handles parsing of Perl 5.42 subroutine signatures.
 *
 * <p>Supported signature features:
 * <ul>
 *   <li>Empty signatures: {@code sub foo() { }}</li>
 *   <li>Mandatory parameters: {@code sub foo($a, $b) { }}</li>
 *   <li>Ignored parameters: {@code sub foo($a, $, $c) { }}</li>
 *   <li>Optional parameters: {@code sub foo($a = 10) { }}</li>
 *   <li>Default from previous parameter: {@code sub foo($a, $b = $a) { }}</li>
 *   <li>Defined-or defaults: {@code sub foo($a //= 'default') { }}</li>
 *   <li>Logical-or defaults: {@code sub foo($a ||= 100) { }}</li>
 *   <li>Slurpy arrays: {@code sub foo($a, @rest) { }}</li>
 *   <li>Slurpy hashes: {@code sub foo(%opts) { }}</li>
 *   <li>Anonymous slurpy: {@code sub foo($a, @) { }}</li>
 * </ul>
 */
public class SignatureParser {

    private final Parser parser;
    private final List<Node> astNodes = new ArrayList<>();
    private final List<Node> parameterVariables = new ArrayList<>();
    private int minParams = 0;
    private int maxParams = 0;
    private boolean hasSlurpy = false;

    private SignatureParser(Parser parser) {
        this.parser = parser;
    }

    /**
     * Parses a Perl subroutine signature and generates the corresponding AST.
     *
     * @param parser The parser instance
     * @return A ListNode containing the generated AST nodes
     * @throws PerlCompilerException if the signature syntax is invalid
     */
    public static ListNode parseSignature(Parser parser) {
        return new SignatureParser(parser).parse();
    }

    private ListNode parse() {
        consumeOpenParen();

        // Handle empty signature
        if (peekToken().text.equals(")")) {
            consumeCloseParen();
            return generateSignatureAST();
        }

        // Parse parameters
        while (true) {
            parseParameter();

            // Check what comes after the parameter
            LexerToken next = peekToken();
            if (next.text.equals(")")) {
                break;
            } else if (next.text.equals(",")) {
                consumeCommas();
                // Check for trailing comma
                if (peekToken().text.equals(")")) {
                    break;
                }
            } else {
                // Check for missing comma between parameters (special case)
                if (next.text.equals("$") || next.text.equals("@") || next.text.equals("%")) {
                    parser.throwError("syntax error");
                }
                parser.throwError("Expected ',' or ')' in signature prototype");
            }
        }

        consumeCloseParen();
        return generateSignatureAST();
    }

    private void parseParameter() {
        LexerToken sigilToken = consumeToken();
        String sigil = sigilToken.text;

        validateSigil(sigil);

        if (hasSlurpy) {
            parser.throwError("Slurpy parameter not last");
        }

        // Check if this is a slurpy parameter
        boolean isSlurpy = sigil.equals("@") || sigil.equals("%");

        // Parse parameter name (if present)
        String paramName = null;
        if (peekToken().type == LexerTokenType.IDENTIFIER) {
            paramName = consumeToken().text;
        }

        // Create parameter variable or undef placeholder
        Node paramVariable = createParameterVariable(sigil, paramName);
        parameterVariables.add(paramVariable);

        if (isSlurpy) {
            handleSlurpyParameter();
        } else {
            handleScalarParameter(paramVariable);
        }
    }

    private void validateSigil(String sigil) {
        // Check for $# which is tokenized as a single token
        if (sigil.equals("$#")) {
            parser.throwError("'#' not allowed immediately following a sigil in a subroutine signature");
        }

        if (!sigil.equals("$") && !sigil.equals("@") && !sigil.equals("%")) {
            parser.throwError("A signature parameter must start with '$', '@' or '%'");
        }

        // Check for double sigil or invalid character after sigil
        LexerToken next = peekToken();
        if (next.text.equals("$") || next.text.equals("@") || next.text.equals("%")) {
            parser.throwError("Illegal character following sigil in a subroutine signature");
        }
        if (next.text.equals("#")) {
            parser.throwError("'#' not allowed immediately following a sigil in a subroutine signature");
        }
    }

    private Node createParameterVariable(String sigil, String name) {
        if (name != null) {
            return new OperatorNode(sigil, new IdentifierNode(name, parser.tokenIndex), parser.tokenIndex);
        } else {
            return new OperatorNode("undef", null, parser.tokenIndex);
        }
    }

    private void handleSlurpyParameter() {
        hasSlurpy = true;
        maxParams = Integer.MAX_VALUE;

        // Verify no more parameters after slurpy
        LexerToken next = peekToken();
        if (next.text.equals(",")) {
            consumeToken(); // consume comma
            next = peekToken();
            if (!next.text.equals(")")) {
                if (next.text.equals("@") || next.text.equals("%")) {
                    parser.throwError("Multiple slurpy parameters not allowed");
                } else {
                    parser.throwError("Slurpy parameter not last");
                }
            }
        }
    }

    private void handleScalarParameter(Node paramVariable) {
        LexerToken next = peekToken();

        // Check for default value
        if (next.text.equals("=") || next.text.equals("||=") || next.text.equals("//=")) {
            String defaultOp = consumeToken().text;
            Node defaultValue = parseDefaultValue(paramVariable);

            if (defaultValue != null) {
                astNodes.add(generateDefaultAssignment(paramVariable, defaultValue, defaultOp, maxParams));
            }
        } else {
            // Mandatory parameter
            minParams++;
        }

        maxParams++;
    }

    private Node generateDefaultAssignment(Node variable, Node defaultValue, String op, int paramIndex) {
        if (variable == null || (variable instanceof OperatorNode && ((OperatorNode) variable).operator.equals("undef"))) {
            // Anonymous parameter with default - no-op
            return new ListNode(parser.tokenIndex);
        }

        if (op.equals("=")) {
            // Simple default: assign if not enough arguments
            // @_ < (paramIndex + 1) && ($var = defaultValue)
            return new BinaryOperatorNode(
                    "&&",
                    new BinaryOperatorNode(
                            "<",
                            atUnderscore(parser),
                            new NumberNode(Integer.toString(paramIndex + 1), parser.tokenIndex),
                            parser.tokenIndex),
                    new BinaryOperatorNode(
                            "=",
                            variable,
                            defaultValue,
                            parser.tokenIndex),
                    parser.tokenIndex);
        }

        // //= or ||= operators
        return new BinaryOperatorNode(op, variable, defaultValue, parser.tokenIndex);
    }

    private Node parseDefaultValue(Node paramVariable) {
        // Check if there's actually a default expression
        LexerToken next = peekToken();
        if (next.type == LexerTokenType.EOF || next.text.equals(",") || next.text.equals(")")) {
            boolean isUndef = paramVariable instanceof OperatorNode && ((OperatorNode) paramVariable).operator.equals("undef");
            if (paramVariable != null && !isUndef) {
                parser.throwError("Optional parameter lacks default expression");
            }
            return null;
        }

        // Parse the default value expression
        ListNode arguments = consumeArgsWithPrototype(parser, "$", false);
        return arguments.elements.getFirst();
    }

    private ListNode generateSignatureAST() {
        List<Node> allNodes = new ArrayList<>();

        // Add argument count validation
        allNodes.add(generateArgCountValidation());

        // Add parameter assignment from @_
        allNodes.add(generateParameterAssignment());

        // Add default value assignments
        allNodes.addAll(astNodes);

        return new ListNode(allNodes, parser.tokenIndex);
    }

    private Node generateArgCountValidation() {
        // (minParams <= @_ <= maxParams) || die "Bad number of arguments"
        return new BinaryOperatorNode(
                "||",
                new ListNode(List.of(
                        new BinaryOperatorNode("<=",
                                new BinaryOperatorNode("<=",
                                        new NumberNode(Integer.toString(minParams), parser.tokenIndex),
                                        atUnderscore(parser),
                                        parser.tokenIndex),
                                new NumberNode(Integer.toString(maxParams), parser.tokenIndex),
                                parser.tokenIndex)
                ), parser.tokenIndex),
                new OperatorNode("die",
                        new ListNode(List.of(
                                new StringNode("Bad number of arguments", parser.tokenIndex)
                        ), parser.tokenIndex),
                        parser.tokenIndex),
                parser.tokenIndex);
    }

    private Node generateParameterAssignment() {
        // my ($a, $b, @rest) = @_
        return new BinaryOperatorNode(
                "=",
                new OperatorNode("my",
                        new ListNode(parameterVariables, parser.tokenIndex),
                        parser.tokenIndex),
                atUnderscore(parser),
                parser.tokenIndex);
    }

    // Token handling utilities
    private void consumeOpenParen() {
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
    }

    private void consumeCloseParen() {
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
    }

    private void consumeCommas() {
        while (peekToken().text.equals(",")) {
            consumeToken();
        }
    }

    private LexerToken peekToken() {
        return TokenUtils.peek(parser);
    }

    private LexerToken consumeToken() {
        return TokenUtils.consume(parser);
    }
}

package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.OperatorParser.dieWarnNode;
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
 *   <li>Named parameters: {@code sub foo(:$named = 'default') { }}</li>
 * </ul>
 */
public class SignatureParser {

    private final Parser parser;
    private final List<Node> astNodes = new ArrayList<>();
    private final List<Node> parameterVariables = new ArrayList<>();
    private final List<Node> namedParameterNodes = new ArrayList<>();
    private int minParams = 0;
    private int maxParams = 0;
    private boolean hasSlurpy = false;
    private String namedArgsHashName = null; // Track the hash name for named parameters

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
        // Check for named parameter (starts with :)
        boolean isNamed = false;
        if (peekToken().text.equals(":")) {
            consumeToken(); // consume ':'
            isNamed = true;
        }

        LexerToken sigilToken = consumeToken();
        String sigil = sigilToken.text;

        validateSigil(sigil);

        if (hasSlurpy) {
            parser.throwError("Slurpy parameter not last");
        }

        // Check if this is a slurpy parameter
        boolean isSlurpy = sigil.equals("@") || sigil.equals("%");

        // Named parameters cannot be slurpy
        if (isNamed && isSlurpy) {
            parser.throwError("Named parameters cannot be slurpy");
        }

        // Parse parameter name (if present)
        String paramName = null;
        if (peekToken().type == LexerTokenType.IDENTIFIER) {
            paramName = consumeToken().text;
        }

        // Named parameters must have a name
        if (isNamed && paramName == null) {
            parser.throwError("Named parameter must have a name");
        }

        // Create parameter variable or undef placeholder
        Node paramVariable = createParameterVariable(sigil, paramName);
        
        if (isNamed) {
            // Named parameters are handled separately, not part of @_ unpacking
            namedParameterNodes.add(paramVariable);
            handleNamedParameter(paramVariable, paramName);
        } else {
            parameterVariables.add(paramVariable);
            if (isSlurpy) {
                handleSlurpyParameter();
            } else {
                handleScalarParameter(paramVariable);
            }
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

    private void handleNamedParameter(Node paramVariable, String paramName) {
        LexerToken next = peekToken();

        // Named parameters are always optional and extracted from a hash in @_
        // Generate: my %h = @_; $named = (delete $h{named}) // default_value;
        
        Node defaultValue = null;
        String defaultOp = "//="; // default to defined-or operator for named params

        // Check for default value
        if (next.text.equals("=") || next.text.equals("||=") || next.text.equals("//=")) {
            defaultOp = consumeToken().text;
            defaultValue = parseDefaultValue(paramVariable);
        }

        // Generate the extraction code for named parameter
        // This returns a ListNode with the hash declaration and extraction statements
        Node extractionCode = generateNamedParameterExtraction(paramVariable, paramName, defaultValue, defaultOp);
        
        // Add the extraction statements to astNodes
        if (extractionCode instanceof ListNode) {
            astNodes.addAll(((ListNode) extractionCode).elements);
        } else {
            astNodes.add(extractionCode);
        }
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

    private Node generateNamedParameterExtraction(Node paramVariable, String paramName, Node defaultValue, String defaultOp) {
        // Named parameters are passed as key-value pairs in @_
        // Generate: { my %h = @_; $named = (delete $h{named}) // default }
        
        List<Node> statements = new ArrayList<>();
        
        // Create the hash only once for all named parameters
        if (namedArgsHashName == null) {
            namedArgsHashName = "__named_args__";
            IdentifierNode hashIdent = new IdentifierNode(namedArgsHashName, parser.tokenIndex);
            Node hashVar = new OperatorNode("%", hashIdent, parser.tokenIndex);
            
            // Create: my %__named_args__ = @_
            Node hashDecl = new BinaryOperatorNode(
                    "=",
                    new OperatorNode("my", hashVar, parser.tokenIndex),
                    atUnderscore(parser),
                    parser.tokenIndex);
            statements.add(hashDecl);
        }
        
        // Create: $__named_args__{named}
        // Note: use $ sigil for single element access, not %
        IdentifierNode hashIdent = new IdentifierNode(namedArgsHashName, parser.tokenIndex);
        // Hash subscripts need HashLiteralNode wrapping the key
        // Use ArrayList because the codegen may modify the list (e.g., auto-quoting identifiers)
        List<Node> keyList = new ArrayList<>();
        keyList.add(new IdentifierNode(paramName, parser.tokenIndex));
        HashLiteralNode hashKey = new HashLiteralNode(keyList, parser.tokenIndex);
        Node hashAccess = new BinaryOperatorNode(
                "{",
                new OperatorNode("$", hashIdent, parser.tokenIndex),
                hashKey,
                parser.tokenIndex);
        
        // Create: delete $__named_args__{named}
        // The delete operator expects its operand to be a ListNode
        Node deleteExpr = new OperatorNode("delete", new ListNode(List.of(hashAccess), parser.tokenIndex), parser.tokenIndex);
        
        Node extractionValue;
        if (defaultValue != null) {
            // (delete $h{named}) // defaultValue
            if (defaultOp.equals("=")) {
                defaultOp = "//";
            } else if (defaultOp.equals("||=")) {
                defaultOp = "||";
            } else if (defaultOp.equals("//=")) {
                defaultOp = "//";
            }
            
            extractionValue = new BinaryOperatorNode(
                    defaultOp,
                    deleteExpr,
                    defaultValue,
                    parser.tokenIndex);
        } else {
            extractionValue = deleteExpr;
        }
        
        // Add the extraction assignment with 'my' declaration
        // my $named = (delete $h{named}) // default
        Node myParam = new OperatorNode("my", paramVariable, parser.tokenIndex);
        statements.add(new BinaryOperatorNode("=", myParam, extractionValue, parser.tokenIndex));
        
        // Return a list node containing the hash declaration (if first time) and the extraction
        return new ListNode(statements, parser.tokenIndex);
    }

    private ListNode generateSignatureAST() {
        List<Node> allNodes = new ArrayList<>();

        // Add argument count validation
        allNodes.add(generateArgCountValidation());

        // Add parameter assignment from @_
        if (!parameterVariables.isEmpty()) {
            allNodes.add(generateParameterAssignment());
        }

        // Add default value assignments and named parameter extractions
        // (Named parameters are declared within their extraction code)
        allNodes.addAll(astNodes);

        return new ListNode(allNodes, parser.tokenIndex);
    }

    private Node generateArgCountValidation() {
        // If we have named parameters, we need different validation
        // Named parameters are passed as key-value pairs, so we need to allow extra arguments
        if (!namedParameterNodes.isEmpty()) {
            // With named parameters: minParams <= @_
            // (We don't check maxParams because named parameters can take any number of key-value pairs)
            return new BinaryOperatorNode(
                    "||",
                    new ListNode(List.of(
                            new BinaryOperatorNode("<=",
                                    new NumberNode(Integer.toString(minParams), parser.tokenIndex),
                                    atUnderscore(parser),
                                    parser.tokenIndex)
                    ), parser.tokenIndex),
                    dieWarnNode(parser, "die", new ListNode(List.of(
                            new StringNode("Bad number of arguments", parser.tokenIndex)), parser.tokenIndex)),
                    parser.tokenIndex);
        } else {
            // Without named parameters: minParams <= @_ <= maxParams
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
                    dieWarnNode(parser, "die", new ListNode(List.of(
                            new StringNode("Bad number of arguments", parser.tokenIndex)), parser.tokenIndex)),
                    parser.tokenIndex);
        }
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

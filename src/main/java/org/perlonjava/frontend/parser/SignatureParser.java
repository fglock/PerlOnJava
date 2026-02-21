package org.perlonjava.frontend.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.OperatorParser.dieWarnNode;
import static org.perlonjava.frontend.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.frontend.parser.PrototypeArgs.consumeArgsWithPrototype;

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
    private String subroutineName = null; // Optional subroutine name for error messages
    private boolean isMethod = false; // True if parsing method signature (has implicit $self)

    private SignatureParser(Parser parser) {
        this.parser = parser;
        this.isMethod = parser.isInMethod;
    }

    private SignatureParser(Parser parser, String subroutineName) {
        this.parser = parser;
        this.subroutineName = subroutineName;
        this.isMethod = parser.isInMethod;
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

    /**
     * Parses a Perl subroutine signature and generates the corresponding AST.
     *
     * @param parser The parser instance
     * @param subroutineName The name of the subroutine for error messages
     * @return A ListNode containing the generated AST nodes
     * @throws PerlCompilerException if the signature syntax is invalid
     */
    public static ListNode parseSignature(Parser parser, String subroutineName) {
        return new SignatureParser(parser, subroutineName).parse();
    }

    /**
     * Parses a Perl method signature and generates the corresponding AST.
     * Methods have an implicit $self parameter that affects argument counts in error messages.
     *
     * @param parser The parser instance
     * @param methodName The name of the method for error messages
     * @param isMethod True if this is a method (has implicit $self)
     * @return A ListNode containing the generated AST nodes
     * @throws PerlCompilerException if the signature syntax is invalid
     */
    public static ListNode parseSignature(Parser parser, String methodName, boolean isMethod) {
        SignatureParser sigParser = new SignatureParser(parser, methodName);
        sigParser.isMethod = isMethod;
        return sigParser.parse();
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

    /**
     * Generates AST for extracting a named parameter from @_.
     * 
     * <p>Named parameters are passed as key-value pairs in @_. This method generates:
     * <pre>{@code
     * my %__named_args__ = @_;  # Only once for all named params
     * my $paramName = (delete $__named_args__{paramName}) // defaultValue;
     * }</pre>
     * 
     * <p>The generated AST structure:
     * <ol>
     *   <li>Hash declaration: {@code my %__named_args__ = @_} (first param only)</li>
     *   <li>Delete operation: {@code delete $__named_args__{paramName}}</li>
     *   <li>Default application: {@code deleteExpr // defaultValue}</li>
     *   <li>Variable assignment: {@code my $paramName = extractionValue}</li>
     * </ol>
     *
     * @param paramVariable The variable node to assign the extracted value to
     * @param paramName The name of the parameter (for hash key lookup)
     * @param defaultValue The default value expression, or null if no default
     * @param defaultOp The default operator ("=", "//=", or "||=")
     * @return A ListNode containing the hash declaration and extraction statements
     */
    private Node generateNamedParameterExtraction(Node paramVariable, String paramName, Node defaultValue, String defaultOp) {
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
                            generateTooFewArgsMessage()), parser.tokenIndex), parser.tokenIndex),
                    parser.tokenIndex);
        } else {
            // Without named parameters: check both min and max
            // We need to check separately for too few vs too many to generate appropriate error messages
            
            // First check: minParams <= @_  (too few arguments check)
            Node tooFewCheck = new BinaryOperatorNode(
                    "||",
                    new ListNode(List.of(
                            new BinaryOperatorNode("<=",
                                    new NumberNode(Integer.toString(minParams), parser.tokenIndex),
                                    atUnderscore(parser),
                                    parser.tokenIndex)
                    ), parser.tokenIndex),
                    dieWarnNode(parser, "die", new ListNode(List.of(
                            generateTooFewArgsMessage()), parser.tokenIndex), parser.tokenIndex),
                    parser.tokenIndex);
            
            // Second check: @_ <= maxParams (too many arguments check)
            Node tooManyCheck = new BinaryOperatorNode(
                    "||",
                    new ListNode(List.of(
                            new BinaryOperatorNode("<=",
                                    atUnderscore(parser),
                                    new NumberNode(Integer.toString(maxParams), parser.tokenIndex),
                                    parser.tokenIndex)
                    ), parser.tokenIndex),
                    dieWarnNode(parser, "die", new ListNode(List.of(
                            generateTooManyArgsMessage()), parser.tokenIndex), parser.tokenIndex),
                    parser.tokenIndex);
            
            // Return both checks in sequence
            return new ListNode(List.of(tooFewCheck, tooManyCheck), parser.tokenIndex);
        }
    }

    private Node generateTooFewArgsMessage() {
        if (subroutineName != null) {
            // Generate: "Too few arguments for subroutine 'Package::name' (got " . (scalar(@_) + adjustment) . "; expected at least " . minParams . ")"
            String fullName = NameNormalizer.normalizeVariableName(subroutineName, parser.ctx.symbolTable.getCurrentPackage());
            // For methods, add 1 to account for implicit $self parameter (both in got and expected)
            int adjustedMin = isMethod ? minParams + 1 : minParams;
            
            Node argCount;
            if (isMethod) {
                // For methods: scalar(@_) + 1 (to account for $self that was already shifted)
                argCount = new BinaryOperatorNode("+",
                        new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                        new NumberNode("1", parser.tokenIndex),
                        parser.tokenIndex);
            } else {
                argCount = new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex);
            }
            
            return new BinaryOperatorNode(".",
                    new BinaryOperatorNode(".",
                            new BinaryOperatorNode(".",
                                    new BinaryOperatorNode(".",
                                            new StringNode("Too few arguments for subroutine '" + fullName + "' (got ", parser.tokenIndex),
                                            argCount,
                                            parser.tokenIndex),
                                    new StringNode("; expected at least ", parser.tokenIndex),
                                    parser.tokenIndex),
                            new NumberNode(Integer.toString(adjustedMin), parser.tokenIndex),
                            parser.tokenIndex),
                    new StringNode(")", parser.tokenIndex),
                    parser.tokenIndex);
        } else {
            return new StringNode("Too few arguments", parser.tokenIndex);
        }
    }

    private Node generateTooManyArgsMessage() {
        if (subroutineName != null) {
            // Generate: "Too many arguments for subroutine 'Package::name' (got " . (scalar(@_) + adjustment) . "; expected at most " . maxParams . ")"
            String fullName = NameNormalizer.normalizeVariableName(subroutineName, parser.ctx.symbolTable.getCurrentPackage());
            // For methods, add 1 to account for implicit $self parameter (both in got and expected)
            int adjustedMax = isMethod ? maxParams + 1 : maxParams;
            
            Node argCount;
            if (isMethod) {
                // For methods: scalar(@_) + 1 (to account for $self that was already shifted)
                argCount = new BinaryOperatorNode("+",
                        new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                        new NumberNode("1", parser.tokenIndex),
                        parser.tokenIndex);
            } else {
                argCount = new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex);
            }
            
            return new BinaryOperatorNode(".",
                    new BinaryOperatorNode(".",
                            new BinaryOperatorNode(".",
                                    new BinaryOperatorNode(".",
                                            new StringNode("Too many arguments for subroutine '" + fullName + "' (got ", parser.tokenIndex),
                                            argCount,
                                            parser.tokenIndex),
                                    new StringNode("; expected at most ", parser.tokenIndex),
                                    parser.tokenIndex),
                            new NumberNode(Integer.toString(adjustedMax), parser.tokenIndex),
                            parser.tokenIndex),
                    new StringNode(")", parser.tokenIndex),
                    parser.tokenIndex);
        } else {
            return new StringNode("Too many arguments", parser.tokenIndex);
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

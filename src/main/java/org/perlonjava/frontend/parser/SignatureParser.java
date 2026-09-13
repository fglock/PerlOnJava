package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.operators.WarnDie;

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
    private String slurpySigil;
    private Node slurpyVariable;
    private int positionalParameterCount = 0;
    private final List<String> namedParameterNames = new ArrayList<>();
    private final List<String> requiredNamedParameterNames = new ArrayList<>();
    private final List<Node> defaultValueNodes = new ArrayList<>();
    private boolean hasOptional = false;
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
     * @param parser         The parser instance
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
     * @param parser     The parser instance
     * @param methodName The name of the method for error messages
     * @param isMethod   True if this is a method (has implicit $self)
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
        int paramStartIndex = parser.tokenIndex;

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
            parser.throwError(paramStartIndex, "Slurpy parameter not last");
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

        if (paramName != null && paramName.equals("_")) {
            parser.throwError(paramStartIndex, "Can't use global " + sigil + "_ in subroutine signature");
        }

        // Named parameters must have a name
        if (isNamed && paramName == null) {
            parser.throwError("Named parameters must actually have a name");
        }

        // Check for illegal operator after parameter (e.g. $b += 1)
        LexerToken afterParam = peekToken();
        if (paramName != null && !afterParam.text.equals(",") && !afterParam.text.equals(")")
                && !afterParam.text.equals("=") && !afterParam.text.equals("//=") && !afterParam.text.equals("||=")
                && !afterParam.text.equals("$") && !afterParam.text.equals("@") && !afterParam.text.equals("%")) {
            if (afterParam.type == LexerTokenType.OPERATOR) {
                parser.throwError("Illegal operator following parameter in a subroutine signature");
            }
        }

        // Create parameter variable or undef placeholder
        Node paramVariable = createParameterVariable(sigil, paramName);

        if (isNamed) {
            // Named parameters are handled separately, not part of @_ unpacking
            namedParameterNodes.add(paramVariable);
            namedParameterNames.add(paramName);
            handleNamedParameter(paramVariable, paramName);
        } else {
            parameterVariables.add(paramVariable);
            if (isSlurpy) {
                slurpyVariable = paramVariable;
                slurpySigil = sigil;
                handleSlurpyParameter();
            } else {
                positionalParameterCount++;
                handleScalarParameter(paramVariable, paramStartIndex);
            }
        }

        // Make this parameter visible to subsequent defaults and to the body,
        // but only after its own default expression has been parsed.  Perl
        // resolves `$a` in `($a = $a . "x")` as the package variable; the
        // signature lexical becomes visible after that expression.
        if (paramName != null) {
            String variable = sigil + paramName;
            if (parser.ctx.symbolTable.getVariableIndexInCurrentScope(variable) != -1
                    && org.perlonjava.runtime.runtimetypes.WarningFlags.ckWarnForScope(
                            parser.ctx.symbolTable, "shadow")) {
                WarnDie.warn(new org.perlonjava.runtime.runtimetypes.RuntimeScalar(
                                "\"my\" variable " + variable + " masks earlier declaration in same scope"),
                        new org.perlonjava.runtime.runtimetypes.RuntimeScalar(
                                parser.ctx.errorUtil.warningLocation(paramStartIndex)));
            }
            parser.ctx.symbolTable.addVariable(sigil + paramName, "my", (OperatorNode) paramVariable);
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

        LexerToken next = peekToken();
        if (next.text.equals("=") || next.text.equals("//=") || next.text.equals("||=")) {
            parser.throwError("A slurpy parameter may not have a default value");
        }

        // Verify no more parameters after slurpy
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

    private void handleScalarParameter(Node paramVariable, int paramStartIndex) {
        LexerToken next = peekToken();

        // Check for default value
        if (next.text.equals("=") || next.text.equals("||=") || next.text.equals("//=")) {
            hasOptional = true;
            String defaultOp = consumeToken().text;
            Node defaultValue = parseDefaultValue(paramVariable);

            if (defaultValue != null) {
                astNodes.add(generateDefaultAssignment(paramVariable, defaultValue, defaultOp, maxParams));
            }
        } else {
            if (hasOptional) {
                parser.throwError(paramStartIndex, "Mandatory parameter follows optional parameter");
            }
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
        } else {
            requiredNamedParameterNames.add(paramName);
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
            // An anonymous parameter still evaluates its default expression.
            // There is simply no lexical slot to assign the result to.
            if (op.equals("=")) {
                return new BinaryOperatorNode(
                        "&&",
                        new BinaryOperatorNode(
                                "<",
                                atUnderscore(parser),
                                new NumberNode(Integer.toString(paramIndex + 1), parser.tokenIndex),
                                parser.tokenIndex),
                        defaultValue,
                        parser.tokenIndex);
            }
            return defaultValue;
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

        // Parse the default value expression at comma precedence, so that
        // complex expressions (ternary, comparisons, logical ops) are included
        // but ',' and ')' correctly terminate the default value.
        Node value = parser.parseExpression(parser.getPrecedence(","));
        defaultValueNodes.add(value);
        // The parameter lexical is not in scope in its own default expression.
        // Preserve that distinction even though code generation happens after
        // the complete signature scope has been registered.
        if (paramVariable instanceof OperatorNode op
                && "$".equals(op.operator)
                && op.operand instanceof IdentifierNode id) {
            qualifySelfReference(value, id.name);
        }
        return value;
    }

    private void qualifySelfReference(Node node, String name) {
        if (node instanceof OperatorNode op) {
            if ("$".equals(op.operator) && op.operand instanceof IdentifierNode id
                    && name.equals(id.name)) {
                id.name = parser.ctx.symbolTable.getCurrentPackage() + "::" + name;
            } else if (op.operand != null) {
                qualifySelfReference(op.operand, name);
            }
        } else if (node instanceof BinaryOperatorNode binary) {
            qualifySelfReference(binary.left, name);
            qualifySelfReference(binary.right, name);
        } else if (node instanceof ListNode list) {
            for (Node element : list.elements) qualifySelfReference(element, name);
        } else if (node instanceof HashLiteralNode hash) {
            for (Node element : hash.elements) qualifySelfReference(element, name);
        } else if (node instanceof ArrayLiteralNode array) {
            for (Node element : array.elements) qualifySelfReference(element, name);
        }
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
     * @param paramName     The name of the parameter (for hash key lookup)
     * @param defaultValue  The default value expression, or null if no default
     * @param defaultOp     The default operator ("=", "//=", or "||=")
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
                    namedArgsSource(),
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

    private Node namedArgsSource() {
        Node odd = new BinaryOperatorNode("%",
                new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                new NumberNode("2", parser.tokenIndex), parser.tokenIndex);
        Node pad = new TernaryOperatorNode("?", odd,
                new ListNode(List.of(new OperatorNode("undef", null, parser.tokenIndex)), parser.tokenIndex),
                new ListNode(List.of(), parser.tokenIndex), parser.tokenIndex);
        return new ListNode(List.of(atUnderscore(parser), pad), parser.tokenIndex);
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

        // Named parameters are deleted from the temporary hash by the
        // extraction nodes. A trailing slurpy parameter receives the
        // remaining key/value pairs, excluding named parameters.
        if (slurpyVariable != null && namedArgsHashName != null) {
            if (!(slurpyVariable instanceof OperatorNode op && op.operator.equals("undef"))) {
                Node remaining = new OperatorNode("%",
                        new IdentifierNode(namedArgsHashName, parser.tokenIndex), parser.tokenIndex);
                allNodes.add(new BinaryOperatorNode("=", slurpyVariable, remaining, parser.tokenIndex));
                if ("@".equals(slurpySigil)) {
                    Node odd = new BinaryOperatorNode("%",
                            new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                            new NumberNode("2", parser.tokenIndex), parser.tokenIndex);
                    Node trim = new OperatorNode("pop",
                            new ListNode(List.of(slurpyVariable), parser.tokenIndex), parser.tokenIndex);
                    allNodes.add(new BinaryOperatorNode("&&", odd, trim, parser.tokenIndex));
                }
            }
        }

        ListNode signature = new ListNode(allNodes, parser.tokenIndex);
        int adjustment = isMethod ? 1 : 0;
        signature.setAnnotation("signatureMinArgs", minParams + adjustment);
        signature.setAnnotation("signatureMaxArgs",
                maxParams == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxParams + adjustment);
        signature.setAnnotation("signatureNamedParams", new ArrayList<>(namedParameterNames));
        signature.setAnnotation("signatureRequiredNamedParams", new ArrayList<>(requiredNamedParameterNames));
        signature.setAnnotation("signaturePositionalCount", positionalParameterCount);
        if (slurpySigil != null) {
            signature.setAnnotation("signatureSlurpySigil", slurpySigil);
        }
        signature.setAnnotation("signatureSubName",
                NameNormalizer.normalizeVariableName(
                        subroutineName == null ? "__ANON__" : subroutineName,
                        parser.ctx.symbolTable.getCurrentPackage()));
        signature.setAnnotation("signatureDefaultValueNodes", new ArrayList<>(defaultValueNodes));
        return signature;
    }

    private Node generateArgCountValidation() {
        // If we have named parameters, we need different validation
        // Named parameters are passed as key-value pairs, so we need to allow extra arguments
        if (!namedParameterNodes.isEmpty()) {
            // With named parameters: minParams <= @_
            // (We don't check maxParams because named parameters can take any number of key-value pairs)
            Node minimum = new BinaryOperatorNode(
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
            return slurpySigil != null && slurpySigil.equals("%")
                    ? new ListNode(List.of(minimum, generateOddNamedArgsValidation()), parser.tokenIndex)
                    : minimum;
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
            if ("%".equals(slurpySigil)) {
                return new ListNode(List.of(tooFewCheck, tooManyCheck,
                        generateOddNamedArgsValidation()), parser.tokenIndex);
            }
            return new ListNode(List.of(tooFewCheck, tooManyCheck), parser.tokenIndex);
        }
    }

    private Node generateOddNamedArgsValidation() {
        int fixedArgs = positionalParameterCount + (isMethod ? 1 : 0);
        Node remaining = new BinaryOperatorNode("-",
                new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                new NumberNode(Integer.toString(fixedArgs), parser.tokenIndex),
                parser.tokenIndex);
        Node even = new BinaryOperatorNode("==",
                new BinaryOperatorNode("%", remaining,
                        new NumberNode("2", parser.tokenIndex), parser.tokenIndex),
                new NumberNode("0", parser.tokenIndex), parser.tokenIndex);
        Node noSlurpyArgs = new BinaryOperatorNode("<=",
                new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                new NumberNode(Integer.toString(fixedArgs), parser.tokenIndex), parser.tokenIndex);
        Node message = new BinaryOperatorNode(".",
                new StringNode("Odd name/value argument for subroutine '"
                        + NameNormalizer.normalizeVariableName(
                                subroutineName == null ? "__ANON__" : subroutineName,
                                parser.ctx.symbolTable.getCurrentPackage()) + "'", parser.tokenIndex),
                new BinaryOperatorNode("x", new StringNode("", parser.tokenIndex),
                        new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex), parser.tokenIndex),
                parser.tokenIndex);
        return new BinaryOperatorNode("||", new BinaryOperatorNode("||", noSlurpyArgs, even,
                parser.tokenIndex),
                dieWarnNode(parser, "die", new ListNode(List.of(
                        message), parser.tokenIndex),
                        parser.tokenIndex), parser.tokenIndex);
    }

    private Node generateTooFewArgsMessage() {
            // Generate: "Too few arguments for subroutine 'Package::name' (got " . (scalar(@_) + adjustment) . "; expected at least " . minParams . ")"
            String fullName = NameNormalizer.normalizeVariableName(
                    subroutineName == null ? "__ANON__" : subroutineName,
                    parser.ctx.symbolTable.getCurrentPackage());
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
                                    new StringNode(minParams == maxParams ? "; expected " : "; expected at least ", parser.tokenIndex),
                                    parser.tokenIndex),
                            new NumberNode(Integer.toString(adjustedMin), parser.tokenIndex),
                            parser.tokenIndex),
                    new StringNode(")", parser.tokenIndex),
                    parser.tokenIndex);
    }

    private Node generateTooManyArgsMessage() {
            // Generate: "Too many arguments for subroutine 'Package::name' (got " . (scalar(@_) + adjustment) . "; expected at most " . maxParams . ")"
            String fullName = NameNormalizer.normalizeVariableName(
                    subroutineName == null ? "__ANON__" : subroutineName,
                    parser.ctx.symbolTable.getCurrentPackage());
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
                                    new StringNode(minParams == maxParams ? "; expected " : "; expected at most ", parser.tokenIndex),
                                    parser.tokenIndex),
                            new NumberNode(Integer.toString(adjustedMax), parser.tokenIndex),
                            parser.tokenIndex),
                    new StringNode(")", parser.tokenIndex),
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

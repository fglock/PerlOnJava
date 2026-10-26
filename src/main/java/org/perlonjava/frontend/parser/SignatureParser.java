package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;
import java.util.concurrent.atomic.AtomicLong;
import org.perlonjava.runtime.operators.WarnDie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final AtomicLong CUSTOM_TYPE_REIFIER_SEQUENCE = new AtomicLong();

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
    private final List<String> invocantNames = new ArrayList<>();
    private final List<String> positionalRequiredNames = new ArrayList<>();
    private final List<String> positionalOptionalNames = new ArrayList<>();
    private final List<String> namedRequiredMetadataNames = new ArrayList<>();
    private final List<String> namedOptionalNames = new ArrayList<>();
    private final Set<String> functionParametersParameterNames = new java.util.HashSet<>();
    private final Map<String, String> parameterTypeNames = new LinkedHashMap<>();
    private final Map<String, RuntimeScalar> parameterTypeObjects = new LinkedHashMap<>();
    private final Map<String, String> parameterTypeObjectVariables = new LinkedHashMap<>();
    private RuntimeScalar lastCustomReifiedTypeValue;
    private final List<Node> defaultValueNodes = new ArrayList<>();
    private boolean hasOptional = false;
    private boolean hasNamedParameter = false;
    private String namedArgsHashName = null; // Track the hash name for named parameters
    private String slurpyParameterName;
    private int typedParameterCount;
    private String subroutineName = null; // Optional subroutine name for error messages
    private boolean isMethod = false; // True if parsing method signature (has implicit $self)
    private boolean parsingFunctionParametersInvocant;
    private int signatureOpenParenIndex;

    private SignatureParser(Parser parser) {
        this.parser = parser;
        this.isMethod = parser.isInMethod && !parser.parsingFunctionParametersMethod;
    }

    private SignatureParser(Parser parser, String subroutineName) {
        this.parser = parser;
        this.subroutineName = subroutineName;
        this.isMethod = parser.isInMethod && !parser.parsingFunctionParametersMethod;
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
        peekToken();
        signatureOpenParenIndex = parser.tokenIndex;
        consumeOpenParen();

        // Handle empty signature
        if (peekToken().text.equals(")")) {
            if (parser.parsingFunctionParametersMethod) {
                parser.throwError("Method signature must declare an invocant");
            }
            consumeCloseParen();
            return generateSignatureAST();
        }

        if (peekToken().text.equals(",")) {
            parser.throwErrorAtToken(previousSignificantToken(parser.tokenIndex), "syntax error");
        }

        int explicitInvocantCount = countParametersBeforeInvocantSeparator(signatureOpenParenIndex);
        if (parser.parsingFunctionParametersDeclaration
                && !parser.parsingFunctionParametersMethod
                && !parser.parsingFunctionParametersOptionalInvocant
                && explicitInvocantCount >= 0) {
            parser.throwError(functionParametersInvocantName(signatureOpenParenIndex)
                    + " not allowed here");
        }
        int configuredInvocants = parser.functionParametersInvocantCount;
        if (parser.parsingFunctionParametersMethod && explicitInvocantCount >= 0
                && explicitInvocantCount != configuredInvocants) {
            throwInvocantCountMismatch(explicitInvocantCount, configuredInvocants);
        }

        boolean functionParametersInvocantSeparatorConsumed = false;
        boolean functionParametersHasInvocants = parser.parsingFunctionParametersMethod
                || (parser.parsingFunctionParametersOptionalInvocant
                && hasOptionalInvocantSeparator(signatureOpenParenIndex));
        int functionParametersInvocantCount = functionParametersHasInvocants
                ? parser.functionParametersInvocantCount : 0;
        // Parse parameters
        while (true) {
            parsingFunctionParametersInvocant = functionParametersHasInvocants
                    && positionalParameterCount < functionParametersInvocantCount;
            parseParameter();
            parsingFunctionParametersInvocant = false;

            // Check what comes after the parameter
            LexerToken next = peekToken();
            if (functionParametersHasInvocants
                    && !functionParametersInvocantSeparatorConsumed) {
                if (next.text.equals(":")) {
                    if (invocantNames.size() != functionParametersInvocantCount) {
                        throwInvocantCountMismatch(invocantNames.size(), functionParametersInvocantCount);
                    }
                    consumeToken();
                    functionParametersInvocantSeparatorConsumed = true;
                    if (peekToken().text.equals(")")) {
                        break;
                    }
                } else if (next.text.equals(",")
                        && invocantNames.size() < functionParametersInvocantCount) {
                    consumeCommas();
                    continue;
                } else {
                    parser.throwError("Method signature must separate its invocant with ':'");
                }
            } else if (next.text.equals(")")) {
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
                throwMalformedSeparator();
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

        Node typeConstraint = null;
        boolean parenthesizedTypeConstraint = false;
        RuntimeScalar parenthesizedTypeValue = null;
        RuntimeScalar customReifiedTypeValue = null;
        if (parser.parsingFunctionParametersDeclaration && parser.functionParametersTypes) {
            if (peekToken().text.equals("(")) {
                consumeToken();
                parenthesizedTypeConstraint = true;
                int typeSourceStart = parser.tokenIndex;
                typeConstraint = parser.parseExpression(0);
                int typeSourceEnd = parser.tokenIndex;
                if (!peekToken().text.equals(")")) {
                    parser.throwError("Malformed Function::Parameters type constraint");
                }
                consumeToken();
                if (parser.functionParametersTypeReifierCallback != null) {
                    customReifiedTypeValue = callCustomTypeReifier(
                            typeSource(typeSourceStart, typeSourceEnd), paramStartIndex);
                    typeConstraint = customTypeConstraintReference(customReifiedTypeValue, paramStartIndex);
                }
            } else if (peekToken().type == LexerTokenType.IDENTIFIER) {
                typeConstraint = parseUnparenthesizedTypeConstraint();
                customReifiedTypeValue = lastCustomReifiedTypeValue;
            }
            if (parenthesizedTypeConstraint
                    && "moose".equals(parser.functionParametersTypeReifier)) {
                RuntimeList evaluatedType = SpecialBlockParser.runSpecialBlock(
                        parser, "BEGIN", typeConstraint, RuntimeContextType.SCALAR);
                parenthesizedTypeValue = evaluatedType.scalar();
                if (RuntimeScalarType.blessedId(parenthesizedTypeValue) == 0) {
                    Node reifierCall = mooseTypeReifierCall(
                            parenthesizedTypeValue.toString(), paramStartIndex);
                    customReifiedTypeValue = SpecialBlockParser.runSpecialBlock(
                            parser, "BEGIN", reifierCall, RuntimeContextType.SCALAR).scalar();
                    typeConstraint = customTypeConstraintReference(
                            customReifiedTypeValue, paramStartIndex);
                } else {
                    customReifiedTypeValue = parenthesizedTypeValue;
                    typeConstraint = customTypeConstraintReference(
                            customReifiedTypeValue, paramStartIndex);
                }
            } else if (customReifiedTypeValue == null) {
                typeConstraint = reifyQuotedTypeName(typeConstraint, paramStartIndex);
            }
            if (typeConstraint != null && customReifiedTypeValue == null) {
                RuntimeScalar evaluatedType = SpecialBlockParser.runSpecialBlock(
                        parser, "BEGIN", typeConstraint, RuntimeContextType.SCALAR).scalar();
                if (RuntimeScalarType.blessedId(evaluatedType) != 0) {
                    customReifiedTypeValue = evaluatedType;
                    typeConstraint = customTypeConstraintReference(
                            customReifiedTypeValue, paramStartIndex);
                }
            }
            if (typeConstraint != null && peekToken().text.equals(":")) {
                consumeToken();
                isNamed = true;
            }
        }

        LexerToken sigilToken = consumeToken();
        String sigil = sigilToken.text;

        boolean immediatelyFollowedByHash = parser.tokenIndex < parser.tokens.size()
                && "#".equals(parser.tokens.get(parser.tokenIndex).text);
        if (validateSigil(sigil, paramStartIndex, isNamed, immediatelyFollowedByHash)) {
            recoverImmediateHashAfterSigil(paramStartIndex);
            return;
        }

        // A slurpy array or hash is permitted after named parameters to
        // collect their unrecognized arguments.  Only a later ordinary
        // positional scalar is forbidden.
        if (!isNamed && hasNamedParameter && sigil.equals("$")) {
            parser.throwError(paramStartIndex, parser.parsingFunctionParametersDeclaration
                    ? "positional parameter follows named parameter"
                    : "Positional parameter follows named parameter");
        }

        // Check if this is a slurpy parameter
        boolean isSlurpy = sigil.equals("@") || sigil.equals("%");

        // Keep parsing after a slurpy parameter so Perl can report every
        // following invalid parameter in the same signature.
        if (hasSlurpy) {
            String followingName = sigil
                    + (peekToken().type == LexerTokenType.IDENTIFIER ? peekToken().text : "");
            String message = parser.parsingFunctionParametersDeclaration
                    ? "\"" + followingName + "\" can't appear after slurpy parameter \""
                            + slurpyParameterName + "\""
                    : isSlurpy ? "Multiple slurpy parameters not allowed" : "Slurpy parameter not last";
            if (!isSlurpy && hasDefaultValueBeforeCloseParen()) {
                parser.deferErrorAtToken(lastSignatureTokenBeforeCloseParen(), message);
            } else {
                parser.deferErrorAtTokenWithoutTrailingCommaWhitespace(paramStartIndex, message);
            }
        }

        // Named parameters cannot be slurpy
        if (isNamed && isSlurpy) {
            throwNamedSlurpyParameterError(sigil);
        }

        // Parse parameter name (if present)
        String paramName = null;
        if (peekToken().type == LexerTokenType.IDENTIFIER) {
            paramName = consumeToken().text;
        }

        if (paramName != null && paramName.equals("_")) {
            var location = parser.ctx.errorUtil.getSourceLocationAccurate(signatureOpenParenIndex);
            throw new PerlCompilerException("Can't use global " + sigil + "_ in subroutine signature at "
                    + location.fileName() + " line " + location.lineNumber() + ", near \"("
                    + sigil + "_\"\n");
        }

        String metadataName = paramName == null ? sigil : sigil + paramName;
        if (parenthesizedTypeValue != null
                && RuntimeScalarType.blessedId(parenthesizedTypeValue) != 0) {
            parameterTypeObjects.put(metadataName, parenthesizedTypeValue);
        }
        if (customReifiedTypeValue != null
                && customReifiedTypeValue.type != RuntimeScalarType.UNDEF) {
            parameterTypeObjects.put(metadataName, customReifiedTypeValue);
        }
        if (typeConstraint instanceof AbstractNode typedNode
                && typedNode.getAnnotation("functionParametersTypeObjectVariable") instanceof String variableName) {
            parameterTypeObjectVariables.put(metadataName, variableName);
        }
        if (parser.parsingFunctionParametersDeclaration && paramName != null
                && !functionParametersParameterNames.add(metadataName)) {
            parser.throwError(paramStartIndex,
                    metadataName + " can't appear twice in the same parameter list");
        }
        if (isNamed && paramName != null && namedParameterNames.contains(paramName)) {
            parser.throwError(paramStartIndex, "Duplicated subroutine parameter name");
        }

        // Named parameters must have a name
        if (isNamed && paramName == null) {
            parser.throwError(previousSignificantToken(paramStartIndex),
                    "Named parameters must actually have a name");
        }

        // Check for illegal operator after parameter (e.g. $b += 1)
        LexerToken afterParam = peekToken();
        if (parser.parsingFunctionParametersMethod && isNamed && afterParam.text.equals(":")) {
            parser.throwError(paramStartIndex,
                    "invocant " + sigil + paramName + " not allowed as a named parameter here");
        }
        if (paramName != null && !afterParam.text.equals(",") && !afterParam.text.equals(")")
                && !afterParam.text.equals("=") && !afterParam.text.equals("//=") && !afterParam.text.equals("||=")
                && !afterParam.text.equals("$") && !afterParam.text.equals("@") && !afterParam.text.equals("%")
                && !(parsingFunctionParametersInvocant && afterParam.text.equals(":"))) {
            if (afterParam.type == LexerTokenType.OPERATOR) {
                throwMalformedSeparator();
            }
        }

        // Create parameter variable or undef placeholder
        Node paramVariable = createParameterVariable(sigil, paramName);
        OperatorNode typeConstraintVariable = null;
        if (typeConstraint != null) {
            if (paramName == null || (isSlurpy && !"@".equals(sigil))) {
                parser.throwError(paramStartIndex,
                        "Function::Parameters type constraints require a named scalar or array parameter");
            }
            if (parser.functionParametersCheckArgumentTypes) {
                String typeVariableName = "__function_parameters_type_" + (++typedParameterCount);
                OperatorNode typeVariable = new OperatorNode("$",
                        new IdentifierNode(typeVariableName, paramStartIndex), paramStartIndex);
                OperatorNode typeDeclaration = new OperatorNode("my", typeVariable, paramStartIndex);
                typeDeclaration.setAnnotation("signatureParameterDeclaration", true);
                astNodes.add(new BinaryOperatorNode("=", typeDeclaration, typeConstraint, paramStartIndex));
                parser.ctx.symbolTable.addVariable("$" + typeVariableName, "my", typeVariable);
                typeConstraintVariable = typeVariable;
            }
            String typeName = metadataTypeName(typeConstraint);
            if (typeName != null) {
                parameterTypeNames.put(metadataName, typeName);
                if (typeName.startsWith("moose:") && !parameterTypeObjects.containsKey(metadataName)) {
                    Node reifiedMetadataType = mooseTypeReifierCall(
                            typeName.substring("moose:".length()), paramStartIndex);
                    RuntimeScalar evaluatedType = SpecialBlockParser.runSpecialBlock(
                            parser, "BEGIN", reifiedMetadataType, RuntimeContextType.SCALAR).scalar();
                    parameterTypeObjects.put(metadataName, evaluatedType);
                }
            }
        }

        if (isNamed) {
            hasNamedParameter = true;
            LexerToken namedDefault = peekToken();
            if (hasOptional && !namedDefault.text.equals("=")
                    && !namedDefault.text.equals("//=") && !namedDefault.text.equals("||=")) {
                parser.throwError(paramStartIndex, parser.parsingFunctionParametersDeclaration
                        ? "optional positional parameter is followed by required named parameter"
                        : "Mandatory parameter follows optional parameter");
            }
            // Named parameters are handled separately, not part of @_ unpacking
            namedParameterNodes.add(paramVariable);
            namedParameterNames.add(paramName);
            handleNamedParameter(paramVariable, paramName);
            if (requiredNamedParameterNames.contains(paramName)) {
                namedRequiredMetadataNames.add(metadataName);
            } else {
                namedOptionalNames.add(metadataName);
            }
        } else {
            parameterVariables.add(paramVariable);
            if (isSlurpy) {
                slurpyVariable = paramVariable;
                slurpySigil = sigil;
                slurpyParameterName = metadataName;
                handleSlurpyParameter();
            } else {
                positionalParameterCount++;
                boolean wasOptional = hasOptional;
                handleScalarParameter(paramVariable, paramStartIndex);
                if (parsingFunctionParametersInvocant) {
                    invocantNames.add(metadataName);
                } else if (hasOptional) {
                    positionalOptionalNames.add(metadataName);
                } else {
                    positionalRequiredNames.add(metadataName);
                }
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
        Node inlineTypeCheck = null;
        if (customReifiedTypeValue != null && typeConstraintVariable != null
                && parser.functionParametersCheckArgumentTypes) {
            inlineTypeCheck = inlineCustomTypeCheck(
                    typeConstraint, sigil + paramName, paramStartIndex);
        }
        if (typeConstraintVariable != null && parser.functionParametersCheckArgumentTypes) {
            Node hasCanMethod = typeConstraintMethodCall(typeConstraintVariable,
                    "can", new StringNode("has_coercion", paramStartIndex), paramStartIndex);
            Node hasCoercion = new BinaryOperatorNode("&&", hasCanMethod,
                    typeConstraintNoArgumentMethodCall(
                            typeConstraintVariable, "has_coercion", paramStartIndex),
                    paramStartIndex);
            Node coercedValue = new BinaryOperatorNode("=", paramVariable,
                    typeConstraintMethodCall(
                            typeConstraintVariable, "coerce", paramVariable, paramStartIndex),
                    paramStartIndex);
            astNodes.add(new IfNode("if", hasCoercion,
                    new BlockNode(new ArrayList<>(List.of(coercedValue)), paramStartIndex),
                    null, paramStartIndex));

            Node failure;
            Node message;
            if (inlineTypeCheck != null) {
                failure = new OperatorNode("!", inlineTypeCheck, paramStartIndex);
                message = typeConstraintMethodCall(
                        typeConstraintVariable, "get_message", paramVariable, paramStartIndex);
            } else {
                Node check = typeConstraintMethodCall(
                        typeConstraintVariable, "check", paramVariable, paramStartIndex);
                failure = new OperatorNode("!", check, paramStartIndex);
                message = typeConstraintMethodCall(
                        typeConstraintVariable, "get_message", paramVariable, paramStartIndex);
            }
            if (parser.parsingFunctionParametersDeclaration) {
                int parameterIndex = isNamed
                        ? positionalParameterCount + namedParameterNames.size()
                        : positionalParameterCount;
                String keyword = parser.functionParametersKeyword == null
                        ? "fun" : parser.functionParametersKeyword;
                String name = subroutineName == null ? "(anon)" : subroutineName;
                String prefix = "In " + keyword + " " + name + ": parameter "
                        + parameterIndex + " (" + sigil + paramName + "): ";
                message = new BinaryOperatorNode(".", new StringNode(prefix, paramStartIndex),
                        message, paramStartIndex);
            }
            Node die = new OperatorNode("die", new ListNode(List.of(message), paramStartIndex), paramStartIndex);
            astNodes.add(new IfNode("if", failure,
                    new BlockNode(new ArrayList<>(List.of(die)), paramStartIndex), null, paramStartIndex));
        }
    }

    private static Node requireMooseTypeConstraints(Node expression, int index) {
        Node require = new OperatorNode("require", ListNode.makeList(
                new StringNode("Moose/Util/TypeConstraints.pm", index)), index);
        return new TernaryOperatorNode("?", require, expression,
                new OperatorNode("undef", null, index), index);
    }

    private static Node mooseTypeReifierCall(String typeName, int index) {
        Node reifier = new OperatorNode("&", new IdentifierNode(
                "Moose::Util::TypeConstraints::find_or_create_isa_type_constraint", index), index);
        Node call = new BinaryOperatorNode("(", reifier,
                new ListNode(List.of(new StringNode(typeName, index)), index), index);
        return requireMooseTypeConstraints(call, index);
    }

    private boolean hasOptionalInvocantSeparator(int openParenIndex) {
        int expected = parser.functionParametersInvocantCount;
        if (expected <= 0) return false;
        int depthParen = 0;
        int depthSquare = 0;
        int depthBrace = 0;
        int parameterSigils = 0;
        int commas = 0;
        int pendingTernaries = 0;
        boolean segmentHasParameter = false;
        for (int i = openParenIndex + 1; i < parser.tokens.size(); i++) {
            LexerToken token = parser.tokens.get(i);
            if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.NEWLINE) continue;
            String text = token.text;
            if (depthParen == 0 && depthSquare == 0 && depthBrace == 0) {
                if (")".equals(text)) return false;
                if (!segmentHasParameter
                        && ("$".equals(text) || "@".equals(text) || "%".equals(text))) {
                    parameterSigils++;
                    segmentHasParameter = true;
                } else if (",".equals(text)) {
                    commas++;
                    segmentHasParameter = false;
                } else if ("?".equals(text)) {
                    pendingTernaries++;
                } else if (":".equals(text)) {
                    if (pendingTernaries > 0) {
                        pendingTernaries--;
                    } else {
                        return parameterSigils == expected && commas == expected - 1;
                    }
                }
            }
            switch (text) {
                case "(" -> depthParen++;
                case ")" -> depthParen--;
                case "[" -> depthSquare++;
                case "]" -> depthSquare--;
                case "{" -> depthBrace++;
                case "}" -> depthBrace--;
            }
        }
        return false;
    }

    private int countParametersBeforeInvocantSeparator(int openParenIndex) {
        int depthParen = 0;
        int depthSquare = 0;
        int depthBrace = 0;
        int parameterCount = 0;
        int pendingTernaries = 0;
        boolean segmentHasParameter = false;
        for (int i = openParenIndex + 1; i < parser.tokens.size(); i++) {
            LexerToken token = parser.tokens.get(i);
            if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.NEWLINE) continue;
            String text = token.text;
            if (depthParen == 0 && depthSquare == 0 && depthBrace == 0) {
                if (")".equals(text)) return -1;
                if (":".equals(text)) {
                    if (pendingTernaries > 0) {
                        pendingTernaries--;
                        segmentHasParameter = true;
                        continue;
                    }
                    if (segmentHasParameter && parameterCount > 0) return parameterCount;
                    // A leading colon starts a named parameter, not an
                    // invocant separator.
                    segmentHasParameter = true;
                    continue;
                }
                if (",".equals(text)) {
                    segmentHasParameter = false;
                    continue;
                }
                if (!segmentHasParameter
                        && ("$".equals(text) || "@".equals(text) || "%".equals(text))) {
                    parameterCount++;
                    segmentHasParameter = true;
                    continue;
                }
                if ("?".equals(text)) pendingTernaries++;
                segmentHasParameter = true;
            }
            switch (text) {
                case "(" -> depthParen++;
                case ")" -> depthParen--;
                case "[" -> depthSquare++;
                case "]" -> depthSquare--;
                case "{" -> depthBrace++;
                case "}" -> depthBrace--;
            }
        }
        return -1;
    }

    private String functionParametersInvocantName(int openParenIndex) {
        for (int i = openParenIndex + 1; i < parser.tokens.size(); i++) {
            LexerToken token = parser.tokens.get(i);
            if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.NEWLINE) continue;
            if (!"$".equals(token.text) && !"@".equals(token.text) && !"%".equals(token.text)) {
                return "invocant";
            }
            String sigil = token.text;
            for (int j = i + 1; j < parser.tokens.size(); j++) {
                LexerToken name = parser.tokens.get(j);
                if (name.type == LexerTokenType.WHITESPACE || name.type == LexerTokenType.NEWLINE) continue;
                if (name.type == LexerTokenType.IDENTIFIER) return "invocant " + sigil + name.text;
                return "invocant " + sigil;
            }
        }
        return "invocant";
    }

    private void throwInvocantCountMismatch(int actual, int expected) {
        String keyword = parser.functionParametersKeyword == null
                ? "method" : parser.functionParametersKeyword;
        String name = subroutineName == null ? "(anon)" : subroutineName;
        parser.throwError("In " + keyword + " " + name
                + ": number of invocants in parameter list (" + actual
                + ") differs from number of invocants in keyword definition (" + expected + ")");
    }

    private static Node typeConstraintMethodCall(
            Node invocant, String method, Node argument, int index) {
        Node call = new BinaryOperatorNode("(",
                new OperatorNode("&", new IdentifierNode(method, index), index),
                new ListNode(List.of(argument), index), index);
        return new BinaryOperatorNode("->", invocant, call, index);
    }

    private static Node typeConstraintNoArgumentMethodCall(Node invocant, String method, int index) {
        Node call = new BinaryOperatorNode("(",
                new OperatorNode("&", new IdentifierNode(method, index), index),
                new ListNode(List.of(), index), index);
        return new BinaryOperatorNode("->", invocant, call, index);
    }

    private Node inlineCustomTypeCheck(
            Node typeReference, String parameterName, int index) {
        Node canMethod = typeConstraintMethodCall(
                typeReference, "can", new StringNode("can_be_inlined", index), index);
        if (!SpecialBlockParser.runSpecialBlock(
                parser, "BEGIN", canMethod, RuntimeContextType.SCALAR).scalar().getDefinedBoolean()) {
            return null;
        }
        RuntimeScalar canInline = SpecialBlockParser.runSpecialBlock(
                parser, "BEGIN",
                typeConstraintNoArgumentMethodCall(typeReference, "can_be_inlined", index),
                RuntimeContextType.SCALAR).scalar();
        if (!canInline.getDefinedBoolean()) return null;

        RuntimeScalar inlineMethod = SpecialBlockParser.runSpecialBlock(
                parser, "BEGIN",
                typeConstraintMethodCall(
                        typeReference, "can", new StringNode("inline_check", index), index),
                RuntimeContextType.SCALAR).scalar();
        String methodName = inlineMethod.getDefinedBoolean() ? "inline_check" : "_inline_check";
        Node sourceCall = typeConstraintMethodCall(
                typeReference, methodName, new StringNode(parameterName, index), index);
        String source = SpecialBlockParser.runSpecialBlock(
                parser, "BEGIN", sourceCall, RuntimeContextType.SCALAR).scalar().toString();

        List<LexerToken> inlineTokens = new Lexer(source).tokenize();
        ErrorMessageUtil previousErrorUtil = parser.ctx.errorUtil;
        ErrorMessageUtil.SourceLocation declarationLocation =
                previousErrorUtil.getSourceLocationAccurate(index);
        String inlineFile = "(inline_check:" + declarationLocation.fileName()
                + ":" + declarationLocation.lineNumber() + ")";
        parser.ctx.errorUtil = new ErrorMessageUtil(inlineFile, inlineTokens);
        try {
            Parser inlineParser = new Parser(parser.ctx, inlineTokens);
            Node expression = inlineParser.parseExpression(0);
            if (expression == null || inlineParser.tokens.get(inlineParser.tokenIndex).type
                    != LexerTokenType.EOF) {
                inlineParser.throwError("syntax error in inlined type constraint");
            }
            return expression;
        } catch (PerlCompilerException exception) {
            throw exception;
        } finally {
            parser.ctx.errorUtil = previousErrorUtil;
        }
    }

    private static Node reifyQuotedTypeName(Node typeConstraint, int index) {
        if (!(typeConstraint instanceof StringNode string)
                || !string.value.matches("[A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)*")) {
            return typeConstraint;
        }
        Node typeFunction = new OperatorNode("&", new IdentifierNode(string.value, index), index);
        return new BinaryOperatorNode("(", typeFunction, new ListNode(List.of(), index), index);
    }

    private Node parseUnparenthesizedTypeConstraint() {
        int start = parser.tokenIndex;
        int parameterSigil = findTypeConstraintParameterSigil(start);
        if (parameterSigil < 0) {
            int last = parser.tokens.size() - 1;
            while (last >= start && (parser.tokens.get(last).type == LexerTokenType.EOF
                    || parser.tokens.get(last).type == LexerTokenType.WHITESPACE
                    || parser.tokens.get(last).type == LexerTokenType.NEWLINE)) {
                last--;
            }
            if (last >= start && "[".equals(parser.tokens.get(last).text)) {
                parser.throwError(start, "missing type name");
            }
            return parser.parseExpression(0);
        }
        if (parser.functionParametersTypeReifierCallback != null && parameterSigil > start) {
            String source = typeSource(start, parameterSigil);
            parser.tokenIndex = parameterSigil;
            lastCustomReifiedTypeValue = callCustomTypeReifier(source, start);
            return customTypeConstraintReference(lastCustomReifiedTypeValue, start);
        }
        if ("moose".equals(parser.functionParametersTypeReifier)
                && parameterSigil > start) {
            StringBuilder typeSource = new StringBuilder();
            for (int index = start; index < parameterSigil; index++) {
                LexerToken token = parser.tokens.get(index);
                if (token.type != LexerTokenType.WHITESPACE
                        && token.type != LexerTokenType.NEWLINE) {
                    typeSource.append(token.text);
                }
            }
            Node reifier = new OperatorNode("&", new IdentifierNode(
                    "Moose::Util::TypeConstraints::find_or_create_isa_type_constraint", start), start);
            parser.tokenIndex = parameterSigil;
            Node reifiedType = new BinaryOperatorNode("(", reifier,
                    new ListNode(List.of(new StringNode(typeSource.toString(), start)), start), start);
            return requireMooseTypeConstraints(reifiedType, start);
        }

        int first = nextSignificantToken(start);
        int afterFirst = nextSignificantToken(first + 1);
        if (first == start && afterFirst == parameterSigil
                && parser.tokens.get(start).type == LexerTokenType.IDENTIFIER) {
            LexerToken typeName = consumeToken();
            Node typeSub = new OperatorNode("&",
                    new IdentifierNode(typeName.text, start), start);
            return new BinaryOperatorNode("(", typeSub, new ListNode(List.of(), start), start);
        }

        // Bound expression parsing at the parameter sigil. Without this
        // sentinel, Perl's `Type $value` indirect-call syntax consumes the
        // parameter as an argument to Type.
        LexerToken boundary = new LexerToken(LexerTokenType.OPERATOR, ")");
        parser.tokens.add(parameterSigil, boundary);
        try {
            Node expression = parser.parseExpression(0);
            if (!peekToken().text.equals(")")) {
                parser.throwError("Malformed Function::Parameters type constraint");
            }
            consumeToken();
            return expression;
        } finally {
            int boundaryIndex = parser.tokenIndex - 1;
            if (boundaryIndex >= 0 && boundaryIndex < parser.tokens.size()
                    && parser.tokens.get(boundaryIndex) == boundary) {
                parser.tokens.remove(boundaryIndex);
                parser.tokenIndex--;
            } else {
                parser.tokens.remove(boundary);
            }
        }
    }

    private String typeSource(int start, int end) {
        StringBuilder source = new StringBuilder();
        for (int index = start; index < end; index++) {
            LexerToken token = parser.tokens.get(index);
            if (token.type != LexerTokenType.WHITESPACE
                    && token.type != LexerTokenType.NEWLINE) {
                source.append(token.text);
            }
        }
        return source.toString();
    }

    private RuntimeScalar callCustomTypeReifier(String source, int index) {
        long id = CUSTOM_TYPE_REIFIER_SEQUENCE.incrementAndGet();
        String callbackName = "Function::Parameters::Internal::__type_reifier_" + id;
        GlobalVariable.globalCodeRefs.put(callbackName, parser.functionParametersTypeReifierCallback);
        Node callback = new OperatorNode("&", new IdentifierNode(callbackName, index), index);
        Node call = new BinaryOperatorNode("(", callback,
                new ListNode(List.of(new StringNode(source, index)), index), index);
        try {
            RuntimeScalar value = SpecialBlockParser.runSpecialBlock(
                    parser, "BEGIN", call, RuntimeContextType.SCALAR).scalar();
            if (RuntimeScalarType.blessedId(value) == 0) {
                parser.throwError(index, "reify_type callback must return a type constraint object");
            }
            return value;
        } catch (PerlCompilerException exception) {
            String message = exception.getMessage();
            int beginFailure = message == null
                    ? -1 : message.indexOf("\nBEGIN failed--compilation aborted at ");
            if (beginFailure >= 0) {
                throw new PerlCompilerException(message.substring(0, beginFailure + 1));
            }
            throw exception;
        } finally {
            GlobalVariable.globalCodeRefs.remove(callbackName);
        }
    }

    private Node customTypeConstraintReference(RuntimeScalar typeObject, int index) {
        long id = CUSTOM_TYPE_REIFIER_SEQUENCE.incrementAndGet();
        String variableName = "Function::Parameters::Internal::__type_constraint_" + id;
        GlobalVariable.globalVariables.put(variableName, typeObject);
        OperatorNode reference = new OperatorNode("$", new IdentifierNode(variableName, index), index);
        reference.setAnnotation("functionParametersTypeObjectVariable", variableName);
        return reference;
    }

    private int findTypeConstraintParameterSigil(int start) {
        int squareDepth = 0;
        int roundDepth = 0;
        int braceDepth = 0;
        for (int index = start; index < parser.tokens.size(); index++) {
            LexerToken token = parser.tokens.get(index);
            if (token.type == LexerTokenType.WHITESPACE) continue;
            if (squareDepth == 0 && roundDepth == 0 && braceDepth == 0
                    && ("$".equals(token.text) || "@".equals(token.text) || "%".equals(token.text))) {
                return index;
            }
            switch (token.text) {
                case "[" -> squareDepth++;
                case "]" -> squareDepth--;
                case "(" -> roundDepth++;
                case ")" -> {
                    if (roundDepth == 0) return -1;
                    roundDepth--;
                }
                case "{" -> braceDepth++;
                case "}" -> braceDepth--;
                default -> { }
            }
        }
        return -1;
    }

    private int nextSignificantToken(int index) {
        while (index < parser.tokens.size()
                && parser.tokens.get(index).type == LexerTokenType.WHITESPACE) {
            index++;
        }
        return index;
    }

    private int previousSignificantToken(int index) {
        for (int previous = index - 1; previous >= 0; previous--) {
            if (parser.tokens.get(previous).type != LexerTokenType.WHITESPACE) {
                return previous;
            }
        }
        return index;
    }

    private boolean validateSigil(String sigil, int paramStartIndex, boolean isNamed,
                                  boolean immediatelyFollowedByHash) {
        if (parsingFunctionParametersInvocant && ("@".equals(sigil) || "%".equals(sigil))) {
            LexerToken name = peekToken();
            String invocant = sigil
                    + (name.type == LexerTokenType.IDENTIFIER ? name.text : "");
            parser.throwError(paramStartIndex, "invocant " + invocant
                    + ("@".equals(sigil) ? " can't be an array" : " can't be a hash"));
        }
        // The lexer may combine a sigil and an immediately following '#'
        // (for example, "$#") into one token.  Diagnose all three sigils at
        // the preceding open-paren context, which is the source position Perl
        // reports.  Keep parsing so the following line receives its ordinary
        // recovered syntax diagnostic too.
        if (sigil.length() == 2 && sigil.charAt(1) == '#'
                && (sigil.charAt(0) == '$' || sigil.charAt(0) == '@' || sigil.charAt(0) == '%')) {
            deferImmediateHashAfterSigilDiagnostic(paramStartIndex, sigil.charAt(0));
            return true;
        }

        if (!sigil.equals("$") && !sigil.equals("@") && !sigil.equals("%")) {
            recoverInvalidParameterStart(paramStartIndex, isNamed, sigil);
            return true;
        }

        // Check for double sigil or invalid character after sigil
        // Check the raw following token before peekToken() has a chance to
        // interpret '#' as a comment and skip past its terminating newline.
        // The recovery below needs that newline to emit Perl's second syntax
        // error for the first token on the next line.
        if (immediatelyFollowedByHash) {
            deferImmediateHashAfterSigilDiagnostic(paramStartIndex, sigil.charAt(0));
            return true;
        }
        LexerToken next = peekToken();
        if (next.text.equals("$") || next.text.equals("@") || next.text.equals("%")) {
            throwDoubleSigilError(sigil, next.text);
        }
        if (next.type == LexerTokenType.NUMBER) {
            var location = parser.ctx.errorUtil.getSourceLocationAccurate(signatureOpenParenIndex);
            throw new PerlCompilerException("Illegal operator following parameter in a subroutine signature at "
                    + location.fileName() + " line " + location.lineNumber() + ", near \"("
                    + sigil + next.text + "\"\n");
        }
        if (next.text.equals("#")) {
            deferImmediateHashAfterSigilDiagnostic(paramStartIndex, sigil.charAt(0));
            return true;
        }
        return false;
    }

    private void throwDoubleSigilError(String sigil, String followingSigil) {
        var location = parser.ctx.errorUtil.getSourceLocationAccurate(signatureOpenParenIndex);
        String closing = parser.tokenIndex + 1 < parser.tokens.size()
                ? parser.tokens.get(parser.tokenIndex + 1).text : "";
        String trailingWhitespace = parser.tokenIndex + 2 < parser.tokens.size()
                && parser.tokens.get(parser.tokenIndex + 2).type == LexerTokenType.WHITESPACE
                ? parser.tokens.get(parser.tokenIndex + 2).text : "";
        throw new PerlCompilerException("Illegal character following sigil in a subroutine signature at "
                + location.fileName() + " line " + location.lineNumber() + ", near \"("
                + sigil + "\"\nsyntax error at " + location.fileName() + " line "
                + location.lineNumber() + ", near \"" + sigil + followingSigil + closing
                + trailingWhitespace + "\"\n");
    }

    private void recoverInvalidParameterStart(int paramStartIndex, boolean isNamed, String token) {
        int contextStart = previousSignificantToken(paramStartIndex);
        var location = parser.ctx.errorUtil.getSourceLocationAccurate(contextStart);
        String message = isNamed
                ? "A named signature parameter must start with '$'"
                : "A signature parameter must start with '$', '@' or '%'";
        throw new PerlCompilerException(message + " at " + location.fileName() + " line "
                + location.lineNumber() + ", near \"" + signatureExcerpt(contextStart, true) + "\"\n"
                + "syntax error at " + location.fileName() + " line "
                + location.lineNumber() + ", near \"" + signatureExcerpt(contextStart, false) + "\"\n");
    }

    private String signatureExcerpt(int start, boolean onlyFirstCharacterOfLastToken) {
        StringBuilder excerpt = new StringBuilder();
        int end = parser.tokenIndex - 1;
        for (int i = start; i <= end; i++) {
            LexerToken token = parser.tokens.get(i);
            if (i == end && onlyFirstCharacterOfLastToken) {
                excerpt.append(token.text.charAt(0));
            } else {
                excerpt.append(token.text);
            }
        }
        return excerpt.toString();
    }

    private void throwMalformedSeparator() {
        var location = parser.ctx.errorUtil.getSourceLocationAccurate(signatureOpenParenIndex);
        String excerpt = malformedSeparatorExcerpt();
        String diagnostic = "Illegal operator following parameter in a subroutine signature at "
                + location.fileName() + " line " + location.lineNumber() + ", near \""
                + excerpt + "\"\n"
                + "syntax error at " + location.fileName() + " line " + location.lineNumber()
                + ", near \"" + excerpt + "\"\n";
        throw new PerlCompilerException(diagnostic);
    }

    private String malformedSeparatorExcerpt() {
        int end = parser.tokenIndex;
        String unexpected = parser.tokens.get(end).text;
        // A compound assignment after a signature parameter is rejected as
        // one operator, but Perl's diagnostic includes the first token of the
        // attempted default expression too: `($a += 1`.
        if (unexpected.endsWith("=")) {
            int expressionStart = end + 1;
            while (expressionStart < parser.tokens.size()
                    && parser.tokens.get(expressionStart).type == LexerTokenType.WHITESPACE) {
                expressionStart++;
            }
            if (expressionStart < parser.tokens.size()
                    && parser.tokens.get(expressionStart).type != LexerTokenType.EOF) {
                end = expressionStart;
            }
        }
        if (";".equals(unexpected) && end + 1 < parser.tokens.size()
                && parser.tokens.get(end + 1).type == LexerTokenType.WHITESPACE) {
            end++;
        }
        if ("{".equals(unexpected)) {
            while (end + 1 < parser.tokens.size() && !"}".equals(parser.tokens.get(end).text)) {
                end++;
            }
        }
        StringBuilder excerpt = new StringBuilder();
        for (int i = signatureOpenParenIndex; i <= end; i++) {
            LexerToken token = parser.tokens.get(i);
            excerpt.append(token.text);
            if (":".equals(unexpected) && i == end) {
                break;
            }
        }
        return excerpt.toString();
    }

    private void throwNamedSlurpyParameterError(String sigil) {
        var location = parser.ctx.errorUtil.getSourceLocationAccurate(signatureOpenParenIndex);
        String name = parser.tokenIndex < parser.tokens.size() ? parser.tokens.get(parser.tokenIndex).text : "";
        throw new PerlCompilerException("A named signature parameter must start with '$' at "
                + location.fileName() + " line " + location.lineNumber() + ", near \"(:" + sigil + "\"\n"
                + "syntax error at " + location.fileName() + " line " + location.lineNumber()
                + ", near \":" + sigil + name + "\"\n");
    }

    private void deferImmediateHashAfterSigilDiagnostic(int paramStartIndex, char sigil) {
        var location = parser.ctx.errorUtil.getSourceLocationAccurate(
                previousSignificantToken(paramStartIndex));
        parser.deferDiagnostic("'#' not allowed immediately following a sigil in a subroutine signature at "
                + location.fileName() + " line " + location.lineNumber()
                + ", near \"(" + sigil + "\"\n");
    }

    /**
     * Perl recovers from {@code $#foo} in a signature until the closing
     * parenthesis, producing a second syntax error at the next line.  Do the
     * same without installing an invalid lexical such as {@code $#foo}.
     */
    private void recoverImmediateHashAfterSigil(int paramStartIndex) {
        int syntaxIndex = -1;
        boolean afterNewline = false;
        for (int i = parser.tokenIndex; i < parser.tokens.size(); i++) {
            LexerToken token = parser.tokens.get(i);
            if (token.type == LexerTokenType.NEWLINE) {
                afterNewline = true;
                continue;
            }
            if (afterNewline && token.type != LexerTokenType.WHITESPACE) {
                syntaxIndex = i;
                break;
            }
        }
        if (syntaxIndex >= 0) {
            var location = parser.ctx.errorUtil.getSourceLocationAccurate(syntaxIndex);
            parser.deferDiagnostic("syntax error at " + location.fileName() + " line "
                    + location.lineNumber() + ", near \""
                    + parser.tokens.get(syntaxIndex).text + "\"\n");
        }
        while (!peekToken().text.equals(")") && peekToken().type != LexerTokenType.EOF) {
            consumeToken();
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
            int defaultOperatorIndex = parser.tokenIndex;
            consumeToken();
            // Perl points this error at the default expression, not its '='.
            LexerToken defaultExpression = peekToken();
            int diagnosticIndex = defaultExpression.text.equals(")")
                    ? defaultOperatorIndex
                    : parser.tokenIndex;
            parser.throwError(diagnosticIndex, "A slurpy parameter may not have a default value");
        }

    }

    private boolean hasDefaultValueBeforeCloseParen() {
        int nested = 0;
        for (int index = parser.tokenIndex; index < parser.tokens.size(); index++) {
            LexerToken token = parser.tokens.get(index);
            if (token.type == LexerTokenType.EOF) break;
            if (token.text.equals(")") && nested == 0) break;
            if (token.text.equals("(")) {
                nested++;
            } else if (token.text.equals(")") && nested > 0) {
                nested--;
            } else if (token.text.equals("=") || token.text.equals("//=") || token.text.equals("||=")) {
                return true;
            }
        }
        return false;
    }

    /** Perl points slurpy-order errors at the last significant signature token. */
    private int lastSignatureTokenBeforeCloseParen() {
        int index = parser.tokenIndex;
        int last = index;
        int nested = 0;
        while (index < parser.tokens.size()) {
            LexerToken token = parser.tokens.get(index);
            if (token.type == LexerTokenType.EOF) break;
            if (token.text.equals(")") && nested == 0) break;
            if (token.text.equals("(")) {
                nested++;
            } else if (token.text.equals(")") && nested > 0) {
                nested--;
            }
            if (token.type != LexerTokenType.WHITESPACE && !token.text.equals("=")) {
                last = index;
            }
            index++;
        }
        return last;
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
                // Perl reports every mandatory parameter after the first
                // optional one, so retain this diagnostic and keep parsing
                // the remainder of the signature.
                parser.deferErrorAtToken(paramStartIndex, "Mandatory parameter follows optional parameter");
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
        } else if (!(parser.parsingFunctionParametersDeclaration
                && !parser.functionParametersStrict)) {
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
            Node missingArgument = new BinaryOperatorNode(
                    "<", new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                    new NumberNode(Integer.toString(paramIndex + 1), parser.tokenIndex),
                    parser.tokenIndex);
            Node condition = missingArgument;
            if ("//=".equals(op) || "||=".equals(op)) {
                ArrayLiteralNode argumentIndex = new ArrayLiteralNode(
                        List.of(new NumberNode(Integer.toString(paramIndex), parser.tokenIndex)),
                        parser.tokenIndex);
                Node argument = new BinaryOperatorNode("[", atUnderscore(parser), argumentIndex,
                        parser.tokenIndex);
                Node fallbackCondition = "//=".equals(op)
                        ? new OperatorNode("!", new OperatorNode("defined", argument, parser.tokenIndex),
                                parser.tokenIndex)
                        : new OperatorNode("!", argument, parser.tokenIndex);
                condition = new BinaryOperatorNode("||", missingArgument, fallbackCondition,
                        parser.tokenIndex);
            }
            return new BinaryOperatorNode("&&", condition, defaultValue, parser.tokenIndex);
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
        if (parser.parsingFunctionParametersDeclaration && !parser.functionParametersDefaultArguments) {
            parser.throwError(previousDefaultOperatorIndex(), parser.functionParametersKeyword
                    + ": default argument not allowed");
        }
        // Check if there's actually a default expression
        LexerToken next = peekToken();
        if (next.type == LexerTokenType.EOF || next.text.equals(",") || next.text.equals(")")) {
            boolean isUndef = paramVariable instanceof OperatorNode && ((OperatorNode) paramVariable).operator.equals("undef");
            if (paramVariable != null && !isUndef) {
                parser.throwError(previousDefaultOperatorIndex(), "Optional parameter lacks default expression");
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
            SymbolTable.SymbolEntry outerVariable = parser.ctx.symbolTable.getSymbolEntry("$" + id.name);
            if (parser.parsingFunctionParametersDeclaration
                    && outerVariable != null
                    && Set.of("my", "state").contains(outerVariable.decl())
                    && outerVariable.ast() != null) {
                String alias = "__fp_outer_" + outerVariable.index() + "_" + paramVariable.getIndex();
                parser.ctx.symbolTable.addVariableWithIndex(
                        "$" + alias, outerVariable.index(), outerVariable.decl(),
                        outerVariable.perlPackage(), outerVariable.ast());
                outerVariable.ast().setAnnotation("functionParametersOuterBinding", "$" + id.name);
                renameSelfReference(value, id.name, alias);
            } else {
                qualifySelfReference(value, id.name);
            }
        }
        return value;
    }

    private int previousDefaultOperatorIndex() {
        for (int index = parser.tokenIndex - 1; index >= 0; index--) {
            LexerToken token = parser.tokens.get(index);
            if (token.type == LexerTokenType.WHITESPACE) continue;
            if (token.text.equals("=") || token.text.equals("//=") || token.text.equals("||=")) {
                return index;
            }
            break;
        }
        return parser.tokenIndex;
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

    private void renameSelfReference(Node node, String name, String alias) {
        if (node instanceof OperatorNode op) {
            if ("$".equals(op.operator) && op.operand instanceof IdentifierNode id
                    && name.equals(id.name)) {
                id.name = alias;
            } else if (op.operand != null) {
                renameSelfReference(op.operand, name, alias);
            }
        } else if (node instanceof BinaryOperatorNode binary) {
            renameSelfReference(binary.left, name, alias);
            renameSelfReference(binary.right, name, alias);
        } else if (node instanceof ListNode list) {
            for (Node element : list.elements) renameSelfReference(element, name, alias);
        } else if (node instanceof HashLiteralNode hash) {
            for (Node element : hash.elements) renameSelfReference(element, name, alias);
        } else if (node instanceof ArrayLiteralNode array) {
            for (Node element : array.elements) renameSelfReference(element, name, alias);
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
                OperatorNode exists = new OperatorNode(
                        "exists", new ListNode(List.of(hashAccess), parser.tokenIndex), parser.tokenIndex);
                extractionValue = new TernaryOperatorNode("?", exists, deleteExpr, defaultValue,
                        parser.tokenIndex);
            } else {
                if (defaultOp.equals("||=")) {
                    defaultOp = "||";
                } else if (defaultOp.equals("//=")) {
                    defaultOp = "//";
                }

                extractionValue = new BinaryOperatorNode(
                        defaultOp,
                        deleteExpr,
                        defaultValue,
                        parser.tokenIndex);
            }
        } else {
            extractionValue = deleteExpr;
        }

        // Add the extraction assignment with 'my' declaration
        // my $named = (delete $h{named}) // default
        OperatorNode myParam = new OperatorNode("my", paramVariable, parser.tokenIndex);
        // Signature parameters are value bindings, not ordinary declarations
        // that may inherit a Devel::LexAlias/forward-refalias replacement.
        myParam.setAnnotation("signatureParameterDeclaration", true);
        statements.add(new BinaryOperatorNode("=", myParam, extractionValue, parser.tokenIndex));

        // Return a list node containing the hash declaration (if first time) and the extraction
        return new ListNode(statements, parser.tokenIndex);
    }

    private Node namedArgsSource() {
        Node namedArguments = atUnderscore(parser);
        Node positionalCountAvailable = new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex);
        Node namedArgumentCount = positionalCountAvailable;
        if (positionalParameterCount > 0) {
            Node start = new NumberNode(Integer.toString(positionalParameterCount), parser.tokenIndex);
            Node end = new OperatorNode("$#", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
            Node range = new BinaryOperatorNode("..", start, end, parser.tokenIndex);
            Node slice = new BinaryOperatorNode("[", atUnderscore(parser),
                    new ArrayLiteralNode(List.of(range), parser.tokenIndex), parser.tokenIndex);
            Node hasNamedTail = new BinaryOperatorNode("<=",
                    new NumberNode(Integer.toString(positionalParameterCount), parser.tokenIndex),
                    positionalCountAvailable, parser.tokenIndex);
            namedArguments = new TernaryOperatorNode("?", hasNamedTail, slice,
                    new ListNode(List.of(), parser.tokenIndex), parser.tokenIndex);
            namedArgumentCount = new TernaryOperatorNode("?", hasNamedTail,
                    new BinaryOperatorNode("-",
                            new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex),
                            new NumberNode(Integer.toString(positionalParameterCount), parser.tokenIndex),
                            parser.tokenIndex),
                    new NumberNode("0", parser.tokenIndex), parser.tokenIndex);
        }
        Node odd = new BinaryOperatorNode("%",
                namedArgumentCount,
                new NumberNode("2", parser.tokenIndex), parser.tokenIndex);
        Node pad = new TernaryOperatorNode("?", odd,
                new ListNode(List.of(new OperatorNode("undef", null, parser.tokenIndex)), parser.tokenIndex),
                new ListNode(List.of(), parser.tokenIndex), parser.tokenIndex);
        return new ListNode(List.of(namedArguments, pad), parser.tokenIndex);
    }

    private ListNode generateSignatureAST() {
        List<Node> allNodes = new ArrayList<>();

        // Add argument count validation
        if (!(parser.parsingFunctionParametersDeclaration && !parser.functionParametersStrict)) {
            allNodes.add(generateArgCountValidation());
        }

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

        // Function::Parameters methods bind their implicit/explicit
        // invocant lexically, then expose only user arguments through @_ in
        // the body. This lets method code forward @_ while adding $self.
        if (!invocantNames.isEmpty()) {
            for (int i = 0; i < invocantNames.size(); i++) {
                allNodes.add(new OperatorNode("shift",
                        new OperatorNode("@", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex),
                        parser.tokenIndex));
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
        signature.setAnnotation("signatureInvocants", new ArrayList<>(invocantNames));
        if (parser.functionParametersKeyword != null) {
            signature.setAnnotation("signatureKeyword", parser.functionParametersKeyword);
        }
        signature.setAnnotation("signaturePositionalRequired", new ArrayList<>(positionalRequiredNames));
        signature.setAnnotation("signaturePositionalOptional", new ArrayList<>(positionalOptionalNames));
        signature.setAnnotation("signatureNamedRequired", new ArrayList<>(namedRequiredMetadataNames));
        signature.setAnnotation("signatureNamedOptional", new ArrayList<>(namedOptionalNames));
        signature.setAnnotation("signatureParameterTypes", new LinkedHashMap<>(parameterTypeNames));
        signature.setAnnotation("signatureParameterTypeObjects", new LinkedHashMap<>(parameterTypeObjects));
        signature.setAnnotation("signatureParameterTypeObjectVariables",
                new LinkedHashMap<>(parameterTypeObjectVariables));
        if (slurpySigil != null) {
            signature.setAnnotation("signatureSlurpySigil", slurpySigil);
            signature.setAnnotation("signatureSlurpyName", slurpyParameterName);
        }
        signature.setAnnotation("signatureSubName",
                NameNormalizer.normalizeVariableName(
                        subroutineName == null ? "__ANON__" : subroutineName,
                        parser.ctx.symbolTable.getCurrentPackage()));
        signature.setAnnotation("signatureDefaultValueNodes", new ArrayList<>(defaultValueNodes));
        if (parser.functionParametersTypeReifier != null) {
            signature.setAnnotation("signatureTypeReifier", parser.functionParametersTypeReifier);
        }
        return signature;
    }

    private static String metadataTypeName(Node typeConstraint) {
        if (typeConstraint.getAnnotation("signatureMetadataTypeName") instanceof String name) {
            return name;
        }
        if (typeConstraint instanceof TernaryOperatorNode ternary
                && "?".equals(ternary.operator)) {
            return metadataTypeName(ternary.trueExpr);
        }
        if (typeConstraint instanceof BinaryOperatorNode sequence && ",".equals(sequence.operator)) {
            typeConstraint = sequence.right;
        }
        if (typeConstraint instanceof BinaryOperatorNode call && "(".equals(call.operator)
                && call.left instanceof OperatorNode reference && "&".equals(reference.operator)
                && reference.operand instanceof IdentifierNode name) {
            if ("Moose::Util::TypeConstraints::find_or_create_isa_type_constraint".equals(name.name)
                    && call.right instanceof ListNode arguments && arguments.elements.size() == 1
                    && arguments.elements.get(0) instanceof StringNode typeSource) {
                return "moose:" + typeSource.value;
            }
            return name.name;
        }
        return null;
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
            if (parser.parsingFunctionParametersDeclaration) {
                return generateFunctionParametersArgumentCountMessage(true);
            }
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
            if (parser.parsingFunctionParametersDeclaration) {
                return generateFunctionParametersArgumentCountMessage(false);
            }
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

    private Node generateFunctionParametersArgumentCountMessage(boolean tooFew) {
        String keyword = parser.functionParametersKeyword == null
                ? "method" : parser.functionParametersKeyword;
        String name = subroutineName == null ? "(anon)" : subroutineName;
        int expected = tooFew ? minParams : maxParams;
        String prefix = "Too " + (tooFew ? "few" : "many") + " arguments for "
                + keyword + " " + name + " (expected " + expected + ", got ";
        Node argCount = new OperatorNode("scalar", atUnderscore(parser), parser.tokenIndex);
        return new BinaryOperatorNode(".",
                new BinaryOperatorNode(".", new StringNode(prefix, parser.tokenIndex),
                        argCount, parser.tokenIndex),
                new StringNode(")", parser.tokenIndex), parser.tokenIndex);
    }

    private Node generateParameterAssignment() {
        // my ($a, $b, @rest) = @_
        OperatorNode declaration = new OperatorNode("my",
                new ListNode(parameterVariables, parser.tokenIndex),
                parser.tokenIndex);
        declaration.setAnnotation("signatureParameterDeclaration", true);
        return new BinaryOperatorNode(
                "=",
                declaration,
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

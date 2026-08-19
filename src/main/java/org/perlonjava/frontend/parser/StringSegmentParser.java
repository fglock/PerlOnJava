package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.operators.PerlUtfString;
import org.perlonjava.runtime.HintHashRegistry;
import org.perlonjava.runtime.NamedCharacterExpansion;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.PerlParserException;
import org.perlonjava.runtime.runtimetypes.ScalarUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.ParseBlock.parseBlock;
import static org.perlonjava.frontend.parser.Variable.parseArrayHashAccess;
import static org.perlonjava.runtime.perlmodule.Strict.HINT_UTF8;

/**
 * Base class for parsing strings with segments and variable interpolation.
 *
 * <p>This abstract class provides the foundation for parsing Perl-style strings that may contain
 * variable interpolation (like $var, @array) and escape sequences. It handles the segmentation
 * of strings into literal text parts and interpolated expressions, which are then combined
 * into a single AST node representing the complete string.</p>
 *
 * <p>The parser works by tokenizing the string content and identifying special sequences:
 * <ul>
 *   <li>Variable interpolation: $scalar, @array, ${expression}</li>
 *   <li>Escape sequences: \n, \t, \x{hex}, \N{unicode_name}, etc.</li>
 *   <li>Control characters: \cA, \cZ, etc.</li>
 * </ul></p>
 *
 * <p>Subclasses can override specific methods to customize behavior for different string types
 * (e.g., quoted strings vs regex patterns, case modification, quotemeta application).</p>
 *
 * @see StringParser
 */
public abstract class StringSegmentParser {

    /**
     * Static counter for generating globally unique capture group names for regex code blocks
     * Must be static to ensure names don't collide across different patterns that share
     * the same pendingCodeBlockConstants map
     */
    /**
     * The emitter context for logging and error handling
     */
    protected final EmitterContext ctx;
    /**
     * The list of tokens representing the string content
     */
    protected final List<LexerToken> tokens;
    /**
     * The parser instance for parsing embedded expressions
     */
    protected final Parser parser;
    /**
     * The token index in the original source for error reporting
     */
    protected final int tokenIndex;
    /**
     * Flag indicating if this is parsing a regex pattern (affects bracket handling)
     */
    protected final boolean isRegex;
    protected final boolean isRegexReplacement;
    protected final boolean isRegexQuoteConstruction;
    /**
     * Buffer for accumulating literal text segments
     */
    protected final StringBuilder currentSegment;
    private boolean currentSegmentHasSourceNonAscii = false;
    private boolean inRegexCharClass = false;
    private boolean regexCharClassFirst = false;
    private boolean regexCharClassEscape = false;
    private boolean inRegexComment = false;
    private boolean regexCommentEscape = false;
    private boolean inRegexLineComment = false;
    private boolean regexExtended = false;
    /**
     * List of AST nodes representing string segments (literals and interpolated expressions)
     */
    protected final List<Node> segments;
    protected boolean hasExecutableRegexCallbacks;
    protected boolean hasRuntimeInterpolation;
    protected final boolean interpolateVariable;
    protected final boolean parseEscapes;
    /**
     * Original token offset for mapping string positions back to source
     */
    private int originalTokenOffset = 0;

    /**
     * Original string content for better error context
     */
    private String originalStringContent = "";

    /**
     * Constructs a new StringSegmentParser with the specified parameters.
     *
     * @param ctx        the emitter context for logging and error handling
     * @param tokens     the list of tokens representing the string content
     * @param parser     the parser instance for parsing embedded expressions
     * @param tokenIndex the token index in the original source for error reporting
     * @param isRegex    flag indicating if this is parsing a regex pattern
     */
    public StringSegmentParser(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex, boolean parseEscapes, boolean interpolateVariable, boolean isRegexReplacement) {
        this(ctx, tokens, parser, tokenIndex, isRegex, parseEscapes, interpolateVariable, isRegexReplacement, false);
    }

    public StringSegmentParser(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex, boolean parseEscapes, boolean interpolateVariable, boolean isRegexReplacement, boolean isRegexQuoteConstruction) {
        this.ctx = ctx;
        this.tokens = tokens;
        this.parser = parser;
        this.tokenIndex = tokenIndex;
        this.isRegex = isRegex;
        this.parseEscapes = parseEscapes;
        this.currentSegment = new StringBuilder();
        this.segments = new ArrayList<>();
        this.interpolateVariable = interpolateVariable;
        this.isRegexReplacement = isRegexReplacement;
        this.isRegexQuoteConstruction = isRegexQuoteConstruction;
    }

    /**
     * Appends text to the current literal segment buffer.
     *
     * <p>Subclasses can override this method to apply transformations such as:
     * <ul>
     *   <li>Case modification (uppercase, lowercase, title case)</li>
     *   <li>Quote metacharacters for regex</li>
     *   <li>Other string transformations</li>
     * </ul></p>
     *
     * @param text the text to append to the current segment
     */
    protected void appendToCurrentSegment(String text) {
        currentSegment.append(text);
    }

    protected void appendLiteralToCurrentSegment(String text) {
        appendToCurrentSegment(text);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            updateRegexCharClassState(c);
            if (c > 127) {
                currentSegmentHasSourceNonAscii = true;
            }
        }
    }

    protected boolean isInsideRegexCharClass() {
        return isRegex && inRegexCharClass;
    }

    /**
     * Whether regex code-block openers have their ordinary special meaning at the current point.
     * Subclasses may suppress them while parsing a quoting region such as {@code \Q...\E}.
     */
    protected boolean regexCodeBlocksAreActive() {
        return !inRegexCharClass && !inRegexComment && !inRegexLineComment;
    }

    void setRegexExtended(boolean regexExtended) {
        this.regexExtended = regexExtended;
    }

    private void updateRegexCharClassState(char c) {
        if (!isRegex) {
            return;
        }
        if (inRegexLineComment) {
            if (c == '\n') {
                inRegexLineComment = false;
            }
            return;
        }
        if (inRegexComment) {
            if (regexCommentEscape) {
                regexCommentEscape = false;
            } else if (c == '\\') {
                regexCommentEscape = true;
            } else if (c == ')') {
                inRegexComment = false;
            }
            return;
        }
        if (inRegexCharClass) {
            if (regexCharClassEscape) {
                regexCharClassEscape = false;
            } else if (c == '\\') {
                regexCharClassEscape = true;
            } else if (c == ']' && !regexCharClassFirst) {
                inRegexCharClass = false;
            } else if (regexCharClassFirst && c != '^') {
                regexCharClassFirst = false;
            }
        } else if (c == '[') {
            inRegexCharClass = true;
            regexCharClassFirst = true;
        } else if (c == '#' && regexExtended) {
            inRegexLineComment = true;
        }
    }

    private boolean startsRegexComment() {
        int currentPos = parser.tokenIndex;
        return currentPos + 1 < parser.tokens.size()
                && "?".equals(parser.tokens.get(currentPos).text)
                && "#".equals(parser.tokens.get(currentPos + 1).text);
    }

    /**
     * Adds a string segment node to the segments list.
     *
     * <p>Subclasses can override this method to apply transformations to string nodes
     * before adding them to the segments list. This is useful for applying operations
     * like quotemeta or case modifications to literal string segments.</p>
     *
     * @param node the AST node representing a string segment
     */
    protected void addStringSegment(Node node) {
        segments.add(node);
    }

    /**
     * Flushes the current segment buffer to the segments list if it contains content.
     *
     * <p>This method is called whenever we encounter an interpolated expression or
     * reach the end of the string. It converts the accumulated literal text in
     * {@code currentSegment} into a StringNode and adds it to the segments list.</p>
     */
    protected void flushCurrentSegment() {
        if (!currentSegment.isEmpty()) {
            String value = currentSegment.toString();
            if (currentSegmentHasSourceNonAscii
                    && !ctx.symbolTable.isStrictOptionEnabled(HINT_UTF8)
                    && !ctx.compilerOptions.isUnicodeSource
                    && !ctx.compilerOptions.isByteStringSource) {
                byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                StringBuilder octets = new StringBuilder(utf8.length);
                for (byte b : utf8) {
                    octets.append((char) (b & 0xff));
                }
                value = octets.toString();
            }
            boolean forceByteString = shouldForceByteStringLiteral(value);
            addStringSegment(new StringNode(value, false, forceByteString, tokenIndex));
            currentSegment.setLength(0);
            currentSegmentHasSourceNonAscii = false;
        }
    }

    private boolean shouldForceByteStringLiteral(String value) {
        if (!ctx.symbolTable.isStrictOptionEnabled(HINT_UTF8)
                && !ctx.compilerOptions.isUnicodeSource) {
            return false;
        }
        if (currentSegmentHasSourceNonAscii) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses variable interpolation sequences like $var, @var, ${...}, @{...}.
     *
     * <p>This method handles several forms of variable interpolation:
     * <ul>
     *   <li>Simple variables: $var, @array</li>
     *   <li>Complex expressions: ${expr}, @{expr}</li>
     *   <li>Dereferenced variables: $var, $var</li>
     *   <li>Array/hash access: $var[0], $var{key}, $var->[0], $var->{key}</li>
     * </ul></p>
     *
     * <p>For array variables (@var), the result is automatically joined with the
     * current list separator ($").</p>
     *
     * @param sigil the variable sigil ("$" for scalars, "@" for arrays, "$#" for array length)
     * @throws PerlCompilerException if the interpolation syntax is invalid
     */
    protected void parseVariableInterpolation(String sigil) {
        flushCurrentSegment();
        hasRuntimeInterpolation = true;

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("str sigil");

        Node operand;
        var isArray = "@".equals(sigil);
        var isArrayPostderef = false;

        if (TokenUtils.peek(parser).text.equals("{")) {
            // Handle block-like interpolation: ${...} or @{...}

            // Check if this is an @{[...]} construct (array reference interpolation)
            if (isArray) {
                int savedIndex = parser.tokenIndex;
                TokenUtils.consume(parser); // Consume the '{'

                if (TokenUtils.peek(parser).text.equals("[")) {
                    // This is @{[...]} - create anonymous array reference and dereference
                    // Parse the entire {...} content as a block
                    // Restore to saved position (before '{') so parseBlock sees the '{'
                    // (can't just decrement by 1 because peek() may have skipped whitespace)
                    parser.tokenIndex = savedIndex;
                    TokenUtils.consume(parser); // Re-consume the '{'

                    try {
                        Node block = ParseBlock.parseBlock(parser); // Parse the block inside the curly brackets
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}"); // Consume the '}'

                        // Apply @ to dereference the block result
                        operand = new OperatorNode("@", block, tokenIndex);
                        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("str @{[...]} operand " + operand);
                    } catch (PerlCompilerException e) {
                        // Re-throw with offset-aware error reporting
                        createOffsetAwareError(tokenIndex, "Syntax error in @{[...]} block: " + e.getMessage());
                        return; // This line will never be reached, but satisfies compiler
                    }
                } else {
                    // Not @{[...]}, restore position and use parseBracedVariable
                    parser.tokenIndex = savedIndex;
                    try {
                        operand = Variable.parseBracedVariable(parser, sigil, true);
                    } catch (PerlCompilerException e) {
                        // Extract the core error message, removing any existing "Syntax error in braced variable:" prefix
                        String coreMessage = e.getMessage();
                        if (coreMessage.startsWith("Syntax error in braced variable: ")) {
                            coreMessage = coreMessage.substring("Syntax error in braced variable: ".length());
                        }
                        // Re-throw with offset-aware error reporting
                        createOffsetAwareError(tokenIndex, "Syntax error in braced variable: " + coreMessage);
                        return; // This line will never be reached, but satisfies compiler
                    }
                }
            } else {
                // Regular ${...} handling - let parseBracedVariable consume the '{'
                try {
                    operand = Variable.parseBracedVariable(parser, sigil, true);
                } catch (PerlCompilerException e) {
                    // Extract the core error message, removing any existing "Syntax error in braced variable:" prefix
                    String coreMessage = e.getMessage();
                    if (coreMessage.startsWith("Syntax error in braced variable: ")) {
                        coreMessage = coreMessage.substring("Syntax error in braced variable: ".length());
                    }
                    // Re-throw with offset-aware error reporting
                    createOffsetAwareError(tokenIndex, "Syntax error in braced variable: " + coreMessage);
                    return; // This line will never be reached, but satisfies compiler
                }
            }

            // After ${...}, parse subscript access like ${$ref}{key} or ${$ref}[0]
            // This matches Perl 5 where "${$hashref}{key}" = $hashref->{key}
            //
            // However, when ${var} uses explicit braces with a simple variable name,
            // [...] and {...} should NOT be parsed as subscripts.
            // Perl 5 rule: explicit braces terminate the variable name, so:
            //   In regex:  ${var}[0]   = scalar $var + char class [0]
            //   In string: "${var}[0]" = scalar $var + literal "[0]"
            //   vs:        $var[0]     = array element $var[0]
            // Only deref expressions like ${$ref}[0] should parse subscripts after braces.
            boolean isSimpleBracedVariable = !isArray
                    && operand instanceof OperatorNode opNode
                    && "$".equals(opNode.operator)
                    && opNode.operand instanceof IdentifierNode;
            if (!isSimpleBracedVariable) {
                try {
                    operand = parseArrayHashAccess(parser, operand, isRegex);
                } catch (Exception e) {
                    // If array/hash access parsing fails, use operand as-is
                }
            }

            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("str operand " + operand);
        } else {
            // Parse simple variables using shared logic, but keep the exact same flow
            operand = parseSimpleVariableInterpolation(sigil);

            // Postfix array dereferences interpolate like ordinary arrays and
            // therefore use $" between elements: "$ref->@*" and
            // "$ref->@[...]" / "$ref->@{...}".
            boolean dereferenceArrowFollows = "$".equals(sigil)
                    && parser.tokenIndex + 1 < parser.tokens.size()
                    && "->".equals(parser.tokens.get(parser.tokenIndex).text);
            boolean postfixDerefFollows = false;
            if (dereferenceArrowFollows) {
                postfixDerefFollows = switch (parser.tokens.get(parser.tokenIndex + 1).text) {
                    case "@*", "$*", "%*", "&*", "$#", "@", "%" -> true;
                    default -> false;
                };
            }
            boolean postfixDerefInterpolationEnabled = ctx.symbolTable != null
                    && ctx.symbolTable.isFeatureCategoryEnabled("postderef_qq");
            if (postfixDerefFollows && postfixDerefInterpolationEnabled) {
                String postderef = parser.tokens.get(parser.tokenIndex + 1).text;
                isArrayPostderef = postderef.equals("@") || postderef.equals("@*");
            }

            // Handle array/hash access: $var[0], $var{key}, $var->[0], etc.
            // Wrap in try-catch to handle malformed access gracefully
            try {
                // In regex replacement context, check if $var{N} or $var{N,M} should be treated as quantifier
                if ("$".equals(sigil) && isRegexReplacement && parser.tokens.get(parser.tokenIndex).text.equals("{") && shouldTreatAsQuantifier()) {
                    // Skip parsing as hash access - leave for regex engine to handle as quantifier
                } else if (!postfixDerefFollows || postfixDerefInterpolationEnabled) {
                    operand = parseArrayHashAccess(parser, operand, isRegex);
                }
            } catch (Exception e) {
                // If array/hash access parsing fails, throw a more descriptive error
                throw new PerlCompilerException(tokenIndex, "syntax error: Unterminated array or hash access", ctx.errorUtil);
            }
        }

        // For arrays, join elements with the list separator ($")
        if (isArray || isArrayPostderef) {
            operand = new BinaryOperatorNode("join_interpolation",
                    new OperatorNode("$", new IdentifierNode("\"", tokenIndex), tokenIndex),
                    operand,
                    tokenIndex);
        }

        // Perl gives an interpolated missing hash element a more specific
        // warning than an ordinary undef string segment, including the hash
        // name and the runtime key. Preserve that source context on the hash
        // access node for both code generators. Regex interpolation has its
        // own warning path and must not use the quoted-string diagnostic.
        if (!isRegex
                && ctx.symbolTable.isWarningCategoryEnabled("uninitialized")
                && operand instanceof BinaryOperatorNode hashAccess
                && "{".equals(hashAccess.operator)
                && hashAccess.left instanceof OperatorNode hashSigil
                && "$".equals(hashSigil.operator)
                && hashSigil.operand instanceof IdentifierNode hashName
                && hashAccess.right instanceof HashLiteralNode keys
                && keys.elements.size() == 1) {
            hashAccess.setAnnotation("stringInterpolationHashName", "$" + hashName.name);
        }

        addStringSegment(operand);
    }

    /**
     * Determines if the current position should be treated as a regex quantifier rather than hash access.
     * This applies only in regex replacement context and when we see patterns like {3} or {2,5}.
     *
     * @return true if this should be treated as a regex quantifier
     */
    private boolean shouldTreatAsQuantifier() {
        // Save current position to look ahead
        int savedIndex = parser.tokenIndex;

        try {
            TokenUtils.consume(parser); // consume '{'

            String firstToken = TokenUtils.peek(parser).text;

            // Check for {,N} pattern
            if (",".equals(firstToken)) {
                TokenUtils.consume(parser);
                if (ScalarUtils.isInteger(TokenUtils.peek(parser).text)) {
                    TokenUtils.consume(parser);
                    return "}".equals(TokenUtils.peek(parser).text);
                }
                return false;
            }

            // Check for {N}, {N,}, {N,M} patterns
            if (ScalarUtils.isInteger(firstToken)) {
                TokenUtils.consume(parser);
                String nextToken = TokenUtils.peek(parser).text;

                if ("}".equals(nextToken)) {
                    return true; // {N}
                }

                if (",".equals(nextToken)) {
                    TokenUtils.consume(parser);
                    String afterComma = TokenUtils.peek(parser).text;

                    if ("}".equals(afterComma)) {
                        return true; // {N,}
                    }

                    if (ScalarUtils.isInteger(afterComma)) {
                        TokenUtils.consume(parser);
                        return "}".equals(TokenUtils.peek(parser).text); // {N,M}
                    }
                }
            }

            return false;

        } finally {
            // Always restore position - we're just looking ahead
            parser.tokenIndex = savedIndex;
        }
    }

    /**
     * Helper method to parse simple variable interpolation (non-braced forms).
     * Uses shared logic from Variable class while maintaining string interpolation context.
     */
    private Node parseSimpleVariableInterpolation(String sigil) {
        // Store the current position before parsing the identifier
        int startIndex = parser.tokenIndex;

        // Check for ${...} pattern which should be parsed as ${${...}}
        // This handles cases like $var, $ $var, etc.
        if ("$".equals(sigil) && TokenUtils.peek(parser).text.equals("$")) {
            // Save position to check what comes after the second $
            int savedIndex = parser.tokenIndex;
            TokenUtils.consume(parser); // Consume the second $

            // Check if what follows the second $ is immediately a braced expression
            if (parser.tokens.get(parser.tokenIndex).text.equals("{")) {
                // This is ${...} pattern - parse as ${${...}}
                // Restore position and consume the second $ properly
                parser.tokenIndex = savedIndex;
                TokenUtils.consume(parser); // Consume the second $

                // Now parse ${...} where the content is ${...}
                Node innerVariable = Variable.parseBracedVariable(parser, "$", true);
                return new OperatorNode("$", innerVariable, tokenIndex);
            } else {
                // Not ${...}, restore position and continue with normal parsing
                parser.tokenIndex = savedIndex;
            }
        }

        // Continue with existing logic for other cases...
        var identifier = IdentifierParser.parseComplexIdentifier(parser);

        if (identifier != null) {
            // Add validation that was missing - this fixes $01, $02 issues
            IdentifierParser.validateIdentifier(parser, identifier, startIndex);

            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("str Identifier: " + identifier);

            // Check if this is a field that needs transformation to $self->{field}
            // This mirrors the logic in Variable.parseVariable
            if (parser.isInMethod && Variable.isFieldInClassHierarchy(parser, identifier)) {
                String localVar = sigil + identifier;
                // Only transform if not shadowed by a local variable
                if (parser.ctx.symbolTable.getVariableIndexInCurrentScope(localVar) == -1) {
                    // Transform field access to $self->{field}
                    // Create $self
                    OperatorNode selfVar = new OperatorNode("$",
                            new IdentifierNode("self", tokenIndex), tokenIndex);

                    // Create hash subscript for field access
                    List<Node> keyList = new ArrayList<>();
                    keyList.add(new IdentifierNode(identifier, tokenIndex));
                    HashLiteralNode hashSubscript = new HashLiteralNode(keyList, tokenIndex);

                    // Create $self->{fieldname}
                    Node fieldAccess = new BinaryOperatorNode("->", selfVar, hashSubscript, tokenIndex);

                    // For array and hash fields, we need to dereference the reference
                    if (sigil.equals("@") || sigil.equals("%")) {
                        // @field becomes @{$self->{field}}
                        // %field becomes %{$self->{field}}
                        return new OperatorNode(sigil, fieldAccess, tokenIndex);
                    } else {
                        // Scalar fields: $field becomes $self->{field}
                        return fieldAccess;
                    }
                }
            }

            // Special case: empty identifier for $ sigil (like $ at end of string)
            if ("$".equals(sigil) && identifier.isEmpty()) {
                // Check if we're at end of string
                if (parser.tokenIndex >= parser.tokens.size() ||
                        parser.tokens.get(parser.tokenIndex).type == LexerTokenType.EOF) {
                    throw new PerlCompilerException(tokenIndex, "Final $ should be \\$ or $name", ctx.errorUtil);
                }
            }

            return new OperatorNode(sigil, new IdentifierNode(identifier, tokenIndex), tokenIndex);
        } else {
            // No identifier found after sigil
            // Check if we're at end of string for $ sigil
            if ("$".equals(sigil) && (parser.tokenIndex >= parser.tokens.size() ||
                    parser.tokens.get(parser.tokenIndex).type == LexerTokenType.EOF)) {
                throw new PerlCompilerException(tokenIndex, "Final $ should be \\$ or $name", ctx.errorUtil);
            }

            // For array sigils, check if next token starts with $ (e.g., @$b means array of $b)
            if ("@".equals(sigil) && parser.tokenIndex < parser.tokens.size()) {
                LexerToken nextToken = parser.tokens.get(parser.tokenIndex);
                if (nextToken.text.startsWith("$")) {
                    // This is @$var or @${expr} - array dereference of scalar
                    // Consume the $ token
                    TokenUtils.consume(parser);

                    // Check if next is { for @${expr} pattern (e.g., @${$v})
                    if (parser.tokenIndex < parser.tokens.size() &&
                            parser.tokens.get(parser.tokenIndex).text.equals("{")) {
                        // @${...} - parse as ${...} then wrap in @
                        Node scalarExpr = Variable.parseBracedVariable(parser, "$", true);
                        return new OperatorNode("@", scalarExpr, tokenIndex);
                    }

                    // Now parse the rest of the identifier
                    identifier = IdentifierParser.parseComplexIdentifier(parser);
                    if (identifier == null || identifier.isEmpty()) {
                        throw new PerlCompilerException(tokenIndex, "Missing identifier after $", ctx.errorUtil);
                    }
                    // Return the array of scalar variable
                    return new OperatorNode(sigil, new OperatorNode("$", new IdentifierNode(identifier, tokenIndex), tokenIndex), tokenIndex);
                }
            }
            // $#$var / $#${expr} — last index of the array referenced by the scalar.
            // Operand must be the scalar $var (not @-wrapped): JVM codegen adds @ for $# (EmitOperatorNode).
            if ("$#".equals(sigil) && parser.tokenIndex < parser.tokens.size()) {
                LexerToken nextTok = parser.tokens.get(parser.tokenIndex);
                if (nextTok.text.startsWith("$")) {
                    TokenUtils.consume(parser);
                    if (parser.tokenIndex < parser.tokens.size()
                            && parser.tokens.get(parser.tokenIndex).text.equals("{")) {
                        Node scalarExpr = Variable.parseBracedVariable(parser, "$", true);
                        return new OperatorNode("$#", scalarExpr, tokenIndex);
                    }
                    identifier = IdentifierParser.parseComplexIdentifier(parser);
                    if (identifier == null || identifier.isEmpty()) {
                        throw new PerlCompilerException(tokenIndex, "Missing identifier after $", ctx.errorUtil);
                    }
                    IdentifierParser.validateIdentifier(parser, identifier, startIndex);
                    Node scalarVar =
                            new OperatorNode("$", new IdentifierNode(identifier, tokenIndex), tokenIndex);
                    return new OperatorNode("$#", scalarVar, tokenIndex);
                }
            }
            if (!"$".equals(sigil)) {
                throw new PerlCompilerException(tokenIndex, "Missing identifier after " + sigil, ctx.errorUtil);
            }

            // For $ sigil with no identifier, check if we're at end of string
            if (parser.tokenIndex >= parser.tokens.size() ||
                    parser.tokens.get(parser.tokenIndex).type == LexerTokenType.EOF) {
                throw new PerlCompilerException(tokenIndex, "Final $ should be \\$ or $name", ctx.errorUtil);
            }
        }

        // Handle dereferenced variables: ${$var}, ${${$var}}, etc.
        int dollarCount = 0;
        while (TokenUtils.peek(parser).text.equals("$")) {
            dollarCount++;
            parser.tokenIndex++;
        }

        if (dollarCount > 0) {
            identifier = IdentifierParser.parseComplexIdentifier(parser);
            if (identifier == null) {
                throw new PerlCompilerException(tokenIndex, "Unexpected value after $ in string", ctx.errorUtil);
            }
            // Add validation for dereferenced variables too
            IdentifierParser.validateIdentifier(parser, identifier, startIndex);

            Node operand = new IdentifierNode(identifier, tokenIndex);
            // Apply dereference operators
            for (int i = 0; i < dollarCount; i++) {
                operand = new OperatorNode("$", operand, tokenIndex);
            }
            return new OperatorNode(sigil, operand, tokenIndex);
        } else {
            throw new PerlCompilerException(tokenIndex, "Unexpected value after " + sigil + " in string", ctx.errorUtil);
        }
    }

    /**
     * Builds the final AST node from all collected segments.
     *
     * <p>The result depends on the number of segments:
     * <ul>
     *   <li>0 segments: Returns an empty StringNode</li>
     *   <li>1 segment: Returns the segment directly if it's a StringNode,
     *       otherwise wraps it in a join operation</li>
     *   <li>Multiple segments: Returns a join operation that concatenates all segments</li>
     * </ul></p>
     *
     * <p>The join operation uses an empty string as the separator, effectively
     * concatenating all segments together.</p>
     *
     * @return the final AST node representing the complete string
     */
    protected Node buildResult() {
        flushCurrentSegment();

        if (needsStructuredRegexTemplate()) {
            return new OperatorNode("regexTemplate", new ListNode(segments, tokenIndex), tokenIndex);
        }

        return switch (segments.size()) {
            case 0 -> new StringNode("", tokenIndex);
            case 1 -> {
                var result = segments.getFirst();
                if (result instanceof StringNode) {
                    yield result;
                }
                // Single non-string segment needs to be converted to string
                yield new BinaryOperatorNode("join_interpolation",
                        new StringNode("", tokenIndex),
                        new ListNode(segments, tokenIndex),
                        tokenIndex);
            }
            default ->
                // Multiple segments: join them all together
                    new BinaryOperatorNode("join_interpolation",
                            new StringNode("", tokenIndex),
                            new ListNode(segments, tokenIndex),
                            tokenIndex);
        };
    }

    /** Preserve regex-valued interpolation so embedded callback tables are not stringified away. */
    protected boolean needsStructuredRegexTemplate() {
        return hasExecutableRegexCallbacks || (isRegex && hasRuntimeInterpolation);
    }

    /**
     * Abstract method for parsing escape sequences.
     *
     * <p>Subclasses must implement this method to handle escape sequences
     * appropriate for their string type. Common escape sequences include:
     * <ul>
     *   <li>Standard escapes: \n, \t, \r, \\, \"</li>
     *   <li>Octal escapes: \123</li>
     *   <li>Hex escapes: \x41, \x{41}</li>
     *   <li>Unicode escapes: \N{LATIN CAPITAL LETTER A}</li>
     *   <li>Control characters: \cA, \cZ</li>
     * </ul></p>
     */
    protected abstract void parseEscapeSequence();

    /**
     * Template method for parsing the complete string.
     *
     * <p>This method implements the main parsing loop, processing tokens one by one
     * and delegating to specialized methods for handling different token types.
     * The overall structure is maintained while allowing subclasses to customize
     * specific behaviors through method overrides.</p>
     *
     * @return the final AST node representing the parsed string
     */
    public Node parse() {
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser.parse: Starting with " + tokens.size() + " tokens, heredoc count: " + parser.getHeredocNodes().size());

        while (true) {
            if (parser.tokenIndex >= tokens.size()) {
                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser.parse: Reached end of tokens at index " + parser.tokenIndex);
                break;
            }
            var token = tokens.get(parser.tokenIndex++);

            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser.parse: Token at " + (parser.tokenIndex - 1) + ": type=" + token.type + ", text='" + token.text.replace("\n", "\\n") + "'");

            if (token.type == LexerTokenType.EOF) {
                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser.parse: Found EOF token");
                break;
            }

            // Check for NEWLINE tokens to process pending heredocs
            if (token.type == LexerTokenType.NEWLINE) {
                // Check if there are pending heredocs to process
                if (!parser.getHeredocNodes().isEmpty()) {
                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser: Found NEWLINE with " + parser.getHeredocNodes().size() + " pending heredocs at index " + (parser.tokenIndex - 1));

                    // Log which heredocs are pending
                    for (OperatorNode heredoc : parser.getHeredocNodes()) {
                        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("  Pending heredoc: " + heredoc.getAnnotation("identifier"));
                    }

                    // Flush current segment before processing heredocs
                    flushCurrentSegment();

                    // Adjust tokenIndex to point to the NEWLINE token for parseHeredocAfterNewline
                    parser.tokenIndex--;  // Back up to the NEWLINE token

                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser: Calling parseHeredocAfterNewline with tokenIndex=" + parser.tokenIndex);

                    // Process ALL heredocs after the newline
                    ParseHeredoc.parseHeredocAfterNewline(parser);

                    // Check if we've consumed all tokens
                    if (parser.tokenIndex >= tokens.size()) {
                        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser: Heredoc processing consumed all remaining tokens");
                        break;
                    }

                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser: After heredoc processing, tokenIndex = " + parser.tokenIndex + ", remaining tokens = " + (tokens.size() - parser.tokenIndex));

                    // parseHeredocAfterNewline updates parser.tokenIndex, so continue from there
                    continue;
                } else {
                    // No heredocs pending, append the newline normally
                    appendLiteralToCurrentSegment(token.text);
                }
                continue;
            }

            var text = token.text;
            if (handleSpecialToken(text)) {
                continue;
            }

            // Default: append literal text to current segment
            appendLiteralToCurrentSegment(text);
        }

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("StringSegmentParser.parse: Finished parsing, segments count: " + segments.size());
        return buildResult();
    }

    /**
     * Handles special tokens that require custom processing.
     *
     * <p>This method identifies and processes tokens that have special meaning
     * in string contexts:
     * <ul>
     *   <li>Backslash (\): Introduces escape sequences</li>
     *   <li>Dollar sign ($): Introduces scalar variable interpolation</li>
     *   <li>At sign (@): Introduces array variable interpolation</li>
     *   <li>Array length ($#): Introduces array length interpolation</li>
     * </ul></p>
     *
     * @param text the token text to process
     * @return true if the token was handled specially, false if it should be treated as literal text
     */
    protected boolean handleSpecialToken(String text) {
        if (isRegex && (inRegexComment || inRegexLineComment)) {
            return false;
        }
        return switch (text) {
            case "\\" -> {
                parseEscapeSequence();
                yield true;
            }
            case "$", "@", "$#" -> {
                if (shouldInterpolateVariable(text)) {
                    parseVariableInterpolation(text);
                    yield true;
                }
                yield false;
            }
            case "(" -> {
                if (isRegex && regexCodeBlocksAreActive() && startsRegexComment()) {
                    inRegexComment = true;
                    yield false;
                }
                // Check for (?{...}) and (??{...}) regex code blocks - only in regex context
                if (isRegex && regexCodeBlocksAreActive() && isRegexCallbackCondition()) {
                    parseRegexCallbackCondition();
                    yield true;
                } else if (isRegex && regexCodeBlocksAreActive() && isRegexCodeBlock()) {
                    parseRegexCodeBlock(false);  // (?{...}) - code execution
                    yield true;
                } else if (isRegex && regexCodeBlocksAreActive() && isRegexRecursiveBlock()) {
                    parseRegexCodeBlock(true);   // (??{...}) - recursive pattern
                    yield true;
                } else if (isRegex && regexCodeBlocksAreActive() && isRegexOptimisticBlock()) {
                    parseRegexOptimisticBlock(); // (*{...}) - optimization-preserving callback
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    /**
     * Checks if the current position is at the start of a (?{...}) regex code block.
     * This method looks ahead to see if we have the pattern (?{
     * Only called when isRegex=true to avoid false matches in regular strings.
     *
     * @return true if this is a regex code block, false otherwise
     */
    private boolean isRegexCodeBlock() {
        // Current token is "(", check if next tokens are "?" and "{"
        int currentPos = parser.tokenIndex;

        if (currentPos + 1 < parser.tokens.size() && currentPos + 2 < parser.tokens.size()) {
            LexerToken nextToken = parser.tokens.get(currentPos);
            LexerToken afterNextToken = parser.tokens.get(currentPos + 1);
            return "?".equals(nextToken.text) && "{".equals(afterNextToken.text);
        }
        return false;
    }

    private boolean isRegexCallbackCondition() {
        int currentPos = parser.tokenIndex;
        return currentPos + 3 < parser.tokens.size()
                && "?".equals(parser.tokens.get(currentPos).text)
                && "(".equals(parser.tokens.get(currentPos + 1).text)
                && ("?".equals(parser.tokens.get(currentPos + 2).text)
                    || "*".equals(parser.tokens.get(currentPos + 2).text))
                && "{".equals(parser.tokens.get(currentPos + 3).text);
    }

    private void parseRegexCallbackCondition() {
        flushCurrentSegment();
        int start = tokenIndex;
        int sourceLine = regexCallbackSourceLine();
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "?");
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "(");
        int callbackSourceStart = parser.tokenIndex;
        LexerToken callbackType = TokenUtils.consume(parser);
        if (!"?".equals(callbackType.text) && !"*".equals(callbackType.text)) {
            throw new IllegalStateException("invalid regex callback condition marker");
        }
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node block = parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        segments.add(new StringNode("(?(", start));
        segments.add(regexCallback(block, "CONDITION", start, sourceLine,
                "(" + regexSourceTokens(callbackSourceStart, parser.tokenIndex)));
    }

    private boolean isRegexOptimisticBlock() {
        int currentPos = parser.tokenIndex;
        return currentPos + 1 < parser.tokens.size()
                && "*".equals(parser.tokens.get(currentPos).text)
                && "{".equals(parser.tokens.get(currentPos + 1).text);
    }

    private void parseRegexOptimisticBlock() {
        flushCurrentSegment();
        int start = tokenIndex;
        int sourceLine = regexCallbackSourceLine();
        int callbackSourceStart = parser.tokenIndex;
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "*");
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        Node block = parseBlock(parser);
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        segments.add(regexCallback(block, "BLOCK", start, sourceLine,
                "(" + regexSourceTokens(callbackSourceStart, parser.tokenIndex)));
    }

    /**
     * Checks if the current tokens form a (??{...}) recursive regex pattern.
     * This is similar to (?{...}) but uses the result as a regex pattern.
     *
     * @return true if this is a recursive regex pattern, false otherwise
     */
    private boolean isRegexRecursiveBlock() {
        // Current token is "(", check if next tokens are "?", "?" and "{"
        int currentPos = parser.tokenIndex;

        if (currentPos + 2 < parser.tokens.size() && currentPos + 3 < parser.tokens.size()) {
            LexerToken token1 = parser.tokens.get(currentPos);
            LexerToken token2 = parser.tokens.get(currentPos + 1);
            LexerToken token3 = parser.tokens.get(currentPos + 2);
            return "?".equals(token1.text) && "?".equals(token2.text) && "{".equals(token3.text);
        }
        return false;
    }

    /**
     * Parses a (?{...}) regex code block by calling the Block parser and applying constant folding.
     *
     * <p>This method implements compile-time constant folding for regex code blocks to support
     * the special variable $^R (last regex code block result). When a code block contains a
     * simple constant expression, it is evaluated at compile time and the constant value is
     * encoded in a named capture group for retrieval at runtime.</p>
     *
     * <p><strong>IMPORTANT LIMITATION:</strong> This approach only works for literal regex patterns
     * in the source code (e.g., {@code /(?{ 42 })/}). It does NOT work for runtime-interpolated
     * patterns (e.g., {@code $var = '(?{ 42 })'; /$var/}) because those patterns are constructed
     * at runtime and never pass through the parser. This limitation affects approximately 1% of
     * real-world use cases, with pack.t and most Perl code using literal patterns.</p>
     *
     * <p>Future enhancement: To support interpolated patterns, this processing would need to be
     * moved to RegexPreprocessor.preProcessRegex() which sees the final pattern string regardless
     * of how it was constructed.</p>
     *
     * <p>Only called when isRegex=true.</p>
     */
    private void parseRegexCodeBlock(boolean isRecursive) {
        // Flush any accumulated text before adding the code block capture group
        // This ensures segments are added in the correct order (critical fix!)
        flushCurrentSegment();

        int savedTokenIndex = tokenIndex;
        int sourceLine = regexCallbackSourceLine();
        int callbackSourceStart = parser.tokenIndex;

        // Consume the "?" token(s)
        TokenUtils.consume(parser); // consume first "?"
        if (isRecursive) {
            TokenUtils.consume(parser); // consume second "?" for (??{...})
        }

        // Consume the "{" token
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");

        // Parse the block content using the Block parser - this handles heredocs properly
        Node block = parseBlock(parser);

        // Consume the closing "}"
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");

        // Consume the closing ")" that completes the (?{...}) construct  
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");

        String kind = isRecursive ? "DYNAMIC" : "BLOCK";
        segments.add(regexCallback(block, kind, savedTokenIndex, sourceLine,
                "(" + regexSourceTokens(callbackSourceStart, parser.tokenIndex)));
    }

    private String regexSourceTokens(int start, int end) {
        StringBuilder source = new StringBuilder();
        for (int i = start; i < end && i < parser.tokens.size(); i++) {
            source.append(parser.tokens.get(i).text);
        }
        return source.toString();
    }

    private Node regexCallback(Node block, String kind, int index, int sourceLine,
                               String source) {
        SubroutineNode closure = new SubroutineNode(null, null, null, block, false, index);
        closure.setAnnotation("inheritsSelfReference", true);
        closure.setAnnotation("regexCallbackPseudoBlock", true);
        closure.setAnnotation("quotedRegexCallback", isRegexQuoteConstruction);
        closure.setAnnotation("regexCallbackPackage",
                parser.ctx.symbolTable.getCurrentPackage());
        closure.setAnnotation("regexCallbackSourceLine", sourceLine);
        if (block instanceof AbstractNode abstractBlock) {
            // (?{ ... }) is a regex pseudo-block, not an ordinary anonymous-sub
            // scope. Its top-level local() frames belong to the matcher path and
            // must survive the Java callback return until Joni commits/unwinds it.
            abstractBlock.setAnnotation("regexCallbackBody", true);
        }
        OperatorNode callback = new OperatorNode("regexCallback", closure, index);
        callback.setAnnotation("regexCallbackKind", kind);
        callback.setAnnotation("regexCallbackPackage",
                closure.getAnnotation("regexCallbackPackage"));
        callback.setAnnotation("regexCallbackSource", source);
        hasExecutableRegexCallbacks = true;
        return callback;
    }

    private int regexCallbackSourceLine() {
        int line = parser.baseLineNumber;
        if (line <= 0) {
            line = ctx.errorUtil.getLineNumberAccurate(originalTokenOffset);
        }
        for (int i = 0; i < parser.tokenIndex && i < parser.tokens.size(); i++) {
            if (parser.tokens.get(i).type == LexerTokenType.NEWLINE) {
                line++;
            }
        }
        return line;
    }

    /**
     * Gets a string context around the specified position for error reporting.
     * This shows the actual string content around where the error occurred.
     */
    private String getStringContextAt(int position) {
        try {
            // Build context from the string tokens around the specified position
            StringBuilder context = new StringBuilder();
            int start = Math.max(0, position - 2);
            int end = Math.min(tokens.size(), position + 3);

            for (int i = start; i < end; i++) {
                if (i < tokens.size()) {
                    context.append(tokens.get(i).text);
                }
            }

            // Quote and escape the context for error message display
            String contextStr = context.toString();
            if (contextStr.length() > 50) {
                contextStr = contextStr.substring(0, 47) + "...";
            }
            return "\"" + contextStr.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        } catch (Exception e) {
            // Fallback to a generic message if context extraction fails
            return "\"string interpolation\"";
        }
    }

    /**
     * Sets the original token offset and string content for mapping string positions back to source.
     * This enables proper error reporting that shows the actual string content.
     */
    public void setOriginalTokenOffset(int offset) {
        this.originalTokenOffset = offset;
    }

    /**
     * Gets the original string content.
     */
    protected String getOriginalStringContent() {
        return originalStringContent;
    }

    /**
     * Sets the original string content for better error context.
     */
    public void setOriginalStringContent(String content) {
        this.originalStringContent = content;
    }

    /**
     * Creates and throws an offset-aware error with correct context.
     * Matches Perl's actual error format for string interpolation errors.
     * Based on Test::More analysis: string errors are single line, no stack traces, no "near" context.
     */
    private void createOffsetAwareError(int stringTokenIndex, String message) {
        // Extract core message without "Syntax error in braced variable:" prefix
        String coreMessage = message;
        if (coreMessage.startsWith("Syntax error in braced variable: ")) {
            coreMessage = coreMessage.substring("Syntax error in braced variable: ".length());
        }

        // Create error message matching Perl's exact format: "[ERROR] at [FILE] line [N]."
        String fileName = ctx.errorUtil.getFileName();
        int lineNumber = ctx.errorUtil.getLineNumber(originalTokenOffset);
        String perlStyleMessage = coreMessage + " at " + fileName + " line " + lineNumber + ".";

        // Create a custom exception that produces clean output like Perl
        RuntimeException cleanError = new RuntimeException(perlStyleMessage) {
            @Override
            public void printStackTrace() {
                // Print only the clean message, no stack trace
                System.err.println(getMessage());
            }

            @Override
            public void printStackTrace(java.io.PrintStream s) {
                // Print only the clean message, no stack trace
                s.println(getMessage());
            }

            @Override
            public void printStackTrace(java.io.PrintWriter s) {
                // Print only the clean message, no stack trace
                s.println(getMessage());
            }
        };

        throw cleanError;
    }

    /**
     * Gets error context from the original string content around the specified position.
     */
    private String getStringErrorContext(int stringTokenIndex) {
        try {
            if (originalStringContent.isEmpty()) {
                return "\"string interpolation\"";
            }

            // Try to estimate character position from token index
            // Look for variable interpolation patterns like ${...} to get better positioning
            int estimatedCharPos = findBestErrorPosition(stringTokenIndex);

            // Show a larger context window around the estimated position
            int contextWindow = 25; // Increased from 10 to show more context
            int start = Math.max(0, estimatedCharPos - contextWindow);
            int end = Math.min(originalStringContent.length(), estimatedCharPos + contextWindow);

            String context = originalStringContent.substring(start, end);

            // Escape special characters for display
            context = context.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");

            return "\"" + context + "\"";
        } catch (Exception e) {
            return "\"string interpolation\"";
        }
    }

    /**
     * Finds the best error position by looking for variable interpolation patterns.
     */
    private int findBestErrorPosition(int stringTokenIndex) {
        // Simple heuristic: look for ${...} patterns in the string
        int dollarIndex = originalStringContent.indexOf("${");
        if (dollarIndex >= 0) {
            // If we found a ${...} pattern, position the error around it
            return Math.max(0, dollarIndex - 5);
        }

        // Fallback to simple token-based estimation
        return Math.min(stringTokenIndex * 4, originalStringContent.length() - 1);
    }

    /**
     * Determines whether a variable sigil should trigger interpolation.
     *
     * <p>Variable interpolation is suppressed in certain contexts:
     * <ul>
     *   <li>When followed by whitespace or newlines</li>
     *   <li>When followed by certain punctuation that doesn't start valid variable names</li>
     *   <li>At the end of the string (EOF)</li>
     * </ul></p>
     *
     * <p>This prevents false interpolation of literal $ and @ characters that
     * aren't intended as variable references.</p>
     *
     * @param sigil the variable sigil ("$", "@", or "$#")
     * @return true if the sigil should trigger variable interpolation
     */
    private boolean shouldInterpolateVariable(String sigil) {
        if (!interpolateVariable) {
            return false;
        }

        var nextToken = tokens.get(parser.tokenIndex);
        if (nextToken.type == LexerTokenType.EOF) {
            // Special case: $ at EOF in double-quoted string should generate error
            // But only for StringDoubleQuoted, not for other contexts like regex
            return "$".equals(sigil) && interpolateVariable && !isRegex && !isRegexReplacement;
        }

        // Regex: don't interpolate "$" if followed by whitespace or newlines
        // "@" sigil: never interpolate if immediately followed by whitespace or newlines
        // "$#" sigil: don't interpolate if followed by whitespace or newlines
        if ((isRegex || "@".equals(sigil) || "$#".equals(sigil)) && (nextToken.type == LexerTokenType.WHITESPACE || nextToken.type == LexerTokenType.NEWLINE)) {
            return false;
        }

        // In regex patterns, $ followed by certain regex metacharacters should NOT be
        // interpolated as special variables. Instead, $ is the end-of-string anchor:
        //   $) = anchor + closing group (not $EGID)
        //   $( = anchor + opening group (not $GID)
        //   $| = anchor + alternation (not $OUTPUT_AUTOFLUSH)
        // In double-quoted strings, these SHOULD interpolate to their variable values.
        if (isRegex && "$".equals(sigil)
                && (")".equals(nextToken.text) || "(".equals(nextToken.text) || "|".equals(nextToken.text))) {
            return false;
        }

        // For @ sigil, only allow specific characters that can start array variable names
        // Valid: identifiers, digits, _, {, $, +, -
        // Invalid: ;, /, !, etc. (these are only valid after $ sigil)
        // This is critical to prevent incorrect interpolation of @; in strings like "@;\n"
        // Without this fix, "@;" would be incorrectly treated as an array variable
        // This also ensures $/ still interpolates correctly (scalar special var)
        if ("@".equals(sigil)) {
            return isValidArrayVariableStart(nextToken);
        }

        // Don't interpolate if followed by certain characters
        return !isNonInterpolatingCharacter(nextToken.text);
    }

    /**
     * Checks if a token can start a valid array variable name.
     * <p>
     * Array variables can be: @foo, @123, @_, @{expr}, @$ref, @+, @-
     * But NOT: @;, @/, @!, etc. (these are only valid for scalar $)
     * <p>
     * This method prevents incorrect string interpolation. For example:
     * - "@;\n" should NOT interpolate @; (not a valid array)
     * - "$/" SHOULD interpolate $/ (valid scalar special var)
     * <p>
     * Without this distinction, tests like op/chop.t, op/concat2.t, and
     * op/magic.t would fail due to incorrect string interpolation.
     *
     * @param token the token following the @ sigil
     * @return true if this can start a valid array variable
     */
    private boolean isValidArrayVariableStart(LexerToken token) {
        if (token.type == LexerTokenType.IDENTIFIER || token.type == LexerTokenType.NUMBER) {
            return true;
        }
        if (token.type == LexerTokenType.OPERATOR) {
            // Only specific operators can follow @ for valid array variables
            return switch (token.text) {
                case "{", "$", "+", "-", "_" -> true;
                default -> false;
            };
        }
        return false;
    }

    /**
     * Checks if a character should prevent variable interpolation.
     *
     * <p>Certain characters following a variable sigil indicate that the sigil
     * should be treated as literal text rather than starting variable interpolation.
     * This includes punctuation that commonly appears after literal $ or @ characters.</p>
     *
     * @param text the character following the variable sigil
     * @return true if this character should prevent interpolation
     */
    private boolean isNonInterpolatingCharacter(String text) {
        // Note: Special punctuation variables like $?, $|, $%, $", $\, $# etc.
        // are all valid Perl special variables and SHOULD be interpolated.
        // Previously this list incorrectly included these characters, preventing
        // interpolation of valid special variables like $? (child error status).
        // These characters are handled correctly by IdentifierParser.parseComplexIdentifier().
        return false;
    }

    /**
     * Handles control character escape sequences like \cA, \cZ.
     *
     * <p>Control characters are represented as \c followed by a letter.
     * The letter is converted to its corresponding control character:
     * <ul>
     *   <li>\cA becomes ASCII 1 (Ctrl-A)</li>
     *   <li>\cZ becomes ASCII 26 (Ctrl-Z)</li>
     *   <li>Both uppercase and lowercase letters are supported</li>
     * </ul></p>
     *
     * <p>If the character following \c is not a letter, it's used as-is.</p>
     */
    void handleControlCharacter() {
        var controlChar = TokenUtils.consumeChar(parser);
        if (controlChar.isEmpty()) {
            throw new PerlCompilerException(parser.tokenIndex, "Missing control char name in \\c", parser.ctx.errorUtil);
        }
        var c = controlChar.charAt(0);
        var result = (c >= 'A' && c <= 'Z') ? String.valueOf((char) (c - 'A' + 1))
                : (c >= 'a' && c <= 'z') ? String.valueOf((char) (c - 'a' + 1))
                : c == '@' ? String.valueOf((char) 0)
                : (c >= '[' && c <= '_') ? String.valueOf((char) (c - '[' + 27))
                : c == '?' ? String.valueOf((char) 127)
                : String.valueOf(c);
        appendToCurrentSegment(result);
    }

    /**
     * Handles hexadecimal escape sequences like \x41, \x{41}.
     *
     * <p>This method supports two forms of hex escapes:
     * <ul>
     *   <li>Fixed length: \x41 (exactly 2 hex digits)</li>
     *   <li>Variable length: \x{41} or \x{0041} (any number of hex digits in braces)</li>
     * </ul></p>
     *
     * <p>The hex value is converted to the corresponding Unicode character.
     * If the hex value is invalid or missing, the literal '\0' is used instead.</p>
     */
    void handleHexEscape() {
        var hexStr = new StringBuilder();
        var chr = TokenUtils.peekChar(parser);

        if ("{".equals(chr)) {
            // Variable length hex escape: \x{...}
            TokenUtils.consumeChar(parser);
            chr = TokenUtils.peekChar(parser);

            // Skip leading whitespace
            while (Character.isWhitespace(chr.charAt(0)) && !"}".equals(chr)) {
                TokenUtils.consumeChar(parser);
                chr = TokenUtils.peekChar(parser);
            }

            boolean lastWasUnderscore = false;
            while (!"}".equals(chr) && !chr.isEmpty()) {
                if (isHexDigit(chr)) {
                    hexStr.append(TokenUtils.consumeChar(parser));
                    lastWasUnderscore = false;
                    chr = TokenUtils.peekChar(parser);
                } else if ("_".equals(chr)) {
                    if (lastWasUnderscore) {
                        // Double underscore not allowed
                        break;
                    }
                    TokenUtils.consumeChar(parser); // Consume but don't add to hexStr
                    lastWasUnderscore = true;
                    chr = TokenUtils.peekChar(parser);
                } else if (Character.isWhitespace(chr.charAt(0))) {
                    // Spaces not allowed between digits - break parsing
                    break;
                } else {
                    break;
                }
            }

            // Skip trailing non-digits
            while (!"}".equals(chr)) {
                TokenUtils.consumeChar(parser);
                chr = TokenUtils.peekChar(parser);
            }

            TokenUtils.consumeChar(parser);
        } else {
            // Fixed length hex escape: \x..
            while (hexStr.length() < 2 && isHexDigit(chr)) {
                hexStr.append(TokenUtils.consumeChar(parser));
                chr = TokenUtils.peekChar(parser);
            }
        }

        if (!hexStr.isEmpty()) {
            try {
                String hs = hexStr.toString();
                BigInteger bi = new BigInteger(hs, 16);
                long hexUv =
                        bi.and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).longValue();
                if (Long.compareUnsigned(hexUv, 0x10FFFFL) > 0) {
                    appendToCurrentSegment(PerlUtfString.encodeBeyondUnicode(hexUv));
                } else if (hexUv >= 0xD800L && hexUv <= 0xDFFFL) {
                    appendToCurrentSegment(PerlUtfString.encodeSurrogate(hexUv));
                } else if (hexUv <= 0xFFFFL) {
                    appendToCurrentSegment(String.valueOf((char) hexUv));
                } else {
                    appendToCurrentSegment(new String(Character.toChars((int) hexUv)));
                }
            } catch (NumberFormatException e) {
                // Invalid hex sequence, treat as literal
                appendToCurrentSegment("\0");
            }
        } else {
            // No hex digits found, treat as literal
            appendToCurrentSegment("\0");
        }
    }

    /**
     * Handles octal escape sequences like \x{100}.
     *
     * <p>The octal value is converted to the corresponding Unicode character.
     * If the octal value is invalid or missing, the literal '\0' is used instead.</p>
     */
    void handleOctalEscape() {
        var octStr = new StringBuilder();
        var chr = TokenUtils.peekChar(parser);

        if ("{".equals(chr)) {
            // Variable length hex escape: \x{...}
            TokenUtils.consumeChar(parser);
            chr = TokenUtils.peekChar(parser);

            // Skip leading whitespace
            while (Character.isWhitespace(chr.charAt(0)) && !"}".equals(chr)) {
                TokenUtils.consumeChar(parser);
                chr = TokenUtils.peekChar(parser);
            }

            boolean lastWasUnderscore = false;
            while (!"}".equals(chr) && !chr.isEmpty()) {
                if (chr.compareTo("0") >= 0 && chr.compareTo("7") <= 0) {
                    octStr.append(TokenUtils.consumeChar(parser));
                    lastWasUnderscore = false;
                    chr = TokenUtils.peekChar(parser);
                } else if ("_".equals(chr)) {
                    if (lastWasUnderscore) {
                        // Double underscore not allowed
                        break;
                    }
                    TokenUtils.consumeChar(parser); // Consume but don't add to octStr
                    lastWasUnderscore = true;
                    chr = TokenUtils.peekChar(parser);
                } else if (Character.isWhitespace(chr.charAt(0))) {
                    // Spaces not allowed between digits - break parsing
                    break;
                } else {
                    break;
                }
            }

            // Skip trailing non-digits
            while (!"}".equals(chr)) {
                TokenUtils.consumeChar(parser);
                chr = TokenUtils.peekChar(parser);
            }

            TokenUtils.consumeChar(parser);
        } else {
            parser.throwError("Missing braces on \\o{}");
        }

        if (!octStr.isEmpty()) {
            try {
                var octValue = Integer.parseInt(octStr.toString(), 8);
                var result = octValue <= 0xFFFF
                        ? String.valueOf((char) octValue)
                        : new String(Character.toChars(octValue));
                appendToCurrentSegment(result);
            } catch (NumberFormatException e) {
                // Invalid hex sequence, treat as literal
                appendToCurrentSegment("\0");
            }
        } else {
            // No octal digits found, treat as literal
            appendToCurrentSegment("\0");
        }
    }

    /**
     * Checks if a character is a valid hexadecimal digit.
     *
     * @param chr the character to check
     * @return true if the character is 0-9, a-f, or A-F
     */
    private boolean isHexDigit(String chr) {
        return (chr.compareTo("0") >= 0 && chr.compareTo("9") <= 0) ||
                (chr.compareToIgnoreCase("a") >= 0 && chr.compareToIgnoreCase("f") <= 0);
    }

    /**
     * Handles Unicode name escape sequences like \N{LATIN CAPITAL LETTER A}.
     *
     * <p>This method processes Unicode character names enclosed in braces after \N.
     * The character name is looked up using ICU4J's UCharacter.getCharFromName()
     * method, which supports standard Unicode character names.</p>
     *
     * <p>If the name is not found or the syntax is invalid, the literal sequence
     * is preserved in the output.</p>
     *
     * <p>Examples:
     * <ul>
     *   <li>\N{LATIN CAPITAL LETTER A} becomes "A"</li>
     *   <li>\N{GREEK SMALL LETTER ALPHA} becomes "α"</li>
     *   <li>\N{INVALID NAME} remains as "\N{INVALID NAME}"</li>
     * </ul></p>
     */
    void handleUnicodeNameEscape() {
        if (!"{".equals(TokenUtils.peekChar(parser))) {
            // In a regex, plain \N is Perl's non-newline atom. Keep the escape
            // intact for the regex backend; quoted strings still treat it as N.
            appendToCurrentSegment(isRegex ? "\\N" : "N");
            return;
        }

        TokenUtils.consumeChar(parser); // consume '{'
        var nameBuilder = new StringBuilder();
        var chr = TokenUtils.peekChar(parser);

        // Collect the Unicode character name
        while (!"}".equals(chr) && !chr.isEmpty()) {
            nameBuilder.append(TokenUtils.consumeChar(parser));
            chr = TokenUtils.peekChar(parser);
        }

        if ("}".equals(chr)) {
            TokenUtils.consumeChar(parser); // consume '}'
            var name = nameBuilder.toString();
            if (isRegex && isPlainNonNewlineInterval(name)) {
                // A brace immediately following plain \N can be its quantifier,
                // not a named character. Leave both pieces for the regex lexer.
                appendToCurrentSegment("\\N{" + name + "}");
                return;
            }
            NamedCharacterExpansion.SourceMode sourceMode =
                    ctx.compilerOptions.isByteStringSource
                            || (!ctx.symbolTable.isStrictOptionEnabled(HINT_UTF8)
                                && !ctx.compilerOptions.isUnicodeSource)
                    ? NamedCharacterExpansion.SourceMode.BYTE
                    : NamedCharacterExpansion.SourceMode.UNICODE;
            if (isRegex) {
                // Keep named-character escapes intact until regex preprocessing.
                // Resolving \N{NUMBER SIGN} to '#' here is observably wrong under
                // /x: the resolved character is then mistaken for a comment and
                // any following capture groups disappear from the pattern.
                boolean customTranslator = NamedCharacterExpansion.usesCustomTranslator(
                        HintHashRegistry.getCompileTimeHint("charnames"));
                if (!customTranslator || currentSegment.toString().endsWith("(?[")) {
                    NamedCharacterExpansion expansion =
                            NamedCharacterExpansion.resolve(name, sourceMode);
                    if (isIncompleteExtendedClassNamedSequence(expansion)) {
                        throwNamedSequenceExtendedClassDiagnostic(expansion.sequence());
                    }
                    if (!expansion.resolved()) {
                        throwNamedCharacterDiagnostic(expansion.diagnostic());
                    }
                }
                appendToCurrentSegment("\\N{" + name + "}");
                return;
            }
            if (name.matches("(?i)U\\+[0-9A-F]+(?:\\.[0-9A-F]+)+")) {
                throwNamedCharacterDiagnostic(
                        "Invalid hexadecimal number in \\N{U+...}");
            }
            NamedCharacterExpansion expansion =
                    NamedCharacterExpansion.resolve(name, sourceMode);
            if (expansion.resolved()) {
                appendToCurrentSegment(expansion.sequence());
            } else {
                throwNamedCharacterDiagnostic(expansion.diagnostic());
            }
        } else {
            throwMissingNamedCharacterBraceDiagnostic();
        }
    }

    private boolean isPlainNonNewlineInterval(String contents) {
        return contents.matches("(?:[0-9]+(?:,[0-9]*)?|,[0-9]+)");
    }

    private void throwNamedCharacterDiagnostic(String diagnostic) {
        int errorIndex = this.tokenIndex;
        var location = ctx.errorUtil.getSourceLocationAccurate(errorIndex);
        throw new PerlParserException(diagnostic
                + " at " + location.fileName() + " line " + location.lineNumber()
                + ", within " + (isRegex ? "pattern" : "string") + "\n");
    }

    private void throwMissingNamedCharacterBraceDiagnostic() {
        var location = ctx.errorUtil.getSourceLocationAccurate(parser.tokenIndex);
        String message = isRegex
                ? "Missing right brace on \\N{} or unescaped left brace after \\N"
                : "Missing right brace on \\N{}";
        throw new PerlParserException(message
                + " at " + location.fileName() + " line " + location.lineNumber()
                + ", within " + (isRegex ? "pattern" : "string") + "\n");
    }

    private boolean isIncompleteExtendedClassNamedSequence(
            NamedCharacterExpansion expansion) {
        return expansion.sequence().codePointCount(0, expansion.sequence().length()) > 1
                && currentSegment.toString().endsWith("(?[");
    }

    private String namedSequenceExtendedClassDiagnostic(String sequence) {
        StringBuilder canonical = new StringBuilder();
        sequence.codePoints().forEach(codePoint -> {
            if (!canonical.isEmpty()) canonical.append('.');
            canonical.append(Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT));
        });
        return "\\N{} here is restricted to one character in regex; "
                + "marked by <-- HERE in m/" + currentSegment
                + "\\N{U+" + canonical + " <-- HERE }/";
    }

    private void throwNamedSequenceExtendedClassDiagnostic(String sequence) {
        var location = ctx.errorUtil.getSourceLocationAccurate(parser.tokenIndex);
        throw new PerlParserException(
                namedSequenceExtendedClassDiagnostic(sequence)
                        + " at " + location.fileName() + " line "
                        + location.lineNumber() + ".\n");
    }
}

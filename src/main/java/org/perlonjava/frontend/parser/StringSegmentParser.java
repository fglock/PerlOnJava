package org.perlonjava.frontend.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;
import org.perlonjava.runtime.runtimetypes.ScalarUtils;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.frontend.parser.ParseBlock.parseBlock;
import static org.perlonjava.frontend.parser.Variable.parseArrayHashAccess;

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
    private static int codeBlockCaptureCounter = 0;
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
    /**
     * Buffer for accumulating literal text segments
     */
    protected final StringBuilder currentSegment;
    /**
     * List of AST nodes representing string segments (literals and interpolated expressions)
     */
    protected final List<Node> segments;
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
            addStringSegment(new StringNode(currentSegment.toString(), tokenIndex));
            currentSegment.setLength(0);
        }
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

        ctx.logDebug("str sigil");

        Node operand;
        var isArray = "@".equals(sigil);

        if (TokenUtils.peek(parser).text.equals("{")) {
            // Handle block-like interpolation: ${...} or @{...}

            // Check if this is an @{[...]} construct (array reference interpolation)
            if (isArray) {
                int savedIndex = parser.tokenIndex;
                TokenUtils.consume(parser); // Consume the '{'

                if (TokenUtils.peek(parser).text.equals("[")) {
                    // This is @{[...]} - create anonymous array reference and dereference
                    // Parse the entire {...} content as a block
                    parser.tokenIndex--; // Back up to re-parse the '{'
                    TokenUtils.consume(parser); // Re-consume the '{'

                    try {
                        Node block = ParseBlock.parseBlock(parser); // Parse the block inside the curly brackets
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}"); // Consume the '}'

                        // Apply @ to dereference the block result
                        operand = new OperatorNode("@", block, tokenIndex);
                        ctx.logDebug("str @{[...]} operand " + operand);
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

            ctx.logDebug("str operand " + operand);
        } else {
            // Parse simple variables using shared logic, but keep the exact same flow
            operand = parseSimpleVariableInterpolation(sigil);

            // Handle array/hash access: $var[0], $var{key}, $var->[0], etc.
            // Wrap in try-catch to handle malformed access gracefully
            try {
                // In regex replacement context, check if $var{N} or $var{N,M} should be treated as quantifier
                if ("$".equals(sigil) && isRegexReplacement && parser.tokens.get(parser.tokenIndex).text.equals("{") && shouldTreatAsQuantifier()) {
                    // Skip parsing as hash access - leave for regex engine to handle as quantifier
                } else {
                    operand = parseArrayHashAccess(parser, operand, isRegex);
                }
            } catch (Exception e) {
                // If array/hash access parsing fails, throw a more descriptive error
                throw new PerlCompilerException(tokenIndex, "syntax error: Unterminated array or hash access", ctx.errorUtil);
            }
        }

        // For arrays, join elements with the list separator ($")
        if (isArray) {
            operand = new BinaryOperatorNode("join",
                    new OperatorNode("$", new IdentifierNode("\"", tokenIndex), tokenIndex),
                    operand,
                    tokenIndex);
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

            ctx.logDebug("str Identifier: " + identifier);

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
                    // This is @$var - array of scalar variable
                    // Consume the $ token
                    TokenUtils.consume(parser);
                    // Now parse the rest of the identifier
                    identifier = IdentifierParser.parseComplexIdentifier(parser);
                    if (identifier == null || identifier.isEmpty()) {
                        throw new PerlCompilerException(tokenIndex, "Missing identifier after $", ctx.errorUtil);
                    }
                    // Return the array of scalar variable
                    return new OperatorNode(sigil, new OperatorNode("$", new IdentifierNode(identifier, tokenIndex), tokenIndex), tokenIndex);
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

        return switch (segments.size()) {
            case 0 -> new StringNode("", tokenIndex);
            case 1 -> {
                var result = segments.getFirst();
                if (result instanceof StringNode) {
                    yield result;
                }
                // Single non-string segment needs to be converted to string
                yield new BinaryOperatorNode("join",
                        new StringNode("", tokenIndex),
                        new ListNode(segments, tokenIndex),
                        tokenIndex);
            }
            default ->
                // Multiple segments: join them all together
                    new BinaryOperatorNode("join",
                            new StringNode("", tokenIndex),
                            new ListNode(segments, tokenIndex),
                            tokenIndex);
        };
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
        ctx.logDebug("StringSegmentParser.parse: Starting with " + tokens.size() + " tokens, heredoc count: " + parser.getHeredocNodes().size());

        while (true) {
            if (parser.tokenIndex >= tokens.size()) {
                ctx.logDebug("StringSegmentParser.parse: Reached end of tokens at index " + parser.tokenIndex);
                break;
            }
            var token = tokens.get(parser.tokenIndex++);

            ctx.logDebug("StringSegmentParser.parse: Token at " + (parser.tokenIndex - 1) + ": type=" + token.type + ", text='" + token.text.replace("\n", "\\n") + "'");

            if (token.type == LexerTokenType.EOF) {
                ctx.logDebug("StringSegmentParser.parse: Found EOF token");
                break;
            }

            // Check for NEWLINE tokens to process pending heredocs
            if (token.type == LexerTokenType.NEWLINE) {
                // Check if there are pending heredocs to process
                if (!parser.getHeredocNodes().isEmpty()) {
                    ctx.logDebug("StringSegmentParser: Found NEWLINE with " + parser.getHeredocNodes().size() + " pending heredocs at index " + (parser.tokenIndex - 1));

                    // Log which heredocs are pending
                    for (OperatorNode heredoc : parser.getHeredocNodes()) {
                        ctx.logDebug("  Pending heredoc: " + heredoc.getAnnotation("identifier"));
                    }

                    // Flush current segment before processing heredocs
                    flushCurrentSegment();

                    // Adjust tokenIndex to point to the NEWLINE token for parseHeredocAfterNewline
                    parser.tokenIndex--;  // Back up to the NEWLINE token

                    ctx.logDebug("StringSegmentParser: Calling parseHeredocAfterNewline with tokenIndex=" + parser.tokenIndex);

                    // Process ALL heredocs after the newline
                    ParseHeredoc.parseHeredocAfterNewline(parser);

                    // Check if we've consumed all tokens
                    if (parser.tokenIndex >= tokens.size()) {
                        ctx.logDebug("StringSegmentParser: Heredoc processing consumed all remaining tokens");
                        break;
                    }

                    ctx.logDebug("StringSegmentParser: After heredoc processing, tokenIndex = " + parser.tokenIndex + ", remaining tokens = " + (tokens.size() - parser.tokenIndex));

                    // parseHeredocAfterNewline updates parser.tokenIndex, so continue from there
                    continue;
                } else {
                    // No heredocs pending, append the newline normally
                    appendToCurrentSegment(token.text);
                }
                continue;
            }

            var text = token.text;
            if (handleSpecialToken(text)) {
                continue;
            }

            // Default: append literal text to current segment
            appendToCurrentSegment(text);
        }

        ctx.logDebug("StringSegmentParser.parse: Finished parsing, segments count: " + segments.size());
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
                // Check for (?{...}) and (??{...}) regex code blocks - only in regex context
                if (isRegex && isRegexCodeBlock()) {
                    parseRegexCodeBlock(false);  // (?{...}) - code execution
                    yield true;
                } else if (isRegex && isRegexRecursiveBlock()) {
                    parseRegexCodeBlock(true);   // (??{...}) - recursive pattern
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

        // Try to apply constant folding to the block
        Node folded = ConstantFoldingVisitor.foldConstants(block);

        // If it's a BlockNode with a single element, extract it
        // This handles both empty blocks (BlockNode with empty ListNode) and single-expression blocks
        if (folded instanceof BlockNode blockNode) {
            if (blockNode.elements.size() == 1) {
                folded = blockNode.elements.get(0);
            }
        }

        // Check if the result is a simple constant using the visitor pattern
        RuntimeScalar constantValue =
                ConstantFoldingVisitor.getConstantValue(folded);

        if (constantValue != null) {
            if (isRecursive) {
                // For (??{...}), the constant becomes a pattern to match
                // Extract the string value and insert it directly as a pattern
                String patternString = constantValue.toString();
                // Insert the pattern string directly - it will be compiled as a regex
                segments.add(new StringNode(patternString, savedTokenIndex));
            } else {
                // For (?{...}), encode the value in a capture group for $^R
                String captureName;

                // Check if it's undef (needs special encoding)
                if (constantValue == RuntimeScalarCache.scalarUndef) {
                    captureName = String.format("cb%03du", codeBlockCaptureCounter++);
                } else {
                    // Use CaptureNameEncoder to encode the value in the capture name
                    captureName = org.perlonjava.regex.CaptureNameEncoder.encodeCodeBlockValue(
                            codeBlockCaptureCounter++, constantValue
                    );
                }

                if (captureName == null) {
                    // Encoding failed (e.g., name too long) - use fallback
                    segments.add(new StringNode("(?{UNIMPLEMENTED_CODE_BLOCK})", savedTokenIndex));
                } else {
                    // Encoding succeeded - create capture group
                    StringNode captureNode = new StringNode("(?<" + captureName + ">)", savedTokenIndex);
                    segments.add(captureNode);
                }
            }
        } else {
            // Not a constant - use unimplemented marker
            if (isRecursive) {
                segments.add(new StringNode("(??{UNIMPLEMENTED_RECURSIVE_PATTERN})", savedTokenIndex));
            } else {
                segments.add(new StringNode("(?{UNIMPLEMENTED_CODE_BLOCK})", savedTokenIndex));
            }
        }
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
     * Sets the original string content for better error context.
     */
    public void setOriginalStringContent(String content) {
        this.originalStringContent = content;
    }

    /**
     * Gets the original string content.
     */
    protected String getOriginalStringContent() {
        return originalStringContent;
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
            if ("$".equals(sigil) && interpolateVariable && !isRegex && !isRegexReplacement) {
                return true;
            }
            return false;
        }

        // Regex: don't interpolate "$" if followed by whitespace or newlines
        // "@" sigil: never interpolate if immediately followed by whitespace or newlines
        // "$#" sigil: don't interpolate if followed by whitespace or newlines
        if ((isRegex || "@".equals(sigil) || "$#".equals(sigil)) && (nextToken.type == LexerTokenType.WHITESPACE || nextToken.type == LexerTokenType.NEWLINE)) {
            return false;
        }

        // Don't interpolate if followed by certain characters
        return !isNonInterpolatingCharacter(nextToken.text);
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
        return switch (text) {
            case ")", "%", "|", "#", "\"", "\\",
                 "?", "(" -> true;
            default -> false;
        };
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
                var hexValue = Integer.parseInt(hexStr.toString(), 16);
                if (!Character.isValidCodePoint(hexValue)) {
                    // Invalid Unicode code point (outside 0x0 to 0x10FFFF range), treat as null
                    appendToCurrentSegment("\0");
                } else {
                    var result = hexValue <= 0xFFFF
                            ? String.valueOf((char) hexValue)
                            : new String(Character.toChars(hexValue));
                    appendToCurrentSegment(result);
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
            // Not a Unicode name escape, treat as literal
            appendToCurrentSegment("N");
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
            try {
                // Use centralized Unicode name resolution from UnicodeResolver
                // This handles U+XXXX format, official Unicode names, and Perl charnames aliases
                int codePoint;
                try {
                    codePoint = org.perlonjava.regex.UnicodeResolver.getCodePointFromName(name);
                    var result = new String(Character.toChars(codePoint));
                    appendToCurrentSegment(result);
                } catch (IllegalArgumentException e) {
                    // Name not found, preserve literal
                    appendToCurrentSegment("N{" + name + "}");
                }
            } catch (Exception e) {
                // Error looking up name, preserve literal
                appendToCurrentSegment("N{" + name + "}");
            }
        } else {
            // Unclosed brace, preserve literal
            appendToCurrentSegment("N{" + nameBuilder);
        }
    }
}
package org.perlonjava.parser;

import com.ibm.icu.lang.UCharacter;
import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.ScalarUtils;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.parser.Variable.parseArrayHashAccess;
import static org.perlonjava.parser.ParseBlock.parseBlock;

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
    public StringSegmentParser(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex, boolean interpolateVariable, boolean isRegexReplacement) {
        this.ctx = ctx;
        this.tokens = tokens;
        this.parser = parser;
        this.tokenIndex = tokenIndex;
        this.isRegex = isRegex;
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
            return new OperatorNode(sigil, new IdentifierNode(identifier, tokenIndex), tokenIndex);
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
                // Check for (?{...}) regex code blocks - only in regex context
                if (isRegex && isRegexCodeBlock()) {
                    parseRegexCodeBlock();
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
     * Parses a (?{...}) regex code block by calling the Block parser.
     * This ensures that Perl code inside regex constructs is properly parsed,
     * including heredocs and other complex constructs.
     * Only called when isRegex=true.
     */
    private void parseRegexCodeBlock() {
        int savedTokenIndex = tokenIndex;
        
        // Consume the "?" token
        TokenUtils.consume(parser); // consume "?"
        
        // Consume the "{" token
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "{");
        
        // Parse the block content using the Block parser - this handles heredocs properly
        Node block = parseBlock(parser);
        
        // Consume the closing "}"
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
        
        // Consume the closing ")" that completes the (?{...}) construct
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        
        // Instead of executing the block, preserve the (?{...}) structure for regex compilation
        // This allows the RegexPreprocessor to handle the unimplemented error properly
        segments.add(new StringNode("(?{UNIMPLEMENTED_CODE_BLOCK})", savedTokenIndex));
        
        ctx.logDebug("regex (?{...}) block parsed - preserved for regex compilation");
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
        if (!controlChar.isEmpty()) {
            var c = controlChar.charAt(0);
            var result = (c >= 'A' && c <= 'Z') ? String.valueOf((char) (c - 'A' + 1))
                    : (c >= 'a' && c <= 'z') ? String.valueOf((char) (c - 'a' + 1))
                    : c == '@' ? String.valueOf((char) 0)
                    : (c >= '[' && c <= '_') ? String.valueOf((char) (c - '[' + 27))
                    : c == '?' ? String.valueOf((char) 127)
                    : String.valueOf(c);
            appendToCurrentSegment(result);
        }
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
                String result;
                if (hexValue <= 0xFFFF) {
                    result = String.valueOf((char) hexValue);
                } else if (Character.isValidCodePoint(hexValue)) {
                    result = new String(Character.toChars(hexValue));
                } else {
                    // For invalid Unicode code points, create a representation using
                    // surrogate characters that won't crash Java but will fail later
                    // when used as identifiers (which is the expected Perl behavior)
                    result = String.valueOf((char) 0xDC00) + (char) (hexValue & 0xFFFF);
                }
                appendToCurrentSegment(result);
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
                var codePoint = UCharacter.getCharFromName(name);
                var result = codePoint != -1
                        ? new String(Character.toChars(codePoint))
                        : "N{" + name + "}"; // Preserve if name not found
                appendToCurrentSegment(result);
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
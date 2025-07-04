package org.perlonjava.parser;

import com.ibm.icu.lang.UCharacter;
import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;

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

    /** The emitter context for logging and error handling */
    protected final EmitterContext ctx;

    /** The list of tokens representing the string content */
    protected final List<LexerToken> tokens;

    /** The parser instance for parsing embedded expressions */
    protected final Parser parser;

    /** The token index in the original source for error reporting */
    protected final int tokenIndex;

    /** Flag indicating if this is parsing a regex pattern (affects bracket handling) */
    protected final boolean isRegex;

    /** Buffer for accumulating literal text segments */
    protected final StringBuilder currentSegment;

    /** List of AST nodes representing string segments (literals and interpolated expressions) */
    protected final List<Node> segments;

    /**
     * Constructs a new StringSegmentParser with the specified parameters.
     *
     * @param ctx the emitter context for logging and error handling
     * @param tokens the list of tokens representing the string content
     * @param parser the parser instance for parsing embedded expressions
     * @param tokenIndex the token index in the original source for error reporting
     * @param isRegex flag indicating if this is parsing a regex pattern
     */
    public StringSegmentParser(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex) {
        this.ctx = ctx;
        this.tokens = tokens;
        this.parser = parser;
        this.tokenIndex = tokenIndex;
        this.isRegex = isRegex;
        this.currentSegment = new StringBuilder();
        this.segments = new ArrayList<>();
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
     *   <li>Dereferenced variables: $$var, $@var</li>
     *   <li>Array/hash access: $var[0], $var{key}, $var->[0], $var->{key}</li>
     * </ul></p>
     *
     * <p>For array variables (@var), the result is automatically joined with the
     * current list separator ($").</p>
     *
     * @param sigil the variable sigil ("$" for scalars, "@" for arrays)
     * @throws PerlCompilerException if the interpolation syntax is invalid
     */
    protected void parseVariableInterpolation(String sigil) {
        flushCurrentSegment();

        ctx.logDebug("str sigil");
        Node operand;
        var isArray = "@".equals(sigil);

        if (TokenUtils.peek(parser).text.equals("{")) {
            // Handle block-like interpolation: ${...} or @{...}
            var rawStr2 = StringParser.parseRawStrings(parser, ctx, parser.tokens, parser.tokenIndex, 1);
            var blockStr = rawStr2.buffers.getFirst();
            ctx.logDebug("str block-like: " + blockStr);
            blockStr = sigil + "{" + blockStr + "}";

            // Parse the expression inside the braces
            var blockParser = new Parser(ctx, new Lexer(blockStr).tokenize());
            operand = ParseBlock.parseBlock(blockParser);
            parser.tokenIndex = rawStr2.next;
            ctx.logDebug("str operand " + operand);
        } else {
            // Handle simple variable interpolation
            var identifier = IdentifierParser.parseComplexIdentifier(parser);
            if (identifier == null) {
                // Handle dereferenced variables: $$var, $@var, etc.
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
                    operand = new IdentifierNode(identifier, tokenIndex);
                    // Apply dereference operators
                    for (int i = 0; i < dollarCount; i++) {
                        operand = new OperatorNode("$", operand, tokenIndex);
                    }
                } else {
                    throw new PerlCompilerException(tokenIndex, "Unexpected value after " + sigil + " in string", ctx.errorUtil);
                }
            } else {
                operand = new IdentifierNode(identifier, tokenIndex);
            }
            ctx.logDebug("str Identifier: " + identifier);
            operand = new OperatorNode(sigil, operand, tokenIndex);

            // Handle array/hash access: $var[0], $var{key}, $var->[0], etc.
            operand = parseArrayHashAccess(operand);
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
     * Parses array and hash access operations following a variable in interpolation.
     *
     * <p>This method handles chained access operations like:
     * <ul>
     *   <li>Array access: $var[0][1]</li>
     *   <li>Hash access: $var{key}{subkey}</li>
     *   <li>Method calls: $var->[0], $var->{key}</li>
     *   <li>Mixed access: $var[0]{key}->[1]</li>
     * </ul></p>
     *
     * <p>Special handling is provided for regex contexts where '[' might indicate
     * a character class rather than array access.</p>
     *
     * @param operand the base variable node to which access operations are applied
     * @return the modified operand with access operations applied
     */
    private Node parseArrayHashAccess(Node operand) {
        outerLoop:
        while (true) {
            var text = tokens.get(parser.tokenIndex).text;
            switch (text) {
                case "[" -> {
                    if (isRegex) {
                        // In regex context, '[' might be a character class
                        // Only treat as array access if followed by $ or number
                        var tokenNext = tokens.get(parser.tokenIndex + 1);
                        ctx.logDebug("str [ " + tokenNext);
                        if (!tokenNext.text.equals("$") && !(tokenNext.type == LexerTokenType.NUMBER)) {
                            break outerLoop;
                        }
                    }
                    operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                    ctx.logDebug("str operand " + operand);
                }
                case "{" -> {
                    // Hash access
                    operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                    ctx.logDebug("str operand " + operand);
                }
                case "->" -> {
                    // Method call or dereference
                    var previousIndex = parser.tokenIndex;
                    parser.tokenIndex++;
                    text = tokens.get(parser.tokenIndex).text;
                    switch (text) {
                        case "[", "{" -> {
                            // Dereference followed by access: $var->[0] or $var->{key}
                            parser.tokenIndex = previousIndex;  // Re-parse "->"
                            operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                            ctx.logDebug("str operand " + operand);
                        }
                        default -> {
                            // Not a dereference we can handle
                            parser.tokenIndex = previousIndex;
                            break outerLoop;
                        }
                    }
                }
                default -> {
                    // No more access operations
                    break outerLoop;
                }
            }
        }
        return operand;
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
                var result = segments.get(0);
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
        while (true) {
            var token = tokens.get(parser.tokenIndex++);
            if (token.type == LexerTokenType.EOF) {
                break;
            }

            var text = token.text;
            if (handleSpecialToken(text)) {
                continue;
            }

            // Default: append literal text to current segment
            appendToCurrentSegment(text);
        }

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
            case "$", "@" -> {
                if (shouldInterpolateVariable(text)) {
                    parseVariableInterpolation(text);
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
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
     * @param sigil the variable sigil ("$" or "@")
     * @return true if the sigil should trigger variable interpolation
     */
    private boolean shouldInterpolateVariable(String sigil) {
        var token1 = tokens.get(parser.tokenIndex);
        if (token1.type == LexerTokenType.EOF) {
            return false;
        }

        // Don't interpolate if followed by whitespace or certain characters
        return !(token1.type == LexerTokenType.WHITESPACE
                || token1.type == LexerTokenType.NEWLINE
                || isNonInterpolatingCharacter(token1.text));
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
            case ")", "%", "|", "]", "#", "\"", "\\" -> true;
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
     * If the hex value is invalid or missing, the literal 'x' is used instead.</p>
     */
    void handleHexEscape() {
        var hexStr = new StringBuilder();
        var chr = TokenUtils.peekChar(parser);

        if ("{".equals(chr)) {
            // Variable length hex escape: \x{...}
            TokenUtils.consumeChar(parser);
            chr = TokenUtils.peekChar(parser);
            while (!"}".equals(chr) && !chr.isEmpty()) {
                if (isHexDigit(chr)) {
                    hexStr.append(TokenUtils.consumeChar(parser));
                    chr = TokenUtils.peekChar(parser);
                } else {
                    break;
                }
            }
            if ("}".equals(chr)) {
                TokenUtils.consumeChar(parser);
            }
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
                var result = hexValue <= 0xFFFF
                        ? String.valueOf((char) hexValue)
                        : new String(Character.toChars(hexValue));
                appendToCurrentSegment(result);
            } catch (NumberFormatException e) {
                // Invalid hex sequence, treat as literal
                appendToCurrentSegment("x");
            }
        } else {
            // No hex digits found, treat as literal
            appendToCurrentSegment("x");
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

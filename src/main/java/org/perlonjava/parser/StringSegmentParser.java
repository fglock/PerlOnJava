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
 * Handles the basic segmentation logic without case modification or quotemeta.
 */
public abstract class StringSegmentParser {

    protected final EmitterContext ctx;
    protected final List<LexerToken> tokens;
    protected final Parser parser;
    protected final int tokenIndex;
    protected final boolean isRegex;

    protected final StringBuilder currentSegment;
    protected final List<Node> segments;

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
     * Appends text to the current segment.
     * Subclasses can override this to apply modifications.
     */
    protected void appendToCurrentSegment(String text) {
        currentSegment.append(text);
    }

    /**
     * Adds a string segment to the segments list.
     * Subclasses can override this to apply modifications.
     */
    protected void addStringSegment(Node node) {
        segments.add(node);
    }

    /**
     * Flushes the current segment buffer to the segments list if not empty.
     */
    protected void flushCurrentSegment() {
        if (!currentSegment.isEmpty()) {
            addStringSegment(new StringNode(currentSegment.toString(), tokenIndex));
            currentSegment.setLength(0);
        }
    }

    /**
     * Parses variable interpolation ($var, @var, ${...}, etc.)
     */
    protected void parseVariableInterpolation(String sigil) {
        flushCurrentSegment();

        ctx.logDebug("str sigil");
        Node operand;
        var isArray = "@".equals(sigil);

        if (TokenUtils.peek(parser).text.equals("{")) {
            // Block-like interpolation
            var rawStr2 = StringParser.parseRawStrings(parser, ctx, parser.tokens, parser.tokenIndex, 1);
            var blockStr = rawStr2.buffers.getFirst();
            ctx.logDebug("str block-like: " + blockStr);
            blockStr = sigil + "{" + blockStr + "}";
            var blockParser = new Parser(ctx, new Lexer(blockStr).tokenize());
            operand = ParseBlock.parseBlock(blockParser);
            parser.tokenIndex = rawStr2.next;
            ctx.logDebug("str operand " + operand);
        } else {
            var identifier = IdentifierParser.parseComplexIdentifier(parser);
            if (identifier == null) {
                // Parse $a  @$a
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

            // Handle array/hash access
            operand = parseArrayHashAccess(operand);
        }

        if (isArray) {
            operand = new BinaryOperatorNode("join",
                    new OperatorNode("$", new IdentifierNode("\"", tokenIndex), tokenIndex),
                    operand,
                    tokenIndex);
        }

        addStringSegment(operand);
    }

    /**
     * Parses array/hash access after variable interpolation
     */
    private Node parseArrayHashAccess(Node operand) {
        outerLoop:
        while (true) {
            var text = tokens.get(parser.tokenIndex).text;
            switch (text) {
                case "[" -> {
                    if (isRegex) {
                        // maybe character class
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
                    operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                    ctx.logDebug("str operand " + operand);
                }
                case "->" -> {
                    var previousIndex = parser.tokenIndex;
                    parser.tokenIndex++;
                    text = tokens.get(parser.tokenIndex).text;
                    switch (text) {
                        case "[", "{" -> {
                            parser.tokenIndex = previousIndex;  // Re-parse "->"
                            operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                            ctx.logDebug("str operand " + operand);
                        }
                        default -> {
                            parser.tokenIndex = previousIndex;
                            break outerLoop;
                        }
                    }
                }
                default -> {
                    break outerLoop;
                }
            }
        }
        return operand;
    }

    /**
     * Builds the final result from all segments.
     * Returns a single string node if only one segment, otherwise joins them.
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
                yield new BinaryOperatorNode("join",
                        new StringNode("", tokenIndex),
                        new ListNode(segments, tokenIndex),
                        tokenIndex);
            }
            default -> new BinaryOperatorNode("join",
                    new StringNode("", tokenIndex),
                    new ListNode(segments, tokenIndex),
                    tokenIndex);
        };
    }

    /**
     * Abstract method for parsing escape sequences.
     * Subclasses must implement this to handle their specific escape logic.
     */
    protected abstract void parseEscapeSequence();

    /**
     * Template method for parsing the string.
     * Subclasses can override specific parts while maintaining the overall structure.
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

            // Default: append to current segment
            appendToCurrentSegment(text);
        }

        return buildResult();
    }

    /**
     * Handles special tokens like escapes, variables, etc.
     * Returns true if the token was handled, false if it should be appended normally.
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
     * Determines if a variable should be interpolated based on the following token.
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

    private boolean isNonInterpolatingCharacter(String text) {
        return switch (text) {
            case ")", "%", "|", "]", "#", "\"", "\\" -> true;
            default -> false;
        };
    }

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

    void handleHexEscape() {
        var hexStr = new StringBuilder();
        var chr = TokenUtils.peekChar(parser);

        if ("{".equals(chr)) {
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
                appendToCurrentSegment("x");
            }
        } else {
            appendToCurrentSegment("x");
        }
    }

    private boolean isHexDigit(String chr) {
        return (chr.compareTo("0") >= 0 && chr.compareTo("9") <= 0) ||
                (chr.compareToIgnoreCase("a") >= 0 && chr.compareToIgnoreCase("f") <= 0);
    }

    void handleUnicodeNameEscape() {
        if (!"{".equals(TokenUtils.peekChar(parser))) {
            appendToCurrentSegment("N");
            return;
        }

        TokenUtils.consumeChar(parser);
        var nameBuilder = new StringBuilder();
        var chr = TokenUtils.peekChar(parser);

        while (!"}".equals(chr) && !chr.isEmpty()) {
            nameBuilder.append(TokenUtils.consumeChar(parser));
            chr = TokenUtils.peekChar(parser);
        }

        if ("}".equals(chr)) {
            TokenUtils.consumeChar(parser);
            var name = nameBuilder.toString();
            try {
                var codePoint = UCharacter.getCharFromName(name);
                var result = codePoint != -1
                        ? new String(Character.toChars(codePoint))
                        : "N{" + name + "}";
                appendToCurrentSegment(result);
            } catch (Exception e) {
                appendToCurrentSegment("N{" + name + "}");
            }
        } else {
            appendToCurrentSegment("N{" + nameBuilder);
        }
    }
}

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
        boolean isArray = sigil.equals("@");

        if (TokenUtils.peek(parser).text.equals("{")) {
            // Block-like interpolation
            StringParser.ParsedString rawStr2 = StringParser.parseRawStrings(parser, ctx, parser.tokens, parser.tokenIndex, 1);
            String blockStr = rawStr2.buffers.getFirst();
            ctx.logDebug("str block-like: " + blockStr);
            blockStr = sigil + "{" + blockStr + "}";
            Parser blockParser = new Parser(ctx, new Lexer(blockStr).tokenize());
            operand = ParseBlock.parseBlock(blockParser);
            parser.tokenIndex = rawStr2.next;
            ctx.logDebug("str operand " + operand);
        } else {
            String identifier = IdentifierParser.parseComplexIdentifier(parser);
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
            operand = new BinaryOperatorNode("join", new OperatorNode("$", new IdentifierNode("\"", tokenIndex), tokenIndex), operand, tokenIndex);
        }

        addStringSegment(operand);
    }

    /**
     * Parses array/hash access after variable interpolation
     */
    private Node parseArrayHashAccess(Node operand) {
        outerLoop:
        while (true) {
            String text = tokens.get(parser.tokenIndex).text;
            switch (text) {
                case "[":
                    if (isRegex) {
                        // maybe character class
                        LexerToken tokenNext = tokens.get(parser.tokenIndex + 1);
                        ctx.logDebug("str [ " + tokenNext);
                        if (!tokenNext.text.equals("$") && !(tokenNext.type == org.perlonjava.lexer.LexerTokenType.NUMBER)) {
                            break outerLoop;
                        }
                    }
                    operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                    ctx.logDebug("str operand " + operand);
                    break;
                case "{":
                    operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                    ctx.logDebug("str operand " + operand);
                    break;
                case "->":
                    int previousIndex = parser.tokenIndex;
                    parser.tokenIndex++;
                    text = tokens.get(parser.tokenIndex).text;
                    switch (text) {
                        case "[":
                        case "{":
                            parser.tokenIndex = previousIndex;  // Re-parse "->"
                            operand = ParseInfix.parseInfixOperation(parser, operand, 0);
                            ctx.logDebug("str operand " + operand);
                            break;
                        default:
                            parser.tokenIndex = previousIndex;
                            break outerLoop;
                    }
                default:
                    break outerLoop;
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

        if (segments.isEmpty()) {
            return new StringNode("", tokenIndex);
        } else if (segments.size() == 1) {
            Node result = segments.get(0);
            if (result instanceof StringNode) {
                return result;
            }
        }

        return new BinaryOperatorNode("join",
                new StringNode("", tokenIndex),
                new ListNode(segments, tokenIndex),
                tokenIndex);
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
            LexerToken token = tokens.get(parser.tokenIndex++);
            if (token.type == org.perlonjava.lexer.LexerTokenType.EOF) {
                break;
            }

            String text = token.text;
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
        switch (text) {
            case "\\":
                parseEscapeSequence();
                return true;
            case "$":
            case "@":
                if (shouldInterpolateVariable(text)) {
                    parseVariableInterpolation(text);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * Determines if a variable should be interpolated based on the following token.
     */
    private boolean shouldInterpolateVariable(String sigil) {
        LexerToken token1 = tokens.get(parser.tokenIndex);
        if (token1.type == org.perlonjava.lexer.LexerTokenType.EOF) {
            return false;
        }

        // Don't interpolate if followed by whitespace or certain characters
        return !(token1.type == org.perlonjava.lexer.LexerTokenType.WHITESPACE
                || token1.type == org.perlonjava.lexer.LexerTokenType.NEWLINE
                || token1.text.equals(")")
                || token1.text.equals("%")
                || token1.text.equals("|")
                || token1.text.equals("]")
                || token1.text.equals("#")
                || token1.text.equals("\"")
                || token1.text.equals("\\"));
    }

    void handleControlCharacter() {
        String ctl = TokenUtils.consumeChar(parser);
        if (!ctl.isEmpty()) {
            char chr = ctl.charAt(0);
            if (chr >= 'a' && chr <= 'z') {
                appendToCurrentSegment(String.valueOf((char) (chr - 'a' + 1)));
            } else {
                appendToCurrentSegment(String.valueOf((char) (chr - 'A' + 1)));
            }
        }
    }

    void handleHexEscape() {
        LexerToken token = tokens.get(parser.tokenIndex);
        String text = token.text;

        if (token.type == LexerTokenType.IDENTIFIER) {
            // Handle \x9 \x20
            String escape;
            if (text.length() <= 2) {
                escape = text;
                parser.tokenIndex++;
            } else {
                escape = text.substring(0, 2);
                token.text = text.substring(2);
            }
            appendToCurrentSegment(new String(Character.toChars(Integer.parseInt(escape, 16))));
        } else if (text.equals("{")) {
            // Handle \x{...} for Unicode
            parser.tokenIndex++;
            StringBuilder unicodeSeq = new StringBuilder();
            while (true) {
                token = tokens.get(parser.tokenIndex++);
                if (token.type == LexerTokenType.EOF) {
                    throw new PerlCompilerException(tokenIndex, "Expected '}' after \\x{", ctx.errorUtil);
                }
                if (token.text.equals("}")) {
                    break;
                }
                unicodeSeq.append(token.text);
            }
            appendToCurrentSegment(new String(Character.toChars(Integer.parseInt(unicodeSeq.toString().trim(), 16))));
        } else {
            throw new PerlCompilerException(tokenIndex, "Expected '{' after \\x", ctx.errorUtil);
        }
    }

    void handleUnicodeNameEscape() {
        LexerToken token = tokens.get(parser.tokenIndex);
        if (token.text.equals("{")) {
            parser.tokenIndex++;
            StringBuilder nameBuilder = new StringBuilder();
            while (true) {
                token = tokens.get(parser.tokenIndex++);
                if (token.type == LexerTokenType.EOF) {
                    throw new PerlCompilerException(tokenIndex, "Expected '}' after \\N{", ctx.errorUtil);
                }
                if (token.text.equals("}")) {
                    break;
                }
                nameBuilder.append(token.text);
            }
            String name = nameBuilder.toString().trim();

            int charCode;
            if (name.startsWith("U+")) {
                try {
                    charCode = Integer.parseInt(name.substring(2), 16);
                } catch (NumberFormatException e) {
                    throw new PerlCompilerException(tokenIndex, "Invalid Unicode code point: " + name, ctx.errorUtil);
                }
            } else {
                charCode = UCharacter.getCharFromName(name);
                if (charCode == -1) {
                    throw new PerlCompilerException(tokenIndex, "Invalid Unicode character name: " + name, ctx.errorUtil);
                }
            }

            appendToCurrentSegment(String.valueOf((char) charCode));
        } else {
            throw new PerlCompilerException(tokenIndex, "Expected '{' after \\N", ctx.errorUtil);
        }
    }
}
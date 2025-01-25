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
 * The StringDoubleQuoted class is responsible for parsing double-quoted strings.
 * It handles escape sequences, variable interpolation, and other Perl-specific
 * string parsing features.
 */
public class StringDoubleQuoted {

    /**
     * Parses a double-quoted string, handling escape sequences and variable interpolation.
     *
     * @param ctx          The EmitterContext used for logging and error handling.
     * @param rawStr       The raw string input to be parsed.
     * @param parseEscapes A boolean indicating whether escape sequences should be parsed.
     * @return A Node representing the parsed string.
     */
    static Node parseDoubleQuotedString(EmitterContext ctx, StringParser.ParsedString rawStr, boolean parseEscapes) {
        String input = rawStr.buffers.getFirst();
        int tokenIndex = rawStr.next;
        boolean isRegex = !parseEscapes;
        ctx.logDebug("parseDoubleQuotedString isRegex:" + isRegex);

        StringBuilder str = new StringBuilder();  // Buffer to hold the parsed string
        List<Node> parts = new ArrayList<>();  // List to hold parts of the parsed string

        Lexer lexer = new Lexer(input);
        List<LexerToken> tokens = lexer.tokenize();
        Parser parser = new Parser(ctx, tokens);
        ctx.quoteMetaEnabled = false;

        // Loop through the token array until the end
        while (true) {
            LexerToken token = tokens.get(parser.tokenIndex++);  // Get the current token
            if (token.type == LexerTokenType.EOF) {
                break;
            }
            String text = token.text;
            switch (text) {
                case "\\":
                    if (parseEscapes) {
                        parseDoubleQuotedEscapes(ctx, tokens, parser, str, tokenIndex, parts);
                    } else {
                        // Consume the escaped character without processing
                        str.append(text);
                        token = tokens.get(parser.tokenIndex);
                        if (token.text.length() == 1) {
                            str.append(token.text);
                            parser.tokenIndex++;
                        } else {
                            str.append(token.text.charAt(0));
                            token.text = token.text.substring(1);
                        }
                    }
                    break;
                case "$":
                case "@":
                    LexerToken token1 = tokens.get(parser.tokenIndex);
                    if (token1.type == LexerTokenType.EOF) {
                        // Final $ or @
                        str.append(text);
                        break;
                    }
                    if (token1.type == LexerTokenType.WHITESPACE
                            || token1.type == LexerTokenType.NEWLINE
                            || token1.text.equals(")")
                            || token1.text.equals("%")
                            || token1.text.equals("|")
                            || token1.text.equals("]")
                            || token1.text.equals("#")
                            || token1.text.equals("\"")
                            || token1.text.equals("\\")) {
                        // Space, `)`, `%`, `|`, `\` after $ or @
                        str.append(text);
                        break;
                    }
                    if (!str.isEmpty()) {
                        addStringSegment(ctx, parts, new StringNode(str.toString(), tokenIndex));  // Add the string so far to parts
                        str.setLength(0);  // Reset the buffer
                    }
                    String sigil = text;
                    ctx.logDebug("str sigil");
                    Node operand;
                    boolean isArray = sigil.equals("@");
                    if (TokenUtils.peek(parser).text.equals("{")) {
                        // Block-like
                        // Extract the string between brackets
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
                            // Parse $$a  @$a
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
                                throw new PerlCompilerException(tokenIndex, "Unexpected value after " + text + " in string", ctx.errorUtil);
                            }
                        } else {
                            operand = new IdentifierNode(identifier, tokenIndex);
                        }
                        ctx.logDebug("str Identifier: " + identifier);
                        operand = new OperatorNode(
                                text, operand, tokenIndex);
                        outerLoop:
                        while (true) {
                            text = tokens.get(parser.tokenIndex).text;
                            switch (text) {
                                case "[":
                                    if (isRegex) {
                                        // maybe character class
                                        LexerToken tokenNext = tokens.get(parser.tokenIndex + 1);
                                        ctx.logDebug("str [ " + tokenNext);
                                        if (!tokenNext.text.equals("$") && !(tokenNext.type == LexerTokenType.NUMBER)) {
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
                    }
                    if (isArray) {
                        operand = new BinaryOperatorNode("join", new OperatorNode("$", new IdentifierNode("\"", tokenIndex), tokenIndex), operand, tokenIndex);
                    }
                    addStringSegment(ctx, parts, operand);
                    break;
                default:
                    str.append(text);
            }
        }

        if (!str.isEmpty()) {
            addStringSegment(ctx, parts, new StringNode(str.toString(), tokenIndex));  // Add the remaining string to parts
        }

        // Join the parts
        if (parts.isEmpty()) {
            return new StringNode("", tokenIndex);
        } else if (parts.size() == 1) {
            Node result = parts.getFirst();
            if (result instanceof StringNode) {
                return parts.getFirst();
            }
        }
        return new BinaryOperatorNode("join",
                new StringNode("", tokenIndex),
                new ListNode(parts, tokenIndex),
                tokenIndex);
    }

    private static void addStringSegment(EmitterContext ctx, List<Node> parts, Node node) {
        if (ctx.quoteMetaEnabled) {
            parts.add(new OperatorNode("quotemeta", node, node.getIndex()));
        } else {
            parts.add(node);
        }
    }

    /**
     * Parses escape sequences within a double-quoted string.
     *
     * @param ctx        The EmitterContext used for logging and error handling.
     * @param tokens     The list of tokens to be parsed.
     * @param parser     The parser instance used for parsing.
     * @param str        The StringBuilder to append parsed characters to.
     * @param tokenIndex The current index of the token being parsed.
     */
    private static void parseDoubleQuotedEscapes(EmitterContext ctx, List<LexerToken> tokens, Parser parser, StringBuilder str, int tokenIndex, List<Node> parts) {
        LexerToken token;
        String text;
        String escape;
        token = tokens.get(parser.tokenIndex);
        if (token.type == LexerTokenType.NUMBER) {
            // Octal like `\200`
            StringBuilder octalStr = new StringBuilder(TokenUtils.consumeChar(parser));
            String chr = TokenUtils.peekChar(parser);
            while (octalStr.length() < 3 && chr.compareTo("0") >= 0 && chr.compareTo("7") <= 0) {
                octalStr.append(TokenUtils.consumeChar(parser));
                chr = TokenUtils.peekChar(parser);
            }
            ctx.logDebug("octalStr: " + octalStr);
            str.append((char) Integer.parseInt(octalStr.toString(), 8));
            return;
        }
        escape = TokenUtils.consumeChar(parser);
        switch (escape) {
            case "\\":
            case "\"":
                str.append(escape);  // Append the escaped character
                break;
            case "n":
                str.append('\n');  // Append newline
                break;
            case "t":
                str.append('\t');  // Append tab
                break;
            case "r":
                str.append('\r');  // Append carriage return
                break;
            case "f":
                str.append('\f');  // Append form feed
                break;
            case "b":
                str.append('\b');  // Append backspace
                break;
            case "a":
                str.append((char) 7);  // Append alarm
                break;
            case "e":
                str.append((char) 27);  // Append escape
                break;
            case "c":
                String ctl = TokenUtils.consumeChar(parser);
                if (!ctl.isEmpty()) {
                    // \cA is control-A char(1)
                    char chr = ctl.charAt(0);
                    if (chr >= 'a' && chr <= 'z') {
                        str.append((char) (chr - 'a' + 1));
                    } else {
                        str.append((char) (chr - 'A' + 1));
                    }
                }
                break;
            case "E":  // Marks the end of quotemeta sequence
                if (!str.isEmpty()) {
                    addStringSegment(ctx, parts, new StringNode(str.toString(), tokenIndex));  // Add the string so far to parts
                    str.setLength(0);  // Reset the buffer
                }
                ctx.quoteMetaEnabled = false;
                break;
            case "Q":   // Marks the start of quotemeta sequence
                if (!str.isEmpty()) {
                    addStringSegment(ctx, parts, new StringNode(str.toString(), tokenIndex));  // Add the string so far to parts
                    str.setLength(0);  // Reset the buffer
                }
                ctx.quoteMetaEnabled = true;
                break;
            case "x":
                StringBuilder unicodeSeq = new StringBuilder();
                token = tokens.get(parser.tokenIndex);
                text = token.text;
                if (token.type == LexerTokenType.IDENTIFIER) {
                    // Handle \x9 \x20
                    if (text.length() <= 2) {
                        escape = text;
                        parser.tokenIndex++;
                    } else {
                        escape = text.substring(0, 2);
                        token.text = text.substring(2);
                    }
                    str.append((char) Integer.parseInt(escape, 16));
                } else if (text.equals("{")) {
                    // Handle \x{...} for Unicode
                    parser.tokenIndex++;
                    while (true) {
                        token = tokens.get(parser.tokenIndex++);  // Get the current token
                        if (token.type == LexerTokenType.EOF) {
                            throw new PerlCompilerException(tokenIndex, "Expected '}' after \\x{", ctx.errorUtil);
                        }
                        if (token.text.equals("}")) {
                            break;
                        }
                        unicodeSeq.append(token.text);
                    }
                    str.append((char) Integer.parseInt(unicodeSeq.toString().trim(), 16));
                } else {
                    throw new PerlCompilerException(tokenIndex, "Expected '{' after \\x", ctx.errorUtil);
                }
                break;
            case "N":
                // Handle \N{name} for Unicode character names
                token = tokens.get(parser.tokenIndex);
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
                        // Handle \N{U+263D} format
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

                    str.append((char) charCode);
                } else {
                    throw new PerlCompilerException(tokenIndex, "Expected '{' after \\N", ctx.errorUtil);
                }
                break;
            default:
                str.append(escape);  // Append the backslash and the next character
                break;
        }
    }
}

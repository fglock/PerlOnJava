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
import java.util.Stack;

/**
 * The StringDoubleQuoted class is responsible for parsing double-quoted strings.
 * It handles escape sequences, variable interpolation, and other Perl-specific
 * string parsing features including case modification sequences.
 */
public class StringDoubleQuoted {

    /**
     * Represents the current case modification state
     */
    private static class CaseModifierState {
        String modifier;        // "U", "L", "u", "l", or null
        boolean isTemporary;    // true for \-u and \-l (applies to next char only)

        CaseModifierState(String modifier, boolean isTemporary) {
            this.modifier = modifier;
            this.isTemporary = isTemporary;
        }
    }

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
        Stack<CaseModifierState> caseModifierStack = new Stack<>();  // Stack for nested case modifiers

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
                        parseDoubleQuotedEscapes(ctx, tokens, parser, str, tokenIndex, parts, caseModifierStack);
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
                        appendWithCaseModification(str, text, caseModifierStack);
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
                        appendWithCaseModification(str, text, caseModifierStack);
                        break;
                    }
                    if (!str.isEmpty()) {
                        addStringSegmentWithCaseModification(ctx, parts, new StringNode(str.toString(), tokenIndex), caseModifierStack);
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
                    addStringSegmentWithCaseModification(ctx, parts, operand, caseModifierStack);
                    break;
                default:
                    appendWithCaseModification(str, text, caseModifierStack);
            }
        }

        if (!str.isEmpty()) {
            addStringSegmentWithCaseModification(ctx, parts, new StringNode(str.toString(), tokenIndex), caseModifierStack);
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

    /**
     * Appends text to the string buffer, applying case modifications as needed
     */
    private static void appendWithCaseModification(StringBuilder str, String text, Stack<CaseModifierState> caseModifierStack) {
        if (caseModifierStack.isEmpty()) {
            str.append(text);
            return;
        }

        String modifiedText = text;

        // Apply persistent modifiers first (from bottom of stack)
        for (CaseModifierState state : caseModifierStack) {
            if (!state.isTemporary) {
                modifiedText = applyCaseModification(modifiedText, state.modifier);
            }
        }

        // Then apply temporary modifiers (from top of stack, most recent first)
        for (int i = caseModifierStack.size() - 1; i >= 0; i--) {
            CaseModifierState state = caseModifierStack.get(i);
            if (state.isTemporary) {
                modifiedText = applyCaseModification(modifiedText, state.modifier);
            }
        }

        str.append(modifiedText);

        // Remove temporary modifiers after applying (from top to bottom)
        while (!caseModifierStack.isEmpty() && caseModifierStack.peek().isTemporary) {
            caseModifierStack.pop();
        }
    }

    /**
     * Adds a string segment with case modification applied
     */
    private static void addStringSegmentWithCaseModification(EmitterContext ctx, List<Node> parts, Node node, Stack<CaseModifierState> caseModifierStack) {
        Node finalNode = node;
        List<CaseModifierState> tempModifiers = new ArrayList<>();

        if (!caseModifierStack.isEmpty()) {
            // Collect active modifiers
            List<CaseModifierState> activeModifiers = new ArrayList<>(caseModifierStack);

            // Apply persistent modifiers first
            for (CaseModifierState state : activeModifiers) {
                if (!state.isTemporary) {
                    String caseOperator = getCaseOperator(state.modifier);
                    if (caseOperator != null) {
                        finalNode = new OperatorNode(caseOperator, finalNode, finalNode.getIndex());
                    }
                }
            }

            // Collect and apply temporary modifiers (in reverse order)
            for (int i = activeModifiers.size() - 1; i >= 0; i--) {
                CaseModifierState state = activeModifiers.get(i);
                if (state.isTemporary) {
                    String caseOperator = getCaseOperator(state.modifier);
                    if (caseOperator != null) {
                        finalNode = new OperatorNode(caseOperator, finalNode, finalNode.getIndex());
                    }
                    // Collect temporary modifiers to remove
                    tempModifiers.add(state);
                }
            }
        }

        // Apply quotemeta if enabled
        if (ctx.quoteMetaEnabled) {
            finalNode = new OperatorNode("quotemeta", finalNode, finalNode.getIndex());
        }

        parts.add(finalNode);

        // Remove temporary modifiers from stack
        for (CaseModifierState modifier : tempModifiers) {
            caseModifierStack.remove(modifier);
        }
    }

    /**
     * Applies case modification to a string
     */
    private static String applyCaseModification(String text, String modifier) {
        if (modifier == null) return text;

        switch (modifier) {
            case "U": return text.toUpperCase();
            case "L": return text.toLowerCase();
            case "u": return text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
            case "l": return text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
            default: return text;
        }
    }

    /**
     * Gets the corresponding case operator for code generation
     */
    private static String getCaseOperator(String modifier) {
        switch (modifier) {
            case "U": return "uc";
            case "L": return "lc";
            case "u": return "ucfirst";
            case "l": return "lcfirst";
            default: return null;
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
     * @param parts      The list of string parts being built.
     * @param caseModifierStack The stack of active case modifiers.
     */
    private static void parseDoubleQuotedEscapes(EmitterContext ctx, List<LexerToken> tokens, Parser parser, StringBuilder str, int tokenIndex, List<Node> parts, Stack<CaseModifierState> caseModifierStack) {
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
            char octalChar = (char) Integer.parseInt(octalStr.toString(), 8);
            appendWithCaseModification(str, String.valueOf(octalChar), caseModifierStack);
            return;
        }
        escape = TokenUtils.consumeChar(parser);
        switch (escape) {
            case "\\":
            case "\"":
                appendWithCaseModification(str, escape, caseModifierStack);
                break;
            case "n":
                appendWithCaseModification(str, "\n", caseModifierStack);
                break;
            case "t":
                appendWithCaseModification(str, "\t", caseModifierStack);
                break;
            case "r":
                appendWithCaseModification(str, "\r", caseModifierStack);
                break;
            case "f":
                appendWithCaseModification(str, "\f", caseModifierStack);
                break;
            case "b":
                appendWithCaseModification(str, "\b", caseModifierStack);
                break;
            case "a":
                appendWithCaseModification(str, String.valueOf((char) 7), caseModifierStack);
                break;
            case "e":
                appendWithCaseModification(str, String.valueOf((char) 27), caseModifierStack);
                break;
            case "c":
                String ctl = TokenUtils.consumeChar(parser);
                if (!ctl.isEmpty()) {
                    // \cA is control-A char(1)
                    char chr = ctl.charAt(0);
                    if (chr >= 'a' && chr <= 'z') {
                        appendWithCaseModification(str, String.valueOf((char) (chr - 'a' + 1)), caseModifierStack);
                    } else {
                        appendWithCaseModification(str, String.valueOf((char) (chr - 'A' + 1)), caseModifierStack);
                    }
                }
                break;
            case "E":  // Marks the end of case modification or quotemeta sequence
                if (!str.isEmpty()) {
                    // Apply current case modifications to the accumulated string before ending them
                    String currentStr = str.toString();
                    str.setLength(0);

                    // Apply case modifications to the current string segment
                    if (!caseModifierStack.isEmpty()) {
                        // Apply persistent modifiers first (from bottom of stack)
                        for (CaseModifierState state : caseModifierStack) {
                            if (!state.isTemporary) {
                                currentStr = applyCaseModification(currentStr, state.modifier);
                            }
                        }

                        // Then apply temporary modifiers (from top of stack, most recent first)
                        for (int i = caseModifierStack.size() - 1; i >= 0; i--) {
                            CaseModifierState state = caseModifierStack.get(i);
                            if (state.isTemporary) {
                                currentStr = applyCaseModification(currentStr, state.modifier);
                            }
                        }

                        // Remove temporary modifiers after applying
                        while (!caseModifierStack.isEmpty() && caseModifierStack.peek().isTemporary) {
                            caseModifierStack.pop();
                        }
                    }

                    // Add the processed string segment
                    if (ctx.quoteMetaEnabled) {
                        parts.add(new OperatorNode("quotemeta", new StringNode(currentStr, tokenIndex), tokenIndex));
                    } else {
                        parts.add(new StringNode(currentStr, tokenIndex));
                    }
                }

                // \E ends the most recent persistent modifier (U or L), or quotemeta
                // It does NOT end temporary modifiers (u or l)
                boolean foundPersistent = false;
                for (int i = caseModifierStack.size() - 1; i >= 0; i--) {
                    CaseModifierState state = caseModifierStack.get(i);
                    if (!state.isTemporary) {
                        caseModifierStack.remove(i);
                        foundPersistent = true;
                        break;
                    }
                }
                if (!foundPersistent) {
                    // No persistent case modifier found, try quotemeta
                    ctx.quoteMetaEnabled = false;
                }
                break;
            case "Q":   // Marks the start of quotemeta sequence
                if (!str.isEmpty()) {
                    addStringSegmentWithCaseModification(ctx, parts, new StringNode(str.toString(), tokenIndex), caseModifierStack);
                    str.setLength(0);  // Reset the buffer
                }
                ctx.quoteMetaEnabled = true;
                break;
            case "U":   // Start uppercase conversion
                if (!str.isEmpty()) {
                    addStringSegmentWithCaseModification(ctx, parts, new StringNode(str.toString(), tokenIndex), caseModifierStack);
                    str.setLength(0);  // Reset the buffer
                }
                caseModifierStack.push(new CaseModifierState("U", false));
                break;
            case "L":   // Start lowercase conversion
                if (!str.isEmpty()) {
                    addStringSegmentWithCaseModification(ctx, parts, new StringNode(str.toString(), tokenIndex), caseModifierStack);
                    str.setLength(0);  // Reset the buffer
                }
                caseModifierStack.push(new CaseModifierState("L", false));
                break;
            case "u":   // Uppercase next character only
                if (!str.isEmpty()) {
                    addStringSegmentWithCaseModification(ctx, parts, new StringNode(str.toString(), tokenIndex), caseModifierStack);
                    str.setLength(0);  // Reset the buffer
                }
                caseModifierStack.push(new CaseModifierState("u", true));
                break;
            case "l":   // Lowercase next character only
                if (!str.isEmpty()) {
                    addStringSegmentWithCaseModification(ctx, parts, new StringNode(str.toString(), tokenIndex), caseModifierStack);
                    str.setLength(0);  // Reset the buffer
                }
                caseModifierStack.push(new CaseModifierState("l", true));
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
                    appendWithCaseModification(str, new String(Character.toChars(Integer.parseInt(escape, 16))), caseModifierStack);
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
                    appendWithCaseModification(str, new String(Character.toChars(Integer.parseInt(unicodeSeq.toString().trim(), 16))), caseModifierStack);
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

                    appendWithCaseModification(str, String.valueOf((char) charCode), caseModifierStack);
                } else {
                    throw new PerlCompilerException(tokenIndex, "Expected '{' after \\N", ctx.errorUtil);
                }
                break;
            default:
                appendWithCaseModification(str, escape, caseModifierStack);
                break;
        }
    }
}
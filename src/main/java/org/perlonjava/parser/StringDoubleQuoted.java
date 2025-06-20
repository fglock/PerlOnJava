package org.perlonjava.parser;

import com.ibm.icu.lang.UCharacter;
import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;
import java.util.Stack;

/**
 * Parser for double-quoted strings with case modification and quotemeta support.
 * Extends StringSegmentParser to handle the base string parsing functionality.
 */
public class StringDoubleQuoted extends StringSegmentParser {

    /**
     * Represents the current case modification state
     */
    private static class CaseModifierState {
        String modifier;        // "U", "L", "u", "l", or null
        boolean isTemporary;    // true for \-u and \l (applies to next char only)

        CaseModifierState(String modifier, boolean isTemporary) {
            this.modifier = modifier;
            this.isTemporary = isTemporary;
        }
    }

    private final Stack<CaseModifierState> caseModifierStack;
    private final boolean parseEscapes;

    private StringDoubleQuoted(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex, boolean parseEscapes) {
        super(ctx, tokens, parser, tokenIndex, isRegex);
        this.parseEscapes = parseEscapes;
        this.caseModifierStack = new Stack<>();
    }

    /**
     * Parses a double-quoted string, handling escape sequences and variable interpolation.
     */
    static Node parseDoubleQuotedString(EmitterContext ctx, StringParser.ParsedString rawStr, boolean parseEscapes) {
        String input = rawStr.buffers.getFirst();
        int tokenIndex = rawStr.next;
        boolean isRegex = !parseEscapes;
        ctx.logDebug("parseDoubleQuotedString isRegex:" + isRegex);

        Lexer lexer = new Lexer(input);
        List<LexerToken> tokens = lexer.tokenize();
        Parser parser = new Parser(ctx, tokens);
        ctx.quoteMetaEnabled = false;

        StringDoubleQuoted doubleQuotedParser = new StringDoubleQuoted(ctx, tokens, parser, tokenIndex, isRegex, parseEscapes);
        return doubleQuotedParser.parse();
    }

    @Override
    protected void appendToCurrentSegment(String text) {
        appendWithCaseModification(currentSegment, text, caseModifierStack);
    }

    @Override
    protected void addStringSegment(Node node) {
        addStringSegmentWithCaseModification(ctx, segments, node, caseModifierStack);
    }

    @Override
    protected void parseEscapeSequence() {
        if (parseEscapes) {
            parseDoubleQuotedEscapes();
        } else {
            // Consume the escaped character without processing
            currentSegment.append("\\");
            LexerToken token = tokens.get(parser.tokenIndex);
            if (token.text.length() == 1) {
                currentSegment.append(token.text);
                parser.tokenIndex++;
            } else {
                currentSegment.append(token.text.charAt(0));
                token.text = token.text.substring(1);
            }
        }
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
        java.util.List<CaseModifierState> tempModifiers = new java.util.ArrayList<>();

        if (!caseModifierStack.isEmpty()) {
            // Collect active modifiers
            java.util.List<CaseModifierState> activeModifiers = new java.util.ArrayList<>(caseModifierStack);

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
     */
    private void parseDoubleQuotedEscapes() {
        LexerToken token = tokens.get(parser.tokenIndex);

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
            appendToCurrentSegment(String.valueOf(octalChar));
            return;
        }

        String escape = TokenUtils.consumeChar(parser);
        switch (escape) {
            case "\\":
            case "\"":
                appendToCurrentSegment(escape);
                break;
            case "n":
                appendToCurrentSegment("\n");
                break;
            case "t":
                appendToCurrentSegment("\t");
                break;
            case "r":
                appendToCurrentSegment("\r");
                break;
            case "f":
                appendToCurrentSegment("\f");
                break;
            case "b":
                appendToCurrentSegment("\b");
                break;
            case "a":
                appendToCurrentSegment(String.valueOf((char) 7));
                break;
            case "e":
                appendToCurrentSegment(String.valueOf((char) 27));
                break;
            case "c":
                handleControlCharacter();
                break;
            case "E":
                handleEndCaseModification();
                break;
            case "Q":
                handleStartQuoteMeta();
                break;
            case "U":
                handleStartUppercase();
                break;
            case "L":
                handleStartLowercase();
                break;
            case "u":
                handleUppercaseNext();
                break;
            case "l":
                handleLowercaseNext();
                break;
            case "x":
                handleHexEscape();
                break;
            case "N":
                handleUnicodeNameEscape();
                break;
            default:
                appendToCurrentSegment(escape);
                break;
        }
    }

    private void handleEndCaseModification() {
        flushCurrentSegment();

        // \E ends the most recent persistent modifier (U or L), or quotemeta
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
            ctx.quoteMetaEnabled = false;
        }
    }

    private void handleStartQuoteMeta() {
        flushCurrentSegment();
        ctx.quoteMetaEnabled = true;
    }

    private void handleStartUppercase() {
        flushCurrentSegment();
        caseModifierStack.push(new CaseModifierState("U", false));
    }

    private void handleStartLowercase() {
        flushCurrentSegment();
        caseModifierStack.push(new CaseModifierState("L", false));
    }

    private void handleUppercaseNext() {
        flushCurrentSegment();
        caseModifierStack.push(new CaseModifierState("u", true));
    }

    private void handleLowercaseNext() {
        flushCurrentSegment();
        caseModifierStack.push(new CaseModifierState("l", true));
    }
}

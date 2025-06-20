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
 * Parser for double-quoted strings with case modification and quotemeta support.
 * Extends StringSegmentParser to handle the base string parsing functionality.
 */
public class StringDoubleQuoted extends StringSegmentParser {

    private static class CaseModifier {
        String type; // "U", "L", "u", "l"
        int startSegment; // segment index where this modifier starts
        List<Node> segmentsUnderModifier; // segments that should be affected by this modifier

        CaseModifier(String type, int startSegment) {
            this.type = type;
            this.startSegment = startSegment;
            this.segmentsUnderModifier = new ArrayList<>();
        }
    }

    private final Stack<CaseModifier> activeCaseModifiers = new Stack<>();
    private final boolean parseEscapes;

    private StringDoubleQuoted(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex, boolean parseEscapes) {
        super(ctx, tokens, parser, tokenIndex, isRegex);
        this.parseEscapes = parseEscapes;
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

        StringDoubleQuoted doubleQuotedParser = new StringDoubleQuoted(ctx, tokens, parser, tokenIndex, isRegex, parseEscapes);
        return doubleQuotedParser.parse();
    }

    @Override
    protected void appendToCurrentSegment(String text) {
        currentSegment.append(text);
    }

    @Override
    protected void addStringSegment(Node node) {
        segments.add(node);

        // Add this segment to all active case modifiers
        for (CaseModifier modifier : activeCaseModifiers) {
            modifier.segmentsUnderModifier.add(node);
        }
    }

    @Override
    public Node parse() {
        Node result = super.parse();

        // Close any remaining case modifications at end of string
        while (!activeCaseModifiers.isEmpty()) {
            applyCaseModifier(activeCaseModifiers.pop());
        }

        return createJoinIfNeeded(segments);
    }

    private void applyCaseModifier(CaseModifier modifier) {
        if (modifier.segmentsUnderModifier.isEmpty()) {
            return;
        }

        String operator = getCaseOperator(modifier.type);
        if (operator == null) {
            return;
        }

        // Create a join node for the segments under this modifier
        Node contentNode = createJoinIfNeeded(modifier.segmentsUnderModifier);

        // Create the case modification operator node
        Node caseModifiedNode = new OperatorNode(operator, contentNode, parser.tokenIndex);

        // Replace the segments in the main segments list
        // Find the range of segments that were affected
        int firstAffectedIndex = -1;
        int lastAffectedIndex = -1;

        for (int i = 0; i < segments.size(); i++) {
            if (modifier.segmentsUnderModifier.contains(segments.get(i))) {
                if (firstAffectedIndex == -1) {
                    firstAffectedIndex = i;
                }
                lastAffectedIndex = i;
            }
        }

        if (firstAffectedIndex != -1) {
            // Remove the affected segments and replace with the case-modified node
            for (int i = lastAffectedIndex; i >= firstAffectedIndex; i--) {
                segments.remove(i);
            }
            segments.add(firstAffectedIndex, caseModifiedNode);

            // Update segments in parent case modifiers
            for (CaseModifier parentModifier : activeCaseModifiers) {
                // Remove old segments from parent modifiers
                parentModifier.segmentsUnderModifier.removeAll(modifier.segmentsUnderModifier);
                // Add the new case-modified node
                parentModifier.segmentsUnderModifier.add(caseModifiedNode);
            }
        }
    }

    private Node createJoinIfNeeded(List<Node> nodeList) {
        if (nodeList.isEmpty()) {
            return new StringNode("", parser.tokenIndex);
        } else if (nodeList.size() == 1) {
            return nodeList.get(0);
        } else {
            ListNode listNode = new ListNode(parser.tokenIndex);
            for (Node node : nodeList) {
                listNode.elements.add(node);
            }
            return new BinaryOperatorNode("join", new StringNode("", parser.tokenIndex), listNode, parser.tokenIndex);
        }
    }

    private String getCaseOperator(String modifier) {
        switch (modifier) {
            case "U": return "uc";
            case "L": return "lc";
            case "u": return "ucfirst";
            case "l": return "lcfirst";
            default: return null;
        }
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
                // Handle quotemeta if needed
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

        if (!activeCaseModifiers.isEmpty()) {
            // Immediately apply the most recent case modifier
            applyCaseModifier(activeCaseModifiers.pop());
        }
    }

    private void handleStartUppercase() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("U", segments.size()));
    }

    private void handleStartLowercase() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("L", segments.size()));
    }

    private void handleUppercaseNext() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("u", segments.size()));
    }

    private void handleLowercaseNext() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("l", segments.size()));
    }

    @Override
    void handleControlCharacter() {
        String controlChar = TokenUtils.consumeChar(parser);
        if (!controlChar.isEmpty()) {
            char c = controlChar.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                appendToCurrentSegment(String.valueOf((char)(c - 'A' + 1)));
            } else if (c >= 'a' && c <= 'z') {
                appendToCurrentSegment(String.valueOf((char)(c - 'a' + 1)));
            } else {
                appendToCurrentSegment(String.valueOf(c));
            }
        }
    }

    @Override
    void handleHexEscape() {
        StringBuilder hexStr = new StringBuilder();
        String chr = TokenUtils.peekChar(parser);

        if (chr.equals("{")) {
            TokenUtils.consumeChar(parser);
            chr = TokenUtils.peekChar(parser);
            while (!chr.equals("}") && !chr.isEmpty()) {
                if ((chr.compareTo("0") >= 0 && chr.compareTo("9") <= 0) ||
                        (chr.compareToIgnoreCase("a") >= 0 && chr.compareToIgnoreCase("f") <= 0)) {
                    hexStr.append(TokenUtils.consumeChar(parser));
                    chr = TokenUtils.peekChar(parser);
                } else {
                    break;
                }
            }
            if (chr.equals("}")) {
                TokenUtils.consumeChar(parser);
            }
        } else {
            while (hexStr.length() < 2 &&
                    ((chr.compareTo("0") >= 0 && chr.compareTo("9") <= 0) ||
                            (chr.compareToIgnoreCase("a") >= 0 && chr.compareToIgnoreCase("f") <= 0))) {
                hexStr.append(TokenUtils.consumeChar(parser));
                chr = TokenUtils.peekChar(parser);
            }
        }

        if (hexStr.length() > 0) {
            try {
                int hexValue = Integer.parseInt(hexStr.toString(), 16);
                if (hexValue <= 0xFFFF) {
                    appendToCurrentSegment(String.valueOf((char)hexValue));
                } else {
                    appendToCurrentSegment(new String(Character.toChars(hexValue)));
                }
            } catch (NumberFormatException e) {
                appendToCurrentSegment("x");
            }
        } else {
            appendToCurrentSegment("x");
        }
    }

    @Override
    void handleUnicodeNameEscape() {
        if (TokenUtils.peekChar(parser).equals("{")) {
            TokenUtils.consumeChar(parser);
            StringBuilder nameBuilder = new StringBuilder();
            String chr = TokenUtils.peekChar(parser);

            while (!chr.equals("}") && !chr.isEmpty()) {
                nameBuilder.append(TokenUtils.consumeChar(parser));
                chr = TokenUtils.peekChar(parser);
            }

            if (chr.equals("}")) {
                TokenUtils.consumeChar(parser);
                String name = nameBuilder.toString();
                try {
                    int codePoint = UCharacter.getCharFromName(name);
                    if (codePoint != -1) {
                        appendToCurrentSegment(new String(Character.toChars(codePoint)));
                    } else {
                        appendToCurrentSegment("N{" + name + "}");
                    }
                } catch (Exception e) {
                    appendToCurrentSegment("N{" + name + "}");
                }
            } else {
                appendToCurrentSegment("N{" + nameBuilder.toString());
            }
        } else {
            appendToCurrentSegment("N");
        }
    }
}

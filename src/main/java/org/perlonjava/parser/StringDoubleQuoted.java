package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Parser for double-quoted strings with case modification and quotemeta support.
 * Extends StringSegmentParser to handle the base string parsing functionality.
 */
public class StringDoubleQuoted extends StringSegmentParser {

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
        var input = rawStr.buffers.getFirst();
        var tokenIndex = rawStr.next;
        var isRegex = !parseEscapes;
        ctx.logDebug("parseDoubleQuotedString isRegex:" + isRegex);

        var lexer = new Lexer(input);
        var tokens = lexer.tokenize();
        var parser = new Parser(ctx, tokens);

        var doubleQuotedParser = new StringDoubleQuoted(ctx, tokens, parser, tokenIndex, isRegex, parseEscapes);
        return doubleQuotedParser.parse();
    }

    @Override
    protected void addStringSegment(Node node) {
        segments.add(node);

        // Add this segment to all active case modifiers
        activeCaseModifiers.forEach(modifier -> modifier.segmentsUnderModifier().add(node));
    }

    @Override
    public Node parse() {
        var result = super.parse();

        // Close any remaining case modifications at end of string
        while (!activeCaseModifiers.isEmpty()) {
            applyCaseModifier(activeCaseModifiers.pop());
        }

        return createJoinIfNeeded(segments);
    }

    private void applyCaseModifier(CaseModifier modifier) {
        if (modifier.segmentsUnderModifier().isEmpty()) {
            return;
        }

        var operator = getCaseOperator(modifier.type());
        if (operator == null) {
            return;
        }

        // Create a join node for the segments under this modifier
        var contentNode = createJoinIfNeeded(modifier.segmentsUnderModifier());

        // Create the case modification operator node
        var caseModifiedNode = new OperatorNode(operator, contentNode, parser.tokenIndex);

        // Replace the segments in the main segments list
        // Find the range of segments that were affected
        var firstAffectedIndex = -1;
        var lastAffectedIndex = -1;

        for (int i = 0; i < segments.size(); i++) {
            if (modifier.segmentsUnderModifier().contains(segments.get(i))) {
                if (firstAffectedIndex == -1) {
                    firstAffectedIndex = i;
                }
                lastAffectedIndex = i;
            }
        }

        if (firstAffectedIndex != -1) {
            // Remove the affected segments and replace with the case-modified node
            if (lastAffectedIndex >= firstAffectedIndex) {
                segments.subList(firstAffectedIndex, lastAffectedIndex + 1).clear();
            }
            segments.add(firstAffectedIndex, caseModifiedNode);

            // Update segments in parent case modifiers
            activeCaseModifiers.forEach(parentModifier -> {
                // Remove old segments from parent modifiers
                parentModifier.segmentsUnderModifier().removeAll(modifier.segmentsUnderModifier());
                // Add the new case-modified node
                parentModifier.segmentsUnderModifier().add(caseModifiedNode);
            });
        }
    }

    private Node createJoinIfNeeded(List<Node> nodeList) {
        return switch (nodeList.size()) {
            case 0 -> new StringNode("", parser.tokenIndex);
            case 1 -> nodeList.get(0);
            default -> {
                var listNode = new ListNode(parser.tokenIndex);
                listNode.elements.addAll(nodeList);
                yield new BinaryOperatorNode("join", new StringNode("", parser.tokenIndex), listNode, parser.tokenIndex);
            }
        };
    }

    private String getCaseOperator(String modifier) {
        return switch (modifier) {
            case "U" -> "uc";
            case "L" -> "lc";
            case "u" -> "ucfirst";
            case "l" -> "lcfirst";
            default -> null;
        };
    }

    @Override
    protected void parseEscapeSequence() {
        if (parseEscapes) {
            parseDoubleQuotedEscapes();
        } else {
            // Consume the escaped character without processing
            currentSegment.append("\\");
            var token = tokens.get(parser.tokenIndex);
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
        var token = tokens.get(parser.tokenIndex);

        if (token.type == LexerTokenType.NUMBER) {
            // Octal like `\200`
            var octalStr = new StringBuilder(TokenUtils.consumeChar(parser));
            var chr = TokenUtils.peekChar(parser);
            while (octalStr.length() < 3 && chr.compareTo("0") >= 0 && chr.compareTo("7") <= 0) {
                octalStr.append(TokenUtils.consumeChar(parser));
                chr = TokenUtils.peekChar(parser);
            }
            ctx.logDebug("octalStr: " + octalStr);
            var octalChar = (char) Integer.parseInt(octalStr.toString(), 8);
            appendToCurrentSegment(String.valueOf(octalChar));
            return;
        }

        var escape = TokenUtils.consumeChar(parser);

        switch (escape) {
            case "\\", "\"" -> appendToCurrentSegment(escape);
            case "n" -> appendToCurrentSegment("\n");
            case "t" -> appendToCurrentSegment("\t");
            case "r" -> appendToCurrentSegment("\r");
            case "f" -> appendToCurrentSegment("\f");
            case "b" -> appendToCurrentSegment("\b");
            case "a" -> appendToCurrentSegment(String.valueOf((char) 7));
            case "e" -> appendToCurrentSegment(String.valueOf((char) 27));
            case "c" -> handleControlCharacter();
            case "E" -> handleEndCaseModification();
            case "Q" -> {
                // Handle quotemeta if needed
            }
            case "U" -> handleStartUppercase();
            case "L" -> handleStartLowercase();
            case "u" -> handleUppercaseNext();
            case "l" -> handleLowercaseNext();
            case "x" -> handleHexEscape();
            case "N" -> handleUnicodeNameEscape();
            default -> appendToCurrentSegment(escape);
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

    /**
     * Record representing a case modification state.
     *
     * @param type the type of case modification ("U", "L", "u", "l")
     * @param startSegment the segment index where this modifier starts
     * @param segmentsUnderModifier the segments that should be affected by this modifier
     */
    private record CaseModifier(
            String type,
            int startSegment,
            List<Node> segmentsUnderModifier
    ) {
        CaseModifier(String type, int startSegment) {
            this(type, startSegment, new ArrayList<>());
        }
    }
}

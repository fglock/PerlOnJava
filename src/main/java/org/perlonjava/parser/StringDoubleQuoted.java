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
 *
 * <p>This class extends StringSegmentParser to handle Perl's double-quoted string syntax,
 * which includes variable interpolation, escape sequences, and case modification operators.
 * Double-quoted strings in Perl support several advanced features:</p>
 *
 * <ul>
 *   <li><strong>Variable interpolation:</strong> $var, @array, ${expr}</li>
 *   <li><strong>Escape sequences:</strong> \n, \t, \x{hex}, \N{unicode_name}</li>
 *   <li><strong>Case modification:</strong> \U...\E (uppercase), \L...\E (lowercase)</li>
 *   <li><strong>Single character case:</strong> \\u (next char upper), \l (next char lower)</li>
 *   <li><strong>Quote metacharacters:</strong> \Q...\E (escape regex metacharacters)</li>
 * </ul>
 *
 * <p>Case modifications can be nested and are applied in the order they appear.
 * For example: "\L\\u$name\E" will lowercase the entire interpolated name except
 * for the first character which will be uppercase.</p>
 *
 * @see StringSegmentParser
 * @see StringParser
 */
public class StringDoubleQuoted extends StringSegmentParser {

    /** Stack of active case modifiers, supporting nested case modifications */
    private final Stack<CaseModifier> activeCaseModifiers = new Stack<>();

    /** Flag indicating whether escape sequences should be processed */
    private final boolean parseEscapes;

    /**
     * Private constructor for StringDoubleQuoted parser.
     *
     * @param ctx the emitter context for logging and error handling
     * @param tokens the list of tokens representing the string content
     * @param parser the parser instance for parsing embedded expressions
     * @param tokenIndex the token index in the original source for error reporting
     * @param isRegex flag indicating if this is parsing a regex pattern
     * @param parseEscapes flag indicating whether to process escape sequences
     */
    private StringDoubleQuoted(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex, boolean parseEscapes) {
        super(ctx, tokens, parser, tokenIndex, isRegex);
        this.parseEscapes = parseEscapes;
    }

    /**
     * Parses a double-quoted string, handling escape sequences and variable interpolation.
     *
     * <p>This is the main entry point for parsing double-quoted strings. It creates
     * a lexer for the string content and delegates to a StringDoubleQuoted instance
     * to handle the parsing.</p>
     *
     * <p>The parseEscapes parameter determines whether escape sequences like \n, \t
     * should be processed. When false (typically for regex contexts), escape sequences
     * are preserved literally.</p>
     *
     * @param ctx the emitter context for logging and error handling
     * @param rawStr the parsed string data containing the string content and position info
     * @param parseEscapes whether to process escape sequences or preserve them literally
     * @return an AST node representing the parsed string
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

    /**
     * Adds a string segment to the segments list and registers it with active case modifiers.
     *
     * <p>This override ensures that any segments added while case modifiers are active
     * are tracked so they can be transformed when the case modifier is closed.
     * This enables proper handling of nested case modifications.</p>
     *
     * @param node the AST node representing a string segment
     */
    @Override
    protected void addStringSegment(Node node) {
        segments.add(node);

        // Register this segment with all currently active case modifiers
        // This allows nested case modifications to work correctly
        activeCaseModifiers.forEach(modifier -> modifier.segmentsUnderModifier().add(node));
    }

    /**
     * Parses the string and applies any remaining case modifications.
     *
     * <p>This override of the template method ensures that any case modifications
     * that are still active at the end of the string are properly applied.
     * This handles cases where \U or \L is used without a corresponding \E.</p>
     *
     * @return the final AST node representing the complete parsed string
     */
    @Override
    public Node parse() {
        var result = super.parse();

        // Apply any case modifications that weren't explicitly closed with \E
        while (!activeCaseModifiers.isEmpty()) {
            applyCaseModifier(activeCaseModifiers.pop());
        }

        return createJoinIfNeeded(segments);
    }

    /**
     * Applies a case modification to its associated segments.
     *
     * <p>This method transforms the segments that were collected under a case modifier
     * by wrapping them in the appropriate case transformation operator (uc, lc, ucfirst, lcfirst).
     * The transformed segments replace the original segments in the main segments list.</p>
     *
     * <p>For nested case modifiers, the method also updates parent modifiers to reference
     * the new transformed node instead of the individual segments.</p>
     *
     * @param modifier the case modifier to apply
     */
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
        // Find the range of segments that were affected by this modifier
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

            // Update segments in parent case modifiers to reference the new transformed node
            activeCaseModifiers.forEach(parentModifier -> {
                // Remove old segments from parent modifiers
                parentModifier.segmentsUnderModifier().removeAll(modifier.segmentsUnderModifier());
                // Add the new case-modified node
                parentModifier.segmentsUnderModifier().add(caseModifiedNode);
            });
        }
    }

    /**
     * Creates a join operation if multiple nodes are present, otherwise returns the single node.
     *
     * <p>This utility method handles the common pattern of needing to join multiple string
     * segments together. It optimizes for the common cases:</p>
     * <ul>
     *   <li>0 nodes: returns empty string</li>
     *   <li>1 node: returns the node directly</li>
     *   <li>Multiple nodes: creates a join operation with empty separator</li>
     * </ul>
     *
     * @param nodeList the list of nodes to potentially join
     * @return a single node representing the joined content
     */
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

    /**
     * Maps case modification escape sequences to their corresponding operators.
     *
     * <p>Perl supports several case modification operators:</p>
     * <ul>
     *   <li>\U - uppercase all following characters until \E</li>
     *   <li>\L - lowercase all following characters until \E</li>
     *   <li>\\u - uppercase only the next character</li>
     *   <li>\l - lowercase only the next character</li>
     * </ul>
     *
     * @param modifier the case modification type ("U", "L", "u", "l")
     * @return the corresponding operator name, or null if not recognized
     */
    private String getCaseOperator(String modifier) {
        return switch (modifier) {
            case "U" -> "uc";        // uppercase
            case "L" -> "lc";        // lowercase
            case "u" -> "ucfirst";   // uppercase first character
            case "l" -> "lcfirst";   // lowercase first character
            default -> null;
        };
    }

    /**
     * Parses escape sequences in double-quoted strings.
     *
     * <p>This method handles the escape sequence processing based on the parseEscapes flag.
     * When parseEscapes is true, escape sequences are processed and converted to their
     * corresponding characters. When false (typically in regex contexts), the escape
     * sequences are preserved literally.</p>
     */
    @Override
    protected void parseEscapeSequence() {
        if (parseEscapes) {
            parseDoubleQuotedEscapes();
        } else {
            // In regex context, preserve escape sequences literally
            // Consume the escaped character without processing
            currentSegment.append("\\");
            var token = tokens.get(parser.tokenIndex);
            if (token.text.length() == 1) {
                currentSegment.append(token.text);
                parser.tokenIndex++;
            } else {
                // Handle multi-character tokens by consuming only the first character
                currentSegment.append(token.text.charAt(0));
                token.text = token.text.substring(1);
            }
        }
    }

    /**
     * Processes escape sequences specific to double-quoted strings.
     *
     * <p>This method handles all the escape sequences supported in Perl double-quoted strings:</p>
     * <ul>
     *   <li><strong>Standard escapes:</strong> \\, \", \n, \t, \r, \f, \b, \a, \e</li>
     *   <li><strong>Octal escapes:</strong> \123 (up to 3 octal digits)</li>
     *   <li><strong>Hex escapes:</strong> \x41, \x{41}</li>
     *   <li><strong>Unicode names:</strong> \N{LATIN CAPITAL LETTER A}</li>
     *   <li><strong>Control characters:</strong> \cA, \cZ</li>
     *   <li><strong>Case modifications:</strong> \U, \L, \\u, \l, \E</li>
     *   <li><strong>Quote meta:</strong> \Q (not fully implemented)</li>
     * </ul>
     */
    private void parseDoubleQuotedEscapes() {
        var token = tokens.get(parser.tokenIndex);

        if (token.type == LexerTokenType.NUMBER) {
            // Handle octal escape sequences like \200
            var octalStr = new StringBuilder(TokenUtils.consumeChar(parser));
            var chr = TokenUtils.peekChar(parser);
            // Consume up to 3 octal digits
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
            // Standard character escapes
            case "\\" -> appendToCurrentSegment("\\");
            case "\"" -> appendToCurrentSegment("\"");
            case "n" -> appendToCurrentSegment("\n");    // newline
            case "t" -> appendToCurrentSegment("\t");    // tab
            case "r" -> appendToCurrentSegment("\r");    // carriage return
            case "f" -> appendToCurrentSegment("\f");    // form feed
            case "b" -> appendToCurrentSegment("\b");    // backspace
            case "a" -> appendToCurrentSegment(String.valueOf((char) 7));  // bell
            case "e" -> appendToCurrentSegment(String.valueOf((char) 27)); // escape

            // Control character escape
            case "c" -> handleControlCharacter();

            // Case modification controls
            case "E" -> handleEndCaseModification();     // End case modification
            case "Q" -> {
                // Handle quotemeta - escape regex metacharacters
                // TODO: Implement quotemeta functionality
            }
            case "U" -> handleStartUppercase();          // Start uppercase
            case "L" -> handleStartLowercase();          // Start lowercase
            case "u" -> handleUppercaseNext();           // Uppercase next character
            case "l" -> handleLowercaseNext();           // Lowercase next character

            // Numeric escapes
            case "x" -> handleHexEscape();               // Hex escape
            case "N" -> handleUnicodeNameEscape();       // Unicode name escape

            // Unknown escape - treat literally
            default -> appendToCurrentSegment(escape);
        }
    }

    /**
     * Handles the \E escape sequence to end case modification.
     *
     * <p>The \E sequence closes the most recently opened case modification (\U, \L, \\u, or \l).
     * This method flushes any pending literal text and immediately applies the case
     * modification to all segments that were collected since the case modifier was started.</p>
     */
    private void handleEndCaseModification() {
        flushCurrentSegment();

        if (!activeCaseModifiers.isEmpty()) {
            // Apply the most recent case modifier and remove it from the stack
            applyCaseModifier(activeCaseModifiers.pop());
        }
    }

    /**
     * Handles the \U escape sequence to start uppercase modification.
     *
     * <p>The \U sequence starts converting all following characters to uppercase
     * until a corresponding \E is encountered. This can be nested with other
     * case modifications.</p>
     */
    private void handleStartUppercase() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("U", segments.size()));
    }

    /**
     * Handles the \L escape sequence to start lowercase modification.
     *
     * <p>The \L sequence starts converting all following characters to lowercase
     * until a corresponding \E is encountered. This can be nested with other
     * case modifications.</p>
     */
    private void handleStartLowercase() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("L", segments.size()));
    }

    /**
     * Handles the \\u escape sequence to uppercase the next character.
     *
     * <p>The \\u sequence converts only the next character to uppercase.
     * Unlike \U, this doesn't require a closing \E and affects only one character.</p>
     */
    private void handleUppercaseNext() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("u", segments.size()));
    }

    /**
     * Handles the \l escape sequence to lowercase the next character.
     *
     * <p>The \l sequence converts only the next character to lowercase.
     * Unlike \L, this doesn't require a closing \E and affects only one character.</p>
     */
    private void handleLowercaseNext() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("l", segments.size()));
    }

    /**
     * Record representing a case modification state.
     *
     * <p>This record tracks the state of an active case modification, including
     * what type of modification it is and which segments should be affected by it.
     * The segments list is populated as new segments are added while this modifier
     * is active.</p>
     *
     * @param type the type of case modification ("U", "L", "u", "l")
     * @param startSegment the segment index where this modifier starts (for debugging)
     * @param segmentsUnderModifier the segments that should be affected by this modifier
     */
    private record CaseModifier(
            String type,
            int startSegment,
            List<Node> segmentsUnderModifier
    ) {
        /**
         * Convenience constructor that creates an empty segments list.
         *
         * @param type the type of case modification
         * @param startSegment the segment index where this modifier starts
         */
        CaseModifier(String type, int startSegment) {
            this(type, startSegment, new ArrayList<>());
        }
    }
}

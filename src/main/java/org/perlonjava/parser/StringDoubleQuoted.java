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

    /** Track if we need to check for single-char modifier completion */
    private boolean needToCheckSingleCharModifier = false;

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
        // First check if we need to deactivate any single-char modifiers
        if (needToCheckSingleCharModifier) {
            checkAndDeactivateSingleCharModifiers();
            needToCheckSingleCharModifier = false;
        }

        segments.add(node);

        // Register this segment with all currently active case modifiers
        activeCaseModifiers.forEach(modifier -> modifier.segmentsUnderModifier().add(node));

        // Mark that we should check for single-char modifier completion
        // after this segment has been added
        if (!activeCaseModifiers.isEmpty() && activeCaseModifiers.peek().isSingleChar()) {
            needToCheckSingleCharModifier = true;
        }
    }

    /**
     * Override to handle literal text appending.
     * This ensures single-char modifiers are checked even for literal text.
     */
    @Override
    protected void appendToCurrentSegment(String text) {
        super.appendToCurrentSegment(text);

        // If we just added text and have an active single-char modifier,
        // we need to check if we should deactivate it
        if (!text.isEmpty() && !activeCaseModifiers.isEmpty() && activeCaseModifiers.peek().isSingleChar()) {
            // Mark that the single-char modifier has affected content
            activeCaseModifiers.peek().setHasAffectedContent(true);
        }
    }

    /**
     * Override to flush segments properly.
     */
    @Override
    protected void flushCurrentSegment() {
        super.flushCurrentSegment();

        // After flushing, check if we need to deactivate single-char modifiers
        if (needToCheckSingleCharModifier) {
            checkAndDeactivateSingleCharModifiers();
            needToCheckSingleCharModifier = false;
        }
    }

    /**
     * Checks for single-character modifiers and deactivates them after affecting one segment.
     * Single-character modifiers (\l, \\u) should only affect the next character/segment.
     */
    private void checkAndDeactivateSingleCharModifiers() {
        while (!activeCaseModifiers.isEmpty()) {
            var topModifier = activeCaseModifiers.peek();
            if (topModifier.isSingleChar() && topModifier.hasAffectedContent()) {
                // Apply and remove the single-character modifier
                applyCaseModifier(activeCaseModifiers.pop());
            } else {
                break;
            }
        }
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

        // Find and replace the affected segments in the main segments list
        replaceSegmentsWithCaseModified(modifier, caseModifiedNode);
    }

    /**
     * Replaces segments affected by a case modifier with the case-modified node.
     * This method properly handles the segment replacement logic.
     */
    private void replaceSegmentsWithCaseModified(CaseModifier modifier, Node caseModifiedNode) {
        var affectedSegments = modifier.segmentsUnderModifier();
        if (affectedSegments.isEmpty()) {
            return;
        }

        // Find all indices of affected segments
        List<Integer> indicesToRemove = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (affectedSegments.contains(segments.get(i))) {
                indicesToRemove.add(i);
            }
        }

        if (!indicesToRemove.isEmpty()) {
            // Remove segments in reverse order to maintain indices
            int insertIndex = indicesToRemove.get(0);
            for (int i = indicesToRemove.size() - 1; i >= 0; i--) {
                segments.remove((int) indicesToRemove.get(i));
            }

            // Insert the case-modified node at the first position
            segments.add(insertIndex, caseModifiedNode);

            // Update parent case modifiers
            updateParentModifiers(affectedSegments, caseModifiedNode);
        }
    }

    /**
     * Updates parent case modifiers to reference the new case-modified node
     * instead of the individual segments that were replaced.
     */
    private void updateParentModifiers(List<Node> oldSegments, Node newNode) {
        for (var parentModifier : activeCaseModifiers) {
            boolean hadAnyOldSegment = false;
            for (var oldSegment : oldSegments) {
                if (parentModifier.segmentsUnderModifier().remove(oldSegment)) {
                    hadAnyOldSegment = true;
                }
            }
            // Add the new node only once if we removed any old segments
            if (hadAnyOldSegment) {
                parentModifier.segmentsUnderModifier().add(newNode);
            }
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
     * Validates that a new case modifier can be started.
     * Perl doesn't allow \L immediately followed by \U (and vice versa).
     */
    private void validateCaseModifierSequence(String newModifier) {
        if (!activeCaseModifiers.isEmpty()) {
            var topModifier = activeCaseModifiers.peek();
            // Check for invalid sequences: \L\U or \U\L
            if ((topModifier.type().equals("L") && newModifier.equals("U")) ||
                    (topModifier.type().equals("U") && newModifier.equals("L"))) {
                // Check if the top modifier has no content yet (immediate sequence)
                if (topModifier.segmentsUnderModifier().isEmpty() && currentSegment.length() == 0) {
                    throw new RuntimeException("syntax error: \\" + topModifier.type() + "\\" + newModifier + " is not allowed");
                }
            }
        }
    }

    /**
     * Handles the \U escape sequence to start uppercase modification.
     *
     * <p>The \U sequence starts converting all following characters to uppercase
     * until a corresponding \E is encountered. If \L is currently active,
     * \U first closes it (like \E would) before starting uppercase.</p>
     */
    private void handleStartUppercase() {
        validateCaseModifierSequence("U");
        flushCurrentSegment();

        // If \L is currently active, close it first (like \E would)
        if (!activeCaseModifiers.isEmpty() && activeCaseModifiers.peek().type().equals("L")) {
            applyCaseModifier(activeCaseModifiers.pop());
        }

        activeCaseModifiers.push(new CaseModifier("U", segments.size(), false));
    }

    /**
     * Handles the \L escape sequence to start lowercase modification.
     *
     * <p>The \L sequence starts converting all following characters to lowercase
     * until a corresponding \E is encountered. If \U is currently active,
     * \L first closes it (like \E would) before starting lowercase.</p>
     */
    private void handleStartLowercase() {
        validateCaseModifierSequence("L");
        flushCurrentSegment();

        // If \U is currently active, close it first (like \E would)
        if (!activeCaseModifiers.isEmpty() && activeCaseModifiers.peek().type().equals("U")) {
            applyCaseModifier(activeCaseModifiers.pop());
        }

        activeCaseModifiers.push(new CaseModifier("L", segments.size(), false));
    }

    /**
     * Handles the \\u escape sequence to uppercase the next character.
     *
     * <p>The \\u sequence converts only the next character to uppercase.
     * Unlike \U, this doesn't require a closing \E and affects only one character.</p>
     */
    private void handleUppercaseNext() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("u", segments.size(), true));
    }

    /**
     * Handles the \l escape sequence to lowercase the next character.
     *
     * <p>The \l sequence converts only the next character to lowercase.
     * Unlike \L, this doesn't require a closing \E and affects only one character.</p>
     */
    private void handleLowercaseNext() {
        flushCurrentSegment();
        activeCaseModifiers.push(new CaseModifier("l", segments.size(), true));
    }

    /**
     * Class representing a case modification state.
     *
     * <p>This class tracks the state of an active case modification, including
     * what type of modification it is and which segments should be affected by it.
     * The segments list is populated as new segments are added while this modifier
     * is active.</p>
     */
    private static class CaseModifier {
        private final String type;
        private final int startSegment;
        private final boolean isSingleChar;
        private final List<Node> segmentsUnderModifier;
        private boolean hasAffectedContent;

        /**
         * Constructor for CaseModifier.
         *
         * @param type the type of case modification ("U", "L", "u", "l")
         * @param startSegment the segment index where this modifier starts
         * @param isSingleChar whether this modifier affects only one character/segment
         */
        CaseModifier(String type, int startSegment, boolean isSingleChar) {
            this.type = type;
            this.startSegment = startSegment;
            this.isSingleChar = isSingleChar;
            this.segmentsUnderModifier = new ArrayList<>();
            this.hasAffectedContent = false;
        }

        public String type() {
            return type;
        }

        public int startSegment() {
            return startSegment;
        }

        public boolean isSingleChar() {
            return isSingleChar;
        }

        public List<Node> segmentsUnderModifier() {
            return segmentsUnderModifier;
        }

        public boolean hasAffectedContent() {
            return hasAffectedContent || !segmentsUnderModifier.isEmpty();
        }

        public void setHasAffectedContent(boolean hasAffectedContent) {
            this.hasAffectedContent = hasAffectedContent;
        }
    }
}

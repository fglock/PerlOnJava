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
 *   <li><strong>Variable interpolation:</strong> $var, @array, ${expr}, @{expr}</li>
 *   <li><strong>Escape sequences:</strong> \n, \t, \x{hex}, \N{unicode_name}</li>
 *   <li><strong>Case modification:</strong> \U...\E (uppercase), \L...\E (lowercase)</li>
 *   <li><strong>Single character case:</strong> \\u (next char upper), \l (next char lower)</li>
 *   <li><strong>Quote metacharacters:</strong> \Q...\E (escape regex metacharacters)</li>
 * </ul>
 *
 * <h3>Case Modification System</h3>
 * <p>The parser maintains a stack of active case modifiers. When a case modifier like \U
 * is encountered, all subsequent text and interpolated variables are wrapped in the
 * appropriate case conversion function. Modifiers can be nested, but conflicting
 * modifiers (like \L inside \U) will terminate the previous modifier.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * "Hello \U$name\E"        # uc($name)
 * "Hello \\u$name"          # ucfirst($name)
 * "\L\U$text\E\E"          # lc(uc($text))
 * "Value: \Q$special\E"    # quotemeta($special)
 * </pre>
 *
 * @see StringSegmentParser
 * @see StringParser
 */
public class StringDoubleQuoted extends StringSegmentParser {

    /**
     * Stack of active case modifiers.
     *
     * <p>Case modifiers can be nested, so we use a stack to track them.
     * When \E is encountered, we pop and apply the most recent modifier.
     * The stack allows complex nesting like \L outer \U inner \E \E.
     */
    private final Stack<CaseModifier> caseModifiers = new Stack<>();

    /**
     * Flag indicating whether escape sequences should be processed.
     *
     * <p>When true, escape sequences like \n are converted to their actual values.
     * When false (for regex contexts), escape sequences are preserved literally
     * to be processed by the regex engine.
     */
    private final boolean parseEscapes;

    /**
     * Flag indicating whether we're inside a \Q...\E quotemeta region.
     *
     * <p>When true, all special characters (including $ and @) are treated as literals,
     * and escape sequences are not processed (except \E to end the region).
     */
    private boolean inQuotemeta = false;

    /**
     * Private constructor for StringDoubleQuoted parser.
     *
     * <p>Use {@link #parseDoubleQuotedString} factory method to create instances.
     *
     * @param ctx The emitter context for error reporting
     * @param tokens The tokenized string content
     * @param parser The parser instance for complex expressions
     * @param tokenIndex The starting token position
     * @param isRegex True if parsing regex pattern (affects interpolation)
     * @param parseEscapes True to process escape sequences, false to preserve them
     */
    private StringDoubleQuoted(EmitterContext ctx, List<LexerToken> tokens, Parser parser, int tokenIndex, boolean isRegex, boolean parseEscapes, boolean interpolateVariable, boolean isRegexReplacement) {
        super(ctx, tokens, parser, tokenIndex, isRegex, interpolateVariable, isRegexReplacement);
        this.parseEscapes = parseEscapes;
    }

    /**
     * Parses a double-quoted string, handling escape sequences and variable interpolation.
     *
     * <p>This is the main entry point for parsing double-quoted strings. It handles:
     * <ul>
     *   <li>Creating a lexer for the string content</li>
     *   <li>Tokenizing the content</li>
     *   <li>Creating a parser instance</li>
     *   <li>Delegating to the StringDoubleQuoted parser</li>
     * </ul>
     *
     * @param ctx The emitter context for logging and error handling
     * @param rawStr The parsed string data containing the string content and position info
     * @param parseEscapes Whether to process escape sequences or preserve them literally
     * @return An AST node representing the parsed string (StringNode, BinaryOperatorNode for join, etc.)
     */
    static Node parseDoubleQuotedString(EmitterContext ctx, StringParser.ParsedString rawStr, boolean parseEscapes, boolean interpolateVariable, boolean isRegexReplacement) {
        // Extract the first buffer (double-quoted strings don't have multiple parts like here-docs)
        var input = rawStr.buffers.getFirst();
        var tokenIndex = rawStr.next;

        // In regex context, we preserve escapes for the regex engine
        var isRegex = !parseEscapes;
        ctx.logDebug("parseDoubleQuotedString isRegex:" + isRegex);

        // Tokenize the string content
        var lexer = new Lexer(input);
        var tokens = lexer.tokenize();
        var parser = new Parser(ctx, tokens);

        // Create and run the double-quoted string parser
        var doubleQuotedParser = new StringDoubleQuoted(ctx, tokens, parser, tokenIndex, isRegex, parseEscapes, interpolateVariable, isRegexReplacement);
        return doubleQuotedParser.parse();
    }

    /**
     * Adds a string segment and tracks it for active case modifiers.
     *
     * <p>This override ensures that all segments (both literal text and interpolated
     * variables) are tracked by active case modifiers. This allows modifiers like
     * \U to affect both literal text and variable values.
     *
     * <p>After adding a segment, we check if any single-character modifiers (\\u, \l)
     * should be deactivated, as they only affect one character.
     *
     * @param node The AST node to add as a segment
     */
    @Override
    protected void addStringSegment(Node node) {
        // Add to main segments list
        segments.add(node);

        // Track this segment in all active case modifiers
        // This allows nested modifiers to all track the same content
        for (CaseModifier modifier : caseModifiers) {
            modifier.addSegment(node);
        }

        // Check if any single-char modifiers should be deactivated
        // This happens after they've affected at least one character
        checkSingleCharModifiers();
    }

    /**
     * Override to handle literal text appending with case modifier tracking.
     *
     * <p>When literal text is appended, we need to track whether single-character
     * modifiers (\\u, \l) have affected any content. Once they have, they should
     * be deactivated after the current segment is complete.
     *
     * @param text The literal text to append
     */
    @Override
    protected void appendToCurrentSegment(String text) {
        super.appendToCurrentSegment(text);

        // Mark single-char modifiers as having affected content
        // This is important because \\u and \l only affect the next character
        if (!text.isEmpty() && !caseModifiers.isEmpty() && caseModifiers.peek().isSingleChar) {
            caseModifiers.peek().hasAffectedContent = true;
        }
    }

    /**
     * Parses the string and applies any remaining case modifications.
     *
     * <p>This override ensures that any unclosed case modifiers (missing \E)
     * are still applied to their content. This matches Perl's behavior where
     * a missing \E is implicitly added at the end of the string.
     *
     * @return The final AST node representing the parsed string
     */
    @Override
    public Node parse() {
        // Parse the string content using the base class
        var result = super.parse();

        // Apply any unclosed case modifications
        // This handles cases like "text \U more text" without \E
        while (!caseModifiers.isEmpty()) {
            applyCaseModifier(caseModifiers.pop());
        }

        return createJoinNode(segments);
    }

    /**
     * Checks and deactivates single-character modifiers after they've affected content.
     *
     * <p>Single-character modifiers (\\u and \l) only affect the next character.
     * Once they've modified something, they should be removed from the stack
     * and their accumulated content should be wrapped in the appropriate function.
     */
    private void checkSingleCharModifiers() {
        // Process all single-char modifiers that have affected content
        while (!caseModifiers.isEmpty() &&
                caseModifiers.peek().isSingleChar &&
                caseModifiers.peek().hasAffectedContent) {
            applyCaseModifier(caseModifiers.pop());
        }
    }

    /**
     * Applies a case modification to its associated segments.
     *
     * <p>This method:
     * <ol>
     *   <li>Determines the appropriate Perl function for the modifier</li>
     *   <li>Creates a joined node from all segments affected by the modifier</li>
     *   <li>Wraps the content in the case function (uc, lc, ucfirst, lcfirst, quotemeta)</li>
     *   <li>Replaces the original segments with the case-modified node</li>
     *   <li>Updates parent modifiers to reference the new node</li>
     * </ol>
     *
     * @param modifier The case modifier to apply
     */
    private void applyCaseModifier(CaseModifier modifier) {
        if (modifier.segments.isEmpty()) {
            return;
        }

        // Map modifier type to Perl function name
        String operator = switch (modifier.type) {
            case "U" -> "uc";       // \U - uppercase
            case "L" -> "lc";       // \L - lowercase
            case "u" -> "ucfirst";  // \\u - uppercase first
            case "l" -> "lcfirst";  // \l - lowercase first
            case "Q" -> "quotemeta"; // \Q - quote metacharacters
            default -> null;
        };

        if (operator == null) {
            return;
        }

        // Create case-modified node
        var contentNode = createJoinNode(modifier.segments);
        var caseModifiedNode = new OperatorNode(operator, contentNode, parser.tokenIndex);

        // Replace segments with case-modified node
        int firstIndex = segments.indexOf(modifier.segments.getFirst());
        if (firstIndex >= 0) {
            // Remove all segments of this modifier
            segments.removeAll(modifier.segments);
            // Insert the case-modified node at the original position
            segments.add(firstIndex, caseModifiedNode);

            // Update parent modifiers to reference the new node instead of the old segments
            // This maintains proper nesting when modifiers are nested
            for (CaseModifier parent : caseModifiers) {
                if (parent.segments.removeAll(modifier.segments)) {
                    parent.segments.add(caseModifiedNode);
                }
            }
        }
    }

    /**
     * Creates a join node for multiple segments or returns single segment.
     *
     * <p>This utility method handles the common pattern of joining string segments:
     * <ul>
     *   <li>Empty list: returns empty string node</li>
     *   <li>Single segment: returns it directly (no join needed)</li>
     *   <li>Multiple segments: creates join("", segment1, segment2, ...)</li>
     * </ul>
     *
     * @param nodes The list of nodes to join
     * @return A single node representing the joined content
     */
    private Node createJoinNode(List<Node> nodes) {
        return switch (nodes.size()) {
            case 0 -> new StringNode("", parser.tokenIndex);
            case 1 -> nodes.getFirst();
            default -> {
                var listNode = new ListNode(parser.tokenIndex);
                listNode.elements.addAll(nodes);
                yield new BinaryOperatorNode("join", new StringNode("", parser.tokenIndex), listNode, parser.tokenIndex);
            }
        };
    }

    /**
     * Parses escape sequences based on context.
     *
     * <p>This method delegates to different escape handling based on the
     * parseEscapes flag and quotemeta mode:
     * <ul>
     *   <li>inQuotemeta=true: Only \E is special, everything else is literal</li>
     *   <li>parseEscapes=true: Process escapes like \n to actual newline</li>
     *   <li>parseEscapes=false: Preserve escapes for regex engine</li>
     * </ul>
     */
    @Override
    protected void parseEscapeSequence() {
        if (inQuotemeta) {
            // In quotemeta mode, everything is literal except \E
            var token = tokens.get(parser.tokenIndex);
            if (token.text.startsWith("E")) {
                // End quotemeta mode
                TokenUtils.consumeChar(parser);
                flushCurrentSegment();
                if (!caseModifiers.isEmpty() && caseModifiers.peek().type.equals("Q")) {
                    applyCaseModifier(caseModifiers.pop());
                }
                inQuotemeta = false;
            } else {
                // Everything else is literal, including the backslash
                currentSegment.append("\\");
            }
            return;
        }

        if (parseEscapes) {
            parseDoubleQuotedEscapes();
        } else {
            parseDoubleQuotedEscapesRegex();
        }
    }

    private void parseDoubleQuotedEscapesRegex() {
        // In regex context, preserve almost all escape sequences literally
        // The regex engine will process them

        // Consume the character after the backslash
        var escape = TokenUtils.consumeChar(parser);

        switch (escape) {
            // Case modification end marker
            case "E" -> {
                // Flush any pending literal text
                flushCurrentSegment();
                // Pop and apply the most recent case modifier
                if (!caseModifiers.isEmpty()) {
                    applyCaseModifier(caseModifiers.pop());
                }
            }

            // Case modifiers
            case "U" -> startCaseModifier("U", false);  // Uppercase until \E
            case "L" -> startCaseModifier("L", false);  // Lowercase until \E
            case "u" -> startCaseModifier("u", true);   // Uppercase next char
            case "l" -> startCaseModifier("l", true);   // Lowercase next char

            // Quotemeta modifier
            case "Q" -> {
                flushCurrentSegment();
                inQuotemeta = true;
                caseModifiers.push(new CaseModifier("Q", false));
            }

            // Unknown escape - treat as literal character
            default -> appendToCurrentSegment("\\" + escape);
        }
    }

    /**
     * Processes escape sequences for double-quoted strings.
     *
     * <p>This method handles all escape sequences valid in double-quoted strings:
     * <ul>
     *   <li>Standard escapes: \n, \t, \r, etc.</li>
     *   <li>Literal escapes: \\, \"</li>
     *   <li>Octal escapes: \123</li>
     *   <li>Hex escapes: \x41, \x{263A}</li>
     *   <li>Control chars: \cA</li>
     *   <li>Unicode names: \N{LATIN SMALL LETTER A}</li>
     *   <li>Case modifiers: \U, \L, \\u, \l, \E</li>
     *   <li>Quotemeta: \Q...\E</li>
     * </ul>
     */
    private void parseDoubleQuotedEscapes() {
        var token = tokens.get(parser.tokenIndex);

        // Handle octal escapes (\123)
        // Octal escapes start with a digit 0-7
        if (token.type == LexerTokenType.NUMBER) {
            var octalStr = new StringBuilder(TokenUtils.consumeChar(parser));
            var chr = TokenUtils.peekChar(parser);
            // Collect up to 3 octal digits
            while (octalStr.length() < 3 && chr.compareTo("0") >= 0 && chr.compareTo("7") <= 0) {
                octalStr.append(TokenUtils.consumeChar(parser));
                chr = TokenUtils.peekChar(parser);
            }
            // Convert octal to character
            appendToCurrentSegment(String.valueOf((char) Integer.parseInt(octalStr.toString(), 8)));
            return;
        }

        // Consume the character after the backslash
        var escape = TokenUtils.consumeChar(parser);

        switch (escape) {
            // Standard escapes - convert to actual characters
            case "\\" -> appendToCurrentSegment("\\");
            case "\"" -> appendToCurrentSegment("\"");
            case "n" -> appendToCurrentSegment("\n");
            case "t" -> appendToCurrentSegment("\t");
            case "r" -> appendToCurrentSegment("\r");
            case "f" -> appendToCurrentSegment("\f");
            case "b" -> appendToCurrentSegment("\b");
            case "a" -> appendToCurrentSegment(String.valueOf((char) 7));  // ASCII bell
            case "e" -> appendToCurrentSegment(String.valueOf((char) 27)); // ASCII escape
            case "$" -> {
                if (isRegexReplacement) {
                    appendToCurrentSegment("\\$");
                } else {
                    appendToCurrentSegment("$");
                }
            }

            // Control character: \cX
            case "c" -> handleControlCharacter();

            // Case modification end marker
            case "E" -> {
                // Flush any pending literal text
                flushCurrentSegment();
                // Pop and apply the most recent case modifier
                if (!caseModifiers.isEmpty()) {
                    applyCaseModifier(caseModifiers.pop());
                }
            }

            // Case modifiers
            case "U" -> startCaseModifier("U", false);  // Uppercase until \E
            case "L" -> startCaseModifier("L", false);  // Lowercase until \E
            case "u" -> startCaseModifier("u", true);   // Uppercase next char
            case "l" -> startCaseModifier("l", true);   // Lowercase next char

            // Quotemeta modifier
            case "Q" -> {
                flushCurrentSegment();
                inQuotemeta = true;
                caseModifiers.push(new CaseModifier("Q", false));
            }

            // Other escape sequences
            case "x" -> handleHexEscape();           // \x41 or \x{263A}
            case "o" -> handleOctalEscape();         // \o{100}
            case "N" -> handleUnicodeNameEscape();   // \N{UNICODE NAME}

            // Unknown escape - treat as literal character
            default -> appendToCurrentSegment(escape);
        }
    }

    /**
     * Starts a new case modifier.
     *
     * <p>This method handles the complex interaction between case modifiers:
     * <ul>
     *   <li>Flushes pending literal text before starting modifier</li>
     *   <li>Handles conflicts between \L and \U (they cancel each other)</li>
     *   <li>Pushes new modifier onto the stack</li>
     *   <li>Validates that conflicting modifiers have content between them</li>
     * </ul>
     *
     * @param type The modifier type ("U", "L", "u", or "l")
     * @param isSingleChar True for \\u and \l (affect only next character)
     */
    private void startCaseModifier(String type, boolean isSingleChar) {
        // Flush any pending literal text
        flushCurrentSegment();

        // Handle conflicting modifiers
        // \L and \U cancel each other out when they meet
        if (!caseModifiers.isEmpty()) {
            var top = caseModifiers.peek();
            if ((top.type.equals("L") && type.equals("U")) ||
                    (top.type.equals("U") && type.equals("L"))) {
                // Check if there's no content between the modifiers
                if (top.segments.isEmpty() && currentSegment.isEmpty()) {
                    // Perl doesn't allow \L\U or \U\L with no content between
                    throw new RuntimeException("syntax error: \\" + top.type + "\\" + type + " is not allowed");
                }
                // Apply the previous modifier before starting the new one
                applyCaseModifier(caseModifiers.pop());
            }
        }

        // Push the new modifier onto the stack
        caseModifiers.push(new CaseModifier(type, isSingleChar));
    }

    /**
     * Simple case modifier tracking class.
     *
     * <p>This class tracks:
     * <ul>
     *   <li>The type of modifier (U, L, u, l, Q)</li>
     *   <li>Whether it's single-character (u, l) or range-based (U, L, Q)</li>
     *   <li>All segments affected by this modifier</li>
     *   <li>Whether single-char modifiers have affected any content</li>
     * </ul>
     */
    private static class CaseModifier {
        /** The modifier type: "U", "L", "u", "l", or "Q" */
        final String type;

        /** True for \\u and \l (single character), false for \U, \L, and \Q (ranges) */
        final boolean isSingleChar;

        /** List of segments affected by this modifier */
        final List<Node> segments = new ArrayList<>();

        /** For single-char modifiers, tracks if they've modified anything yet */
        boolean hasAffectedContent = false;

        /**
         * Creates a new case modifier.
         *
         * @param type The modifier type
         * @param isSingleChar Whether this is a single-character modifier
         */
        CaseModifier(String type, boolean isSingleChar) {
            this.type = type;
            this.isSingleChar = isSingleChar;
        }

        /**
         * Adds a segment to this modifier's scope.
         *
         * @param node The segment to track
         */
        void addSegment(Node node) {
            segments.add(node);
            // Single-char modifiers are immediately marked as having affected content
            if (isSingleChar) {
                hasAffectedContent = true;
            }
        }
    }
}

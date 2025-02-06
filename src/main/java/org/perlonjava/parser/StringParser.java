package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.Lexer;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.ScalarUtils.printable;

/*
 * StringParser is used to parse domain-specific languages within Perl, such as Regex and string interpolation.
 *
 * Perl has a complex quoting mechanism for strings, which cannot be fully implemented in the Lexer
 * due to insufficient context.
 * This module reprocesses the tokens according to the current context to extract the quoted string.
 * The string is then passed on to the respective domain-specific compilers.
 */
public class StringParser {

    // States for the finite state machine (FSM)
    private static final int START = 0;
    private static final int STRING = 1;
    private static final int ESCAPE = 2;
    private static final int END_TOKEN = 3;

    // Map to hold pairs of matching delimiters
    private static final Map<Character, Character> QUOTE_PAIR = Map.of(
            '<', '>',
            '{', '}',
            '(', ')',
            '[', ']'
    );

    /**
     * Parses a raw string with delimiters from a list of tokens.
     *
     * @param tokens List of lexer tokens.
     * @param index  Starting index in the tokens list.
     * @param redo   Flag to indicate if the parsing should be redone; example:  s/.../.../
     * @return ParsedString object containing the parsed string and updated token index.
     */
    public static ParsedString parseRawStringWithDelimiter(EmitterContext ctx, List<LexerToken> tokens, int index, boolean redo) {
        int tokPos = index;  // Current position in the tokens list
        char startDelim = 0;  // Starting delimiter
        char endDelim = 0;  // Ending delimiter
        int state = START;  // Initial state of the FSM
        int parenLevel = 0;  // Parenthesis nesting level
        boolean isPair = false;  // Flag to indicate if the delimiters are a pair
        StringBuilder buffer = new StringBuilder();  // Buffer to hold the parsed string
        StringBuilder remain = new StringBuilder();  // Buffer to hold the remaining string
        ArrayList<String> buffers = new ArrayList<>();

        while (state != END_TOKEN) {
            if (tokens.get(tokPos).type == LexerTokenType.EOF) {
                throw new PerlCompilerException(tokPos, "Can't find string terminator " + endDelim + " anywhere before EOF", ctx.errorUtil);
            }

            for (char ch : tokens.get(tokPos).text.toCharArray()) {
                switch (state) {
                    case START:
                        startDelim = ch;
                        endDelim = startDelim;
                        if (QUOTE_PAIR.containsKey(startDelim)) {  // Check if the delimiter is a pair
                            isPair = true;
                            endDelim = QUOTE_PAIR.get(startDelim);
                        }
                        state = STRING;  // Move to STRING state
                        break;

                    case STRING:
                        if (isPair && ch == startDelim) {
                            parenLevel++;  // Increase nesting level for starting delimiter
                        } else if (ch == endDelim) {
                            if (parenLevel == 0) {
                                if (redo && !isPair) {
                                    redo = false;
                                    // Restart FSM for another string
                                    buffers.add(buffer.toString());
                                    buffer = new StringBuilder();
                                    break;  // Exit the loop to restart FSM
                                } else {
                                    state = END_TOKEN;  // End parsing
                                }
                                continue;  // Skip the rest of the loop
                            }
                            parenLevel--;  // Decrease nesting level for ending delimiter
                        } else if (ch == '\\') {
                            state = ESCAPE;  // Move to ESCAPE state
                        }
                        buffer.append(ch);  // Append character to buffer
                        break;

                    case ESCAPE:
                        buffer.append(ch);  // Append escaped character to buffer
                        state = STRING;  // Return to STRING state
                        break;

                    case END_TOKEN:
                        remain.append(ch);  // Append remaining characters to remain buffer
                        break;
                }
            }
            tokPos++;  // Move to the next token
        }
        buffers.add(buffer.toString());
        if (!remain.isEmpty()) {
            tokPos--;
            tokens.get(tokPos).text = remain.toString();  // Put the remaining string back in the tokens list
        }
        return new ParsedString(index, tokPos, buffers, startDelim, endDelim, ' ', ' ');
    }

    public static ParsedString parseRawStrings(Parser parser, EmitterContext ctx, List<LexerToken> tokens, int tokenIndex, int stringCount) {
        int pos = tokenIndex;
        boolean redo = (stringCount == 3);
        ParsedString ast = parseRawStringWithDelimiter(ctx, tokens, pos, redo); // use redo flag to extract 2 strings
        if (stringCount == 1) {
            return ast;
        }
        pos = ast.next;

        if (stringCount == 3) { // fetch the second of 3 strings: s{aaa}{SECOND}ig
            char delim = ast.startDelim; // / or {
            if (QUOTE_PAIR.containsKey(delim)) {
                pos = Whitespace.skipWhitespace(parser, pos, tokens);
                ParsedString ast2 = parseRawStringWithDelimiter(ctx, tokens, pos, false);
                ast.buffers.add(ast2.buffers.getFirst());
                ast.next = ast2.next;
                ast.secondBufferStartDelim = ast2.startDelim;
                ast.secondBufferEndDelim = ast2.endDelim;
                pos = ast.next;
            }
        }

        // fetch the last string: s/aaa/bbb/LAST
        String modifier = "";
        if (tokens.get(pos).type == LexerTokenType.IDENTIFIER) {
            modifier = tokens.get(pos).text;
            ast.next = pos + 1;
        }
        ArrayList<String> buffers = ast.buffers;
        if (buffers == null) {
            buffers = new ArrayList<>();
            ast.buffers = buffers;
        }
        buffers.add(modifier);
        return ast;
    }

    static Node parseRegexString(EmitterContext ctx, ParsedString rawStr) {
        Node parsed;
        if (rawStr.startDelim == '\'') {
            // single quote delimiter, use the string as-is
            parsed = new StringNode(rawStr.buffers.getFirst(), rawStr.index);
        } else {
            // interpolate variables, but ignore the escapes
            parsed = StringDoubleQuoted.parseDoubleQuotedString(ctx, rawStr, false);
        }
        return parsed;
    }

    public static ListNode parseWordsString(ParsedString rawStr) {
        // Use a regular expression to split the string.
        // " +" matches one or more ASCII space characters
        String[] words = rawStr.buffers.getFirst().trim().split("[ \t\n]+");
        ListNode list = new ListNode(rawStr.index);
        for (String word : words) {
            list.elements.add(new StringNode(word, rawStr.index));
        }
        return list;
    }

    public static OperatorNode parseRegexReplace(EmitterContext ctx, ParsedString rawStr) {
        String operator = "replaceRegex";
        Node parsed = parseRegexString(ctx, rawStr);
        String replaceStr = rawStr.buffers.get(1);
        String modifierStr = rawStr.buffers.get(2);

        Node replace;
        if (modifierStr.contains("e")) {
            // if modifiers include `e`, then parse the `replace` code
            ctx.logDebug("regex e-modifier: " + replaceStr);
            Parser blockParser = new Parser(ctx, new Lexer(replaceStr).tokenize());
            replace = ParseBlock.parseBlock(blockParser);
        } else if (rawStr.secondBufferStartDelim != '\'') {
            // handle string interpolaton
            rawStr.buffers.removeFirst();   // consume the first buffer
            replace = StringDoubleQuoted.parseDoubleQuotedString(ctx, rawStr, true);
        } else {
            // handle single quoted string
            rawStr.buffers.removeFirst();   // consume the first buffer
            replace = StringSingleQuoted.parseSingleQuotedString(rawStr);
        }

        if (modifierStr.contains("ee")) {
            replace = new OperatorNode("eval", new ListNode(List.of(replace), rawStr.index), rawStr.index);
        }

        // If replace is not a plain string, make it an anonymous subroutine
        if (!(replace instanceof StringNode)) {
            if (!(replace instanceof BlockNode)) {
                List<Node> list = new ArrayList<>();
                list.add(replace);
                replace = new BlockNode(list, rawStr.index);
            }
            replace = new SubroutineNode(null, null, null, replace, false, rawStr.index);
        }

        Node modifiers = new StringNode(modifierStr, rawStr.index);
        List<Node> elements = new ArrayList<>();
        elements.add(parsed);
        elements.add(replace);
        elements.add(modifiers);
        ListNode list = new ListNode(elements, rawStr.index);
        return new OperatorNode(operator, list, rawStr.index);
    }

    public static OperatorNode parseRegexMatch(EmitterContext ctx, String operator, ParsedString rawStr) {
        operator = operator.equals("qr") ? "quoteRegex" : "matchRegex";
        Node parsed = parseRegexString(ctx, rawStr);
        String modStr = rawStr.buffers.get(1);
        if (rawStr.startDelim == '?') {
            // `m?PAT?` matches exactly once
            // save the internal flag in the modifier string
            modStr += '?';
        }
        Node modifiers = new StringNode(modStr, rawStr.index);
        List<Node> elements = new ArrayList<>();
        elements.add(parsed);
        elements.add(modifiers);
        ListNode list = new ListNode(elements, rawStr.index);
        return new OperatorNode(operator, list, rawStr.index);
    }

    public static OperatorNode parseSystemCommand(EmitterContext ctx, String operator, ParsedString rawStr) {
        operator = "qx";
        // TODO when to interpolate variables?
        Node parsed = new StringNode(rawStr.buffers.getFirst(), rawStr.index);
        List<Node> elements = new ArrayList<>();
        elements.add(parsed);
        ListNode list = new ListNode(elements, rawStr.index);
        return new OperatorNode(operator, list, rawStr.index);
    }

    public static Node parseRawString(Parser parser, String operator) {
        // handle special quotes for operators: q qq qx qw // s/// m//
        if (operator.equals("<") || operator.equals("<<") || operator.equals("'") || operator.equals("\"") || operator.equals("/") || operator.equals("//") || operator.equals("/=")
                || operator.equals("`")) {
            parser.tokenIndex--;   // will reparse the quote
            if (operator.equals("<") || operator.equals("<<")) {
                operator = "<>";
            }
        }
        ParsedString rawStr;
        int stringParts = switch (operator) {
            case "s", "tr", "y" -> 3;    // s{str}{str}modifier
            case "m", "qr", "/", "//", "/=" -> 2;
            default -> 1;    // m{str}modifier
        };
        rawStr = parseRawStrings(parser, parser.ctx, parser.tokens, parser.tokenIndex, stringParts);
        parser.tokenIndex = rawStr.next;

        switch (operator) {
            case "`":
            case "qx":
                return parseSystemCommand(parser.ctx, operator, rawStr);
            case "'":
            case "q":
                return StringSingleQuoted.parseSingleQuotedString(rawStr);
            case "m":
            case "qr":
            case "/":
            case "//":
            case "/=":
                return parseRegexMatch(parser.ctx, operator, rawStr);
            case "s":
                return parseRegexReplace(parser.ctx, rawStr);
            case "\"":
            case "qq":
                return StringDoubleQuoted.parseDoubleQuotedString(parser.ctx, rawStr, true);
            case "qw":
                return parseWordsString(rawStr);
        }

        ListNode list = new ListNode(rawStr.index);
        int size = rawStr.buffers.size();
        for (int i = 0; i < size; i++) {
            list.elements.add(new StringNode(rawStr.buffers.get(i), rawStr.index));
        }
        return new OperatorNode(operator, list, rawStr.index);
    }

    static StringNode parseVstring(Parser parser, String vStringPart, int currentIndex) {
        // Start constructing the v-string
        StringBuilder vStringBuilder = new StringBuilder();

        if (vStringPart.startsWith("v")) {
            vStringPart = vStringPart.substring(1);
        }
        try {
            // Convert the initial part to a character and append it
            int charCode = Integer.parseInt(vStringPart);
            vStringBuilder.append((char) charCode);
        } catch (NumberFormatException e) {
            throw new PerlCompilerException(currentIndex, "Invalid v-string format: " + vStringPart, parser.ctx.errorUtil);
        }

        // Continue parsing while the next token is a dot followed immediately by a number
        while (true) {
            // Get the next immediate token without skipping whitespace
            LexerToken nextToken = parser.tokens.get(parser.tokenIndex);

            // Check if the next token is a dot
            if (nextToken.text.equals(".")) {
                // Get the token immediately following the dot
                LexerToken numberToken = parser.tokens.get(parser.tokenIndex + 1);

                // Ensure the token after the dot is a number
                if (numberToken.type == LexerTokenType.NUMBER) {
                    // Consume the dot
                    TokenUtils.consume(parser);
                    // Consume the number, convert it to a character, and append it
                    String num = TokenUtils.consume(parser).text.replace("_", "");
                    int charCode = Integer.parseInt(num);
                    vStringBuilder.append((char) charCode);
                } else {
                    break; // Exit the loop if the next token is not a number
                }
            } else {
                break; // Exit the loop if the next token is not a dot
            }
        }

        parser.ctx.logDebug("v-string: <" + printable(vStringBuilder.toString()) + "> next:" + TokenUtils.peek(parser));

        // Create a StringNode with the constructed v-string
        return new StringNode(vStringBuilder.toString(), true, currentIndex);
    }

    /**
     * Class to represent the parsed string and its position in the tokens list.
     */
    public static class ParsedString {
        public int index;  // Starting index of the parsed string
        public int next;  // Next index in the tokens list
        public ArrayList<String> buffers;  // Parsed string
        public char startDelim;
        public char endDelim;
        public char secondBufferStartDelim;  // Start delimiter of the second buffer
        public char secondBufferEndDelim;    // End delimiter of the second buffer

        public ParsedString(int index, int next, ArrayList<String> buffers, char startDelim, char endDelim, char secondBufferStartDelim, char secondBufferEndDelim) {
            this.index = index;
            this.next = next;
            this.buffers = buffers;
            this.startDelim = startDelim;
            this.endDelim = endDelim;
            this.secondBufferStartDelim = secondBufferStartDelim;
            this.secondBufferEndDelim = secondBufferEndDelim;
        }

        @Override
        public String toString() {
            return "ParsedString{" +
                    "index=" + index +
                    ", next=" + next +
                    ", buffers=" + buffers +
                    ", startDelim=" + startDelim +
                    ", endDelim=" + endDelim +
                    ", secondBufferStartDelim=" + secondBufferStartDelim +
                    ", secondBufferEndDelim=" + secondBufferEndDelim +
                    '}';
        }
    }
}


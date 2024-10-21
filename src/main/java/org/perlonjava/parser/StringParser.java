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
        return new ParsedString(index, tokPos, buffers, startDelim, endDelim);
    }

    public static ParsedString parseRawStrings(EmitterContext ctx, List<LexerToken> tokens, int tokenIndex, int stringCount) {
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
                pos = Whitespace.skipWhitespace(pos, tokens);
                ParsedString ast2 = parseRawStringWithDelimiter(ctx, tokens, pos, false);
                ast.buffers.add(ast2.buffers.getFirst());
                ast.next = ast2.next;
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
            parsed = parseDoubleQuotedString(ctx, rawStr, false);
        }
        return parsed;
    }

    static Node parseDoubleQuotedString(EmitterContext ctx, ParsedString rawStr, boolean parseEscapes) {
        String input = rawStr.buffers.getFirst();
        int tokenIndex = rawStr.next;

        StringBuilder str = new StringBuilder();  // Buffer to hold the parsed string
        List<Node> parts = new ArrayList<>();  // List to hold parts of the parsed string

        Lexer lexer = new Lexer(input);
        List<LexerToken> tokens = lexer.tokenize();
        Parser parser = new Parser(ctx, tokens);

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
                        parseDoubleQuotedEscapes(ctx, tokens, parser, str, tokenIndex);
                    } else {
                        // consume the escaped character without processing
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
                    if (tokens.get(parser.tokenIndex).type == LexerTokenType.EOF) {
                        // final $ or @
                        str.append(text);
                        break;
                    }
                    if (!str.isEmpty()) {
                        parts.add(new StringNode(str.toString(), tokenIndex));  // Add the string so far to parts
                        str = new StringBuilder();  // Reset the buffer
                    }
                    String sigil = text;
                    ctx.logDebug("str sigil");
                    Node operand;
                    boolean isArray = sigil.equals("@");
                    if (TokenUtils.peek(parser).text.equals("{")) {
                        // block-like
                        // extract the string between brackets
                        StringParser.ParsedString rawStr2 = StringParser.parseRawStrings(ctx, parser.tokens, parser.tokenIndex, 1);
                        String blockStr = rawStr2.buffers.getFirst();
                        ctx.logDebug("str block-like: " + blockStr);
                        blockStr = sigil + "{" + blockStr + "}";
                        Parser blockParser = new Parser(ctx, new Lexer(blockStr).tokenize());
                        operand = blockParser.parseBlock();
                        parser.tokenIndex = rawStr2.next;
                        ctx.logDebug("str operand " + operand);
                    } else {
                        String identifier = IdentifierParser.parseComplexIdentifier(parser);
                        if (identifier == null) {
                            // parse $$$a  @$$a
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
                                case "{":
                                    operand = parser.parseInfixOperation(operand, 0);
                                    ctx.logDebug("str operand " + operand);
                                    break;
                                case "->":
                                    int previousIndex = parser.tokenIndex;
                                    parser.tokenIndex++;
                                    text = tokens.get(parser.tokenIndex).text;
                                    switch (text) {
                                        case "[":
                                        case "{":
                                            parser.tokenIndex = previousIndex;  // re-parse "->"
                                            operand = parser.parseInfixOperation(operand, 0);
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
                    parts.add(operand);
                    break;
                default:
                    str.append(text);
            }
        }

        if (!str.isEmpty()) {
            parts.add(new StringNode(str.toString(), tokenIndex));  // Add the remaining string to parts
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

    private static void parseDoubleQuotedEscapes(EmitterContext ctx, List<LexerToken> tokens, Parser parser, StringBuilder str, int tokenIndex) {
        LexerToken token;
        String text;
        String escape;
        token = tokens.get(parser.tokenIndex);
        if (token.type == LexerTokenType.NUMBER) {
            //  octal like `\200`
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
                    //  \cA is control-A char(1)
                    char chr = ctl.charAt(0);
                    if (chr >= 'a' && chr <= 'z') {
                        str.append((char) (chr - 'a' + 1));
                    } else {
                        str.append((char) (chr - 'A' + 1));
                    }
                }
                break;
            case "E":
                break;  // Marks the end of \Q sequence
            case "Q":
                // \Q quotemeta: Start an inner loop to handle the quoted section
                while (true) {
                    token = tokens.get(parser.tokenIndex++);
                    LexerToken nextToken = tokens.get(parser.tokenIndex);
                    if (token.type == LexerTokenType.EOF) {
                        break;
                    }
                    if (token.text.equals("\\") && nextToken.text.startsWith("E")) {
                        parser.tokenIndex--;
                        break;
                    }
                    if (token.type == LexerTokenType.IDENTIFIER || token.type == LexerTokenType.NUMBER) {
                        str.append(token.text);
                    } else {
                        for (char c : token.text.toCharArray()) {
                            str.append("\\").append(c);
                        }
                    }
                }
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
            default:
                str.append(escape);  // Append the backslash and the next character
                break;
        }
    }

    static Node parseSingleQuotedString(StringParser.ParsedString rawStr) {
        String input = rawStr.buffers.getFirst();
        char startDelim = rawStr.startDelim;
        char endDelim = rawStr.endDelim;
        int tokenIndex = rawStr.index;

        StringBuilder str = new StringBuilder();  // Buffer to hold the parsed string
        char[] chars = input.toCharArray();  // Convert the input string to a character array
        int length = chars.length;  // Length of the character array
        int index = 0;  // Current position in the character array

        // Loop through the character array until the end
        while (index < length) {
            char ch = chars[index];  // Get the current character
            if (ch == '\\') {
                index++;  // Move to the next character
                if (index < length) {
                    char nextChar = chars[index];  // Get the next character
                    if (nextChar == '\\' || nextChar == startDelim || nextChar == endDelim) {
                        str.append(nextChar);  // Append the escaped character
                    } else {
                        str.append('\\').append(nextChar);  // Append the backslash and the next character
                    }
                }
            } else {
                str.append(ch);  // Append the current character
            }
            index++;  // Move to the next character
        }

        // Return a new StringNode with the parsed string and the token index
        return new StringNode(str.toString(), tokenIndex);
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
            replace = blockParser.parseBlock();
        } else {
            // handle string interpolaton
            rawStr.buffers.removeFirst();   // shift replace to first position
            replace = parseDoubleQuotedString(ctx, rawStr, false);
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
        if (operator.equals("<") || operator.equals("<<") || operator.equals("'") || operator.equals("\"") || operator.equals("/") || operator.equals("//")
                || operator.equals("`")) {
            parser.tokenIndex--;   // will reparse the quote
            if (operator.equals("<") || operator.equals("<<")) {
                operator = "<>";
            }
        }
        ParsedString rawStr;
        int stringParts = switch (operator) {
            case "s", "tr", "y" -> 3;    // s{str}{str}modifier
            case "m", "qr", "/", "//" -> 2;
            default -> 1;    // m{str}modifier
        };
        rawStr = parseRawStrings(parser.ctx, parser.tokens, parser.tokenIndex, stringParts);
        parser.tokenIndex = rawStr.next;

        switch (operator) {
            case "`":
            case "qx":
                return parseSystemCommand(parser.ctx, operator, rawStr);
            case "'":
            case "q":
                return parseSingleQuotedString(rawStr);
            case "m":
            case "qr":
            case "/":
            case "//":
                return parseRegexMatch(parser.ctx, operator, rawStr);
            case "s":
                return parseRegexReplace(parser.ctx, rawStr);
            case "\"":
            case "qq":
                return parseDoubleQuotedString(parser.ctx, rawStr, true);
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

    /**
     * Class to represent the parsed string and its position in the tokens list.
     */
    public static class ParsedString {
        public int index;  // Starting index of the parsed string
        public int next;  // Next index in the tokens list
        public ArrayList<String> buffers;  // Parsed string
        public char startDelim;
        public char endDelim;

        public ParsedString(int index, int next, ArrayList<String> buffers, char startDelim, char endDelim) {
            this.index = index;
            this.next = next;
            this.buffers = buffers;
            this.startDelim = startDelim;
            this.endDelim = endDelim;
        }
    }
}


package org.perlonjava.frontend.parser;

import org.perlonjava.app.cli.CompilerOptions;

import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.lexer.Lexer;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.perlmodule.Strict.HINT_UTF8;
import static org.perlonjava.runtime.perlmodule.Strict.HINT_RE_ASCII;
import static org.perlonjava.runtime.perlmodule.Strict.HINT_RE_UNICODE;
import static org.perlonjava.runtime.runtimetypes.ScalarUtils.printable;

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
    public static ParsedString parseRawStringWithDelimiter(EmitterContext ctx, List<LexerToken> tokens, int index, boolean redo, Parser parser) {
        return parseRawStringWithDelimiter(ctx, tokens, index, redo, parser, false);
    }

    public static ParsedString parseRawStringWithDelimiter(EmitterContext ctx, List<LexerToken> tokens, int index, boolean redo, Parser parser, boolean isRegex) {
        int tokPos = index;  // Current position in the tokens list
        char startDelim = 0;  // Starting delimiter
        char endDelim = 0;  // Ending delimiter
        int state = START;  // Initial state of the FSM
        int parenLevel = 0;  // Parenthesis nesting level
        boolean isPair = false;  // Flag to indicate if the delimiters are a pair
        StringBuilder buffer = new StringBuilder();  // Buffer to hold the parsed string
        StringBuilder remain = new StringBuilder();  // Buffer to hold the remaining string
        ArrayList<String> buffers = new ArrayList<>();

        // Track token positions for heredoc processing
        int startTokPos = tokPos;
        StringBuilder pendingBuffer = new StringBuilder();  // Buffer for content pending heredoc check

        while (state != END_TOKEN) {
            LexerToken currentToken = tokens.get(tokPos);
            if (currentToken.type == LexerTokenType.EOF) {
                String errorMsg = endDelim == '/'
                        ? "Search pattern not terminated"
                        : "Can't find string terminator " + endDelim + " anywhere before EOF";
                throw new PerlCompilerException(tokPos, errorMsg, ctx.errorUtil);
            }

            // Process heredocs at newlines during string parsing
            if (currentToken.type == LexerTokenType.NEWLINE) {
                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("parseRawStringWithDelimiter: Found NEWLINE at tokPos=" + tokPos +
                        ", parser=" + (parser != null) +
                        ", heredocCount=" + (parser != null ? parser.getHeredocNodes().size() : 0));

                if (parser != null && !parser.getHeredocNodes().isEmpty()) {
                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("parseRawStringWithDelimiter: Processing heredocs");

                    // Save the current parser position
                    int savedIndex = parser.tokenIndex;
                    int beforeHeredocTokPos = tokPos;
                    parser.tokenIndex = tokPos;

                    // Process pending heredocs
                    ParseHeredoc.parseHeredocAfterNewline(parser);

                    // Calculate how many tokens were consumed by heredoc processing
                    int afterHeredocTokPos = parser.tokenIndex;
                    int tokensConsumed = afterHeredocTokPos - beforeHeredocTokPos;

                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("parseRawStringWithDelimiter: Heredoc consumed " + tokensConsumed + " tokens");

                    // If heredoc consumed more than just the newline, we need to handle it
                    if (tokensConsumed > 1) {
                        // Add any pending content up to the newline
                        buffer.append(pendingBuffer);
                        pendingBuffer.setLength(0);

                        // Skip the newline (it triggered heredoc) and all consumed content
                        tokPos = afterHeredocTokPos - 1;  // -1 because loop will increment
                    } else {
                        // Heredoc only consumed the newline, add pending content including newline
                        pendingBuffer.append(currentToken.text);
                        buffer.append(pendingBuffer);
                        pendingBuffer.setLength(0);
                    }

                    // Restore parser position
                    parser.tokenIndex = savedIndex;

                    // Continue to next token
                    tokPos++;
                    continue;
                }
            }

            // Process token characters
            for (char ch : currentToken.text.toCharArray()) {
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
                                    buffer.append(pendingBuffer);  // Flush pending
                                    buffers.add(buffer.toString());
                                    buffer = new StringBuilder();
                                    pendingBuffer.setLength(0);
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
                        pendingBuffer.append(ch);  // Append to pending buffer
                        break;

                    case ESCAPE:
                        if (isRegex && !isPair && ch == endDelim) {
                            // Delimiter escape (e.g., \/ in qr/.../):
                            // Remove the preceding backslash that was already appended in STRING state.
                            // In Perl 5, delimiter escaping is resolved before \Q processing,
                            // so \Qfoo\/bar/ becomes \Qfoo/bar which \Q quotes as foo\/bar.
                            // The backslash might be in pendingBuffer or already flushed to buffer.
                            if (pendingBuffer.length() > 0) {
                                pendingBuffer.deleteCharAt(pendingBuffer.length() - 1);
                            } else if (buffer.length() > 0) {
                                buffer.deleteCharAt(buffer.length() - 1);
                            }
                        }
                        pendingBuffer.append(ch);  // Append escaped character to pending buffer
                        state = STRING;  // Return to STRING state
                        break;

                    case END_TOKEN:
                        remain.append(ch);  // Append remaining characters to remain buffer
                        break;
                }
            }

            // If we haven't hit a newline, flush pending buffer to main buffer
            if (currentToken.type != LexerTokenType.NEWLINE) {
                buffer.append(pendingBuffer);
                pendingBuffer.setLength(0);
            }

            tokPos++;  // Move to the next token
        }

        // Final flush of any pending content
        buffer.append(pendingBuffer);

        if (ctx.symbolTable.isStrictOptionEnabled(HINT_UTF8)
                || ctx.compilerOptions.isUnicodeSource) {
            // utf8 source code is true - keep Unicode string as-is
            buffers.add(buffer.toString());

            // System.out.println("buffers utf8: " + buffer.toString().length() + " " + buffer.toString());
        } else if (ctx.compilerOptions.isEvalbytes) {
            // evalbytes context - treat each character as a raw byte value
            // Characters <= 255 represent byte values directly
            String str = buffer.toString();
            StringBuilder octetString = new StringBuilder();

            for (int i = 0; i < str.length(); i++) {
                char ch = str.charAt(i);
                if (ch <= 255) {
                    // Treat as raw byte value
                    octetString.append(ch);
                } else {
                    // Character outside byte range - UTF-8 encode it
                    byte[] utf8Bytes = Character.toString(ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    for (byte b : utf8Bytes) {
                        octetString.append((char) (b & 0xFF));
                    }
                }
            }

            buffers.add(octetString.toString());
        } else if (ctx.compilerOptions.isByteStringSource) {
            // Source code originated from a BYTE_STRING scalar (e.g. eval STRING where STRING is bytes).
            // In this case buffer already represents raw bytes as chars 0..255.
            buffers.add(buffer.toString());
        } else {
            // utf8 source code is false - convert to octets
            String str = buffer.toString();
            StringBuilder octetString = new StringBuilder();

            // First, we need to convert the Unicode string back to UTF-8 bytes
            // to simulate reading the source file as raw bytes
            byte[] utf8Bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Then treat each UTF-8 byte as a separate character/octet
            for (byte b : utf8Bytes) {
                octetString.append((char) (b & 0xFF));
            }

            buffers.add(octetString.toString());
        }

        if (!remain.isEmpty()) {
            tokPos--;
            String remainStr = remain.toString();
            // When a multi-char token (e.g., "/=") has its leading character consumed as a
            // regex close delimiter, the remainder ("=") may need to be merged with the next
            // token to reconstruct a binding operator. Example: qr/x/=~ was tokenized as
            // qr, /, x, /=, ~ — after consuming "/" from "/=", remainder "=" + next "~" = "=~"
            // Similarly, qr/x/=>'val' needs "=" + ">" merged into "=>" (fat comma).
            if ((remainStr.equals("=") || remainStr.equals("!"))
                    && tokPos + 1 < tokens.size()
                    && tokens.get(tokPos + 1).text.equals("~")) {
                remainStr = remainStr + "~";
                // Neutralize the consumed ~ token so it won't be parsed again
                tokens.get(tokPos + 1).text = " ";
                tokens.get(tokPos + 1).type = LexerTokenType.WHITESPACE;
            } else if (remainStr.equals("=")
                    && tokPos + 1 < tokens.size()
                    && tokens.get(tokPos + 1).text.equals(">")) {
                remainStr = "=>";
                // Neutralize the consumed > token so it won't be parsed again
                tokens.get(tokPos + 1).text = " ";
                tokens.get(tokPos + 1).type = LexerTokenType.WHITESPACE;
            }
            tokens.get(tokPos).text = remainStr;  // Put the remaining string back in the tokens list
        }
        return new ParsedString(index, tokPos, buffers, startDelim, endDelim, ' ', ' ');
    }

    public static ParsedString parseRawStrings(Parser parser, EmitterContext ctx, List<LexerToken> tokens, int tokenIndex, int stringCount) {
        return parseRawStrings(parser, ctx, tokens, tokenIndex, stringCount, false);
    }

    public static ParsedString parseRawStrings(Parser parser, EmitterContext ctx, List<LexerToken> tokens, int tokenIndex, int stringCount, boolean isRegex) {
        int pos = tokenIndex;
        boolean redo = (stringCount == 3);
        ParsedString ast = parseRawStringWithDelimiter(ctx, tokens, pos, redo, parser, isRegex); // use redo flag to extract 2 strings
        if (stringCount == 1) {
            return ast;
        }
        pos = ast.next;

        if (stringCount == 3) { // fetch the second of 3 strings: s{aaa}{SECOND}ig
            char delim = ast.startDelim; // / or {
            if (QUOTE_PAIR.containsKey(delim)) {
                pos = Whitespace.skipWhitespace(parser, pos, tokens);
                ParsedString ast2 = parseRawStringWithDelimiter(ctx, tokens, pos, false, parser);
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

    static Node parseRegexString(EmitterContext ctx, ParsedString rawStr, Parser parser, String modifiers) {
        Node parsed;

        if (rawStr.startDelim == '\'') {
            // single quote delimiter, use the string as-is
            parsed = new StringNode(rawStr.buffers.getFirst(), rawStr.index);
        } else {
            // Check if /x modifier is present
            boolean hasXModifier = modifiers != null && modifiers.contains("x");
            
            String patternStr = rawStr.buffers.getFirst();
            if (hasXModifier) {
                // With /x modifier, strip comments before variable interpolation
                // Comments start with # and extend to newline (but not inside [...] or escaped)
                patternStr = stripRegexComments(patternStr);
                // Create a modified ParsedString with comments stripped
                ArrayList<String> modifiedBuffers = new ArrayList<>(rawStr.buffers);
                modifiedBuffers.set(0, patternStr);
                rawStr = new ParsedString(rawStr.index, rawStr.next, modifiedBuffers,
                        rawStr.startDelim, rawStr.endDelim,
                        rawStr.secondBufferStartDelim, rawStr.secondBufferEndDelim);
            }
            
            // interpolate variables, but ignore the escapes, keep `\$` if present
            // Pass shared heredoc nodes to handle heredocs inside regex patterns
            parsed = StringDoubleQuoted.parseDoubleQuotedString(ctx, rawStr, false, true, true,
                    parser != null ? parser.getHeredocNodes() : null);
        }
        return parsed;
    }

    /**
     * Strip comments from a regex pattern for /x mode.
     * Comments start with # and extend to newline.
     * But # is NOT a comment when:
     * - Inside [...] character classes
     * - Escaped as \#
     * - Inside (?{...}) or (??{...}) code blocks
     * - Part of (?#...) inline comments (these are preserved)
     */
    private static String stripRegexComments(String pattern) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = pattern.length();
        boolean inCharClass = false;
        int codeBlockDepth = 0;  // Track nested (?{...}) code blocks
        
        while (i < len) {
            char c = pattern.charAt(i);
            
            if (c == '\\' && i + 1 < len) {
                // Escaped character - copy both chars
                result.append(c);
                result.append(pattern.charAt(i + 1));
                i += 2;
                continue;
            }
            
            if (c == '[' && !inCharClass && codeBlockDepth == 0) {
                inCharClass = true;
                result.append(c);
                i++;
                continue;
            }
            
            if (c == ']' && inCharClass) {
                inCharClass = false;
                result.append(c);
                i++;
                continue;
            }
            
            // Check for special (?...) sequences
            if (c == '(' && !inCharClass && codeBlockDepth == 0 && i + 1 < len && pattern.charAt(i + 1) == '?') {
                // Check what follows (?
                if (i + 2 < len) {
                    char afterQ = pattern.charAt(i + 2);
                    
                    // (?#...) is an inline comment - copy until closing )
                    if (afterQ == '#') {
                        result.append(c);  // (
                        i++;
                        result.append(pattern.charAt(i));  // ?
                        i++;
                        result.append(pattern.charAt(i));  // #
                        i++;
                        // Copy until closing )
                        while (i < len && pattern.charAt(i) != ')') {
                            result.append(pattern.charAt(i));
                            i++;
                        }
                        if (i < len) {
                            result.append(pattern.charAt(i));  // )
                            i++;
                        }
                        continue;
                    }
                    
                    // (?{ or (??{ starts a code block
                    if (afterQ == '{' || (afterQ == '?' && i + 3 < len && pattern.charAt(i + 3) == '{')) {
                        result.append(c);  // (
                        i++;
                        result.append(pattern.charAt(i));  // ?
                        i++;
                        if (pattern.charAt(i) == '?') {
                            result.append(pattern.charAt(i));  // second ? for (??{
                            i++;
                        }
                        result.append(pattern.charAt(i));  // {
                        i++;
                        codeBlockDepth++;
                        continue;
                    }
                }
            }
            
            // Track brace nesting inside code blocks
            if (codeBlockDepth > 0) {
                if (c == '{') {
                    codeBlockDepth++;
                } else if (c == '}') {
                    codeBlockDepth--;
                }
                result.append(c);
                i++;
                continue;
            }
            
            if (c == '#' && !inCharClass && codeBlockDepth == 0) {
                // Start of /x comment - skip until newline
                while (i < len && pattern.charAt(i) != '\n') {
                    i++;
                }
                // Keep the newline if present (it's significant in regex)
                if (i < len && pattern.charAt(i) == '\n') {
                    result.append('\n');
                    i++;
                }
                continue;
            }
            
            result.append(c);
            i++;
        }
        
        return result.toString();
    }


    public static ListNode parseWordsString(ParsedString rawStr) {
        String input = rawStr.buffers.getFirst();
        char startDelim = rawStr.startDelim;
        char endDelim = rawStr.endDelim;

        StringBuilder processed = new StringBuilder();
        char[] chars = input.toCharArray();
        int length = chars.length;
        int index = 0;
        while (index < length) {
            char ch = chars[index];
            if (ch == '\\') {
                index++;
                if (index < length) {
                    char nextChar = chars[index];
                    if (nextChar == '\\' || nextChar == startDelim || nextChar == endDelim) {
                        processed.append(nextChar);
                    } else {
                        processed.append('\\').append(nextChar);
                    }
                }
            } else {
                processed.append(ch);
            }
            index++;
        }

        String trimmed = processed.toString().trim();
        ListNode list = new ListNode(rawStr.index);
        if (trimmed.isEmpty()) {
            return list;
        }
        String[] words = trimmed.split("\\s+");
        for (String word : words) {
            list.elements.add(new StringNode(word, rawStr.index));
        }
        return list;
    }

    public static OperatorNode parseRegexReplace(EmitterContext ctx, ParsedString rawStr, Parser parser) {
        String operator = "replaceRegex";
        String replaceStr = rawStr.buffers.get(1);
        String modifierStr = rawStr.buffers.get(2);
        Node parsed = parseRegexString(ctx, rawStr, parser, modifierStr);

        Node replace;
        if (modifierStr.contains("e")) {
            // if modifiers include `e`, then parse the `replace` code
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("regex e-modifier: " + replaceStr);
            Parser blockParser = new Parser(ctx, new Lexer(replaceStr).tokenize(), parser.getHeredocNodes());
            replace = ParseBlock.parseBlock(blockParser);
        } else if (rawStr.secondBufferStartDelim != '\'') {
            // handle string interpolaton
            rawStr.buffers.removeFirst();   // consume the first buffer
            replace = StringDoubleQuoted.parseDoubleQuotedString(ctx, rawStr, true, true, true);
        } else {
            // handle single quoted string
            rawStr.buffers.removeFirst();   // consume the first buffer
            replace = StringSingleQuoted.parseSingleQuotedString(rawStr);
        }

        if (modifierStr.chars().filter(c -> c == 'e').count() >= 2) {
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

    public static OperatorNode parseRegexMatch(EmitterContext ctx, String operator, ParsedString rawStr, Parser parser) {
        operator = operator.equals("qr") ? "quoteRegex" : "matchRegex";
        String modStr = rawStr.buffers.get(1);
        
        // Add default modifiers from `use re` pragma if not already present
        if (ctx.symbolTable != null) {
            if (ctx.symbolTable.isStrictOptionEnabled(HINT_RE_ASCII)) {
                if (!modStr.contains("a") && !modStr.contains("u")) {
                    modStr = "a" + modStr;
                }
            } else if (ctx.symbolTable.isStrictOptionEnabled(HINT_RE_UNICODE)) {
                if (!modStr.contains("u") && !modStr.contains("a")) {
                    modStr = "u" + modStr;
                }
            }
        }
        
        Node parsed = parseRegexString(ctx, rawStr, parser, modStr);
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
        // Parse as interpolated string (like double quotes)
        Node parsed = StringDoubleQuoted.parseDoubleQuotedString(ctx, rawStr, true, true, false);
        List<Node> elements = new ArrayList<>();
        elements.add(parsed);
        ListNode list = new ListNode(elements, rawStr.index);
        return new OperatorNode(operator, list, rawStr.index);
    }

    public static OperatorNode parseTransliteration(EmitterContext ctx, ParsedString rawStr) {
        String operator = "tr";

        // Get the search list and replacement list
        String searchList = rawStr.buffers.get(0);
        String replacementList = rawStr.buffers.get(1);
        String modifiers = rawStr.buffers.get(2);

        Node searchNode;
        Node replacementNode;

        // If single quote delimiter, only process \\ escapes
        if (rawStr.startDelim == '\'') {
            // For single quotes, only remove \ from pairs of \\
            searchList = searchList.replace("\\\\", "\\");
            searchNode = new StringNode(searchList, rawStr.index);
        } else {
            // For other delimiters, process double-quote escape sequences
            // but without variable interpolation
            ParsedString searchParsed = new ParsedString(
                    rawStr.index,
                    rawStr.next,
                    new ArrayList<>(List.of(searchList)),
                    rawStr.startDelim,
                    rawStr.endDelim,
                    ' ', ' '
            );
            // searchNode = StringDoubleQuoted.parseDoubleQuotedString(ctx, searchParsed, true, false);
            searchNode = StringDoubleQuoted.parseDoubleQuotedString(ctx, searchParsed, false, false, false);
        }

        // Same logic for replacement list
        if (rawStr.secondBufferStartDelim == '\'') {
            replacementList = replacementList.replace("\\\\", "\\");
            replacementNode = new StringNode(replacementList, rawStr.index);
        } else {
            ParsedString replaceParsed = new ParsedString(
                    rawStr.index,
                    rawStr.next,
                    new ArrayList<>(List.of(replacementList)),
                    rawStr.secondBufferStartDelim,
                    rawStr.secondBufferEndDelim,
                    ' ', ' '
            );
            // replacementNode = StringDoubleQuoted.parseDoubleQuotedString(ctx, replaceParsed, true, false);
            replacementNode = StringDoubleQuoted.parseDoubleQuotedString(ctx, replaceParsed, false, false, false);
        }

        Node modifierNode = new StringNode(modifiers, rawStr.index);

        List<Node> elements = new ArrayList<>();
        elements.add(searchNode);
        elements.add(replacementNode);
        elements.add(modifierNode);

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
        // Regex operators need delimiter escape resolution (e.g., \/ → / in qr/.../\Q...\E/)
        boolean isRegex = switch (operator) {
            case "m", "qr", "/", "//", "/=", "s" -> true;
            default -> false;
        };
        rawStr = parseRawStrings(parser, parser.ctx, parser.tokens, parser.tokenIndex, stringParts, isRegex);
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
                return parseRegexMatch(parser.ctx, operator, rawStr, parser);
            case "s":
                return parseRegexReplace(parser.ctx, rawStr, parser);
            case "\"":
            case "qq":
                return StringDoubleQuoted.parseDoubleQuotedString(parser.ctx, rawStr, true, true, false, parser.getHeredocNodes(), parser);
            case "qw":
                return parseWordsString(rawStr);
            case "tr":
            case "y":
                return parseTransliteration(parser.ctx, rawStr);
            case "<>": {
                // In Perl, <$var/*.t> uses double-quote interpolation (like qq//)
                // before passing to glob(). This ensures variables are interpolated.
                Node interpolated = StringDoubleQuoted.parseDoubleQuotedString(
                        parser.ctx, rawStr, true, true, false, parser.getHeredocNodes(), parser);
                ListNode diamondList = new ListNode(rawStr.index);
                diamondList.elements.add(interpolated);
                return new OperatorNode("<>", diamondList, rawStr.index);
            }
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
            vStringBuilder.appendCodePoint(charCode);
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
                    vStringBuilder.appendCodePoint(charCode);
                } else {
                    break; // Exit the loop if the next token is not a number
                }
            } else {
                break; // Exit the loop if the next token is not a dot
            }
        }

        if (CompilerOptions.DEBUG_ENABLED) parser.ctx.logDebug("v-string: " + printable(vStringBuilder.toString()) + " next:" + TokenUtils.peek(parser));

        // Create a StringNode with the constructed v-string
        return new StringNode(vStringBuilder.toString(), true, currentIndex);
    }

    public static void assertNoWideCharacters(String toWrite, String message) {
        for (int i = 0; i < toWrite.length(); i++) {
            if (toWrite.charAt(i) > 255) {
                throw new PerlCompilerException("Wide character in " + message);
            }
        }
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


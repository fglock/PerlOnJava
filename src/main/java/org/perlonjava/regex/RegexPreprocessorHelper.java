package org.perlonjava.regex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.perlonjava.regex.UnicodeResolver.translateUnicodeProperty;

public class RegexPreprocessorHelper {
    static int handleEscapeSequences(String s, StringBuilder sb, int c, int offset) {
        sb.append(Character.toChars(c));  // This appends the backslash
        final int length = s.length();

        offset++;
        if (offset >= length) {
            return offset;
        }

        char nextChar = s.charAt(offset);
        if (nextChar == 'g' && offset + 1 < length && s.charAt(offset + 1) == '{') {
            // Handle \g{name} backreference
            offset += 2; // Skip past \g{
            int endBrace = s.indexOf('}', offset);
            if (endBrace != -1) {
                String ref = s.substring(offset, endBrace);
                if (ref.startsWith("-")) {
                    // Handle relative backreference
                    int relativeRef = Integer.parseInt(ref);
                    int absoluteRef = RegexPreprocessor.captureGroupCount + relativeRef + 1;
                    if (absoluteRef > 0) {
                        sb.setLength(sb.length() - 1); // Remove the backslash
                        sb.append("\\").append(absoluteRef);
                    } else {
                        throw new IllegalArgumentException("Invalid relative backreference: " + ref);
                    }
                } else {
                    // Handle named backreference
                    sb.setLength(sb.length() - 1); // Remove the backslash
                    sb.append("\\k<").append(ref).append(">");
                }
                offset = endBrace;
            }
        } else if ((nextChar == 'b' || nextChar == 'B') && offset + 1 < length && s.charAt(offset + 1) == '{') {
            // Handle \b{...} and \B{...} boundary assertions
            boolean negated = (nextChar == 'B');
            offset += 2; // Skip past \b{ or \B{
            int endBrace = s.indexOf('}', offset);
            if (endBrace != -1) {
                String boundaryType = s.substring(offset, endBrace).trim();
                sb.setLength(sb.length() - 1); // Remove the backslash

                if (negated) {
                    // Negated boundaries - wrap in negative lookaround
                    sb.append("(?!");
                }

                switch (boundaryType) {
                    case "gcb":
                        // Grapheme cluster boundary
                        // This is a simplified version - a full implementation would be more complex
                        sb.append("(?<!\\p{M})(?=\\p{M})|(?<=\\p{M})(?!\\p{M})|");
                        sb.append("(?<!\\p{L})(?=\\p{L})|(?<=\\p{L})(?!\\p{L})|");
                        sb.append("(?<![\uD800-\uDBFF])(?=[\uD800-\uDBFF])|");
                        sb.append("(?<=[\uDC00-\uDFFF])(?![\uDC00-\uDFFF])");
                        break;
                    case "lb":
                        // Line break boundary
                        sb.append("(?=\\r\\n|[\\n\\r\\u0085\\u2028\\u2029])|");
                        sb.append("(?<=\\r\\n|[\\n\\r\\u0085\\u2028\\u2029])");
                        break;
                    case "sb":
                        // Sentence boundary (simplified)
                        sb.append("(?<=[.!?])\\s+(?=[A-Z])|");
                        sb.append("(?<=^)(?=.)|(?<=.)(?=$)");
                        break;
                    case "wb":
                        // Word boundary
                        if (negated) {
                            // For \B{wb}, we can use the simpler \B
                            sb.setLength(sb.length() - 3); // Remove "(?!"
                            sb.append("\\B");
                            offset = endBrace;
                            return offset;
                        } else {
                            sb.append("\\b");
                        }
                        break;
                    default:
                        RegexPreprocessor.regexError(s, offset - 3,
                                "Unknown boundary type '" + boundaryType + "' in \\" + nextChar + "{...}");
                }

                if (negated) {
                    sb.append(")");  // Close the negative lookahead
                }

                offset = endBrace;
            } else {
                RegexPreprocessor.regexError(s, offset, "Unmatched brace in \\" + nextChar + "{...} construct");
            }
        } else if (nextChar == 'N') {
            // Handle \N constructs
            if (offset + 1 < length && s.charAt(offset + 1) == '{') {
                // Check if it's a quantifier or a Unicode name
                offset += 2; // Skip past \N{
                int endBrace = s.indexOf('}', offset);
                if (endBrace != -1) {
                    String content = s.substring(offset, endBrace).trim();
                    // Check if content is a quantifier (digits, comma, optional spaces)
                    if (content.matches("\\s*\\d+\\s*(?:,\\s*\\d*\\s*)?")) {
                        // It's a quantifier like {2} or {3,4} or {3,}
                        // Remove all spaces from the quantifier for Java compatibility
                        String cleanQuantifier = content.replaceAll("\\s+", "");
                        sb.setLength(sb.length() - 1); // Remove only the backslash
                        sb.append("[^\\n]{").append(cleanQuantifier).append("}");
                        return endBrace;
                    } else {
                        // It's a Unicode name
                        int codePoint = UnicodeResolver.getCodePointFromName(content);
                        sb.append(String.format("x{%X}", codePoint));
                        return endBrace;
                    }
                } else {
                    RegexPreprocessor.regexError(s, offset, "Unmatched brace in \\N{...} construct");
                }
            } else {
                // Plain \N without braces - matches any non-newline character
                sb.setLength(sb.length() - 1); // Remove only the backslash
                sb.append("[^\\n]");
                return offset;
            }
        } else if ((nextChar == 'p' || nextChar == 'P') && offset + 1 < length && s.charAt(offset + 1) == '{') {
            // Handle \p{...} and \P{...} constructs
            boolean negated = (nextChar == 'P');
            offset += 2; // Skip past \p or \P
            int endBrace = s.indexOf('}', offset);
            if (endBrace != -1) {
                String property = s.substring(offset, endBrace).trim();
                String translatedProperty = translateUnicodeProperty(property, negated);
                sb.setLength(sb.length() - 1); // Remove the backslash
                sb.append(translatedProperty);
                offset = endBrace;
            } else {
                RegexPreprocessor.regexError(s, offset, "Unmatched brace in \\p{...} or \\P{...} construct");
            }
        } else {
            int c2 = s.codePointAt(offset);
            if (c2 >= '1' && c2 <= '3') {
                // Check if this might be an octal sequence \123
                // We need at least 2 more characters after the current position
                if (offset + 2 < length) {
                    int c3 = s.codePointAt(offset + 1);
                    int c4 = s.codePointAt(offset + 2);
                    if ((c3 >= '0' && c3 <= '7') && (c4 >= '0' && c4 <= '7')) {
                        // Handle \000 octal sequences
                        sb.append('0');
                    }
                }
            } else if (c2 == '0') {
                // Rewrite \0 to \00
                sb.append('0');
            }
            sb.append(Character.toChars(c2));
        }
        return offset;
    }

    /**
     * Handles escape sequences within character classes.
     *
     * @param offset   The current offset in the regex string.
     * @param s        The regex string.
     * @param sb       The StringBuilder to append processed characters.
     * @param length   The length of the regex string.
     * @param flag_xx  A flag indicating whether to treat spaces as tokens.
     * @param rejected A StringBuilder to collect rejected sequences.
     * @return The updated offset after processing the character class.
     */
    static int handleRegexCharacterClassEscape(int offset, String s, StringBuilder sb, int length, boolean flag_xx,
                                               StringBuilder rejected) {
        // inside [ ... ]
        //      space    becomes: "\ " unless the /xx flag is used (flag_xx)
        //      \120     becomes: \0120 - Java requires octal sequences to start with zero
        //      \0       becomes: \00 - Java requires the extra zero
        //      [:ascii:]  becomes: \p{ASCII}
        //      [:^ascii:] becomes: \P{ASCII}
        //      \b       is rejected, Java doesn't support \b inside [...]
        boolean first = true;
        boolean afterCaret = false;
        int lastChar = -1;  // Track last character for range validation
        boolean wasEscape = false;  // Track if last char was from escape sequence

        while (offset < length) {
            final int c = s.codePointAt(offset);
            switch (c) {
                case ']':
                    // Special case: ] immediately after [ or [^ is a literal ]
                    if (first || afterCaret) {
                        sb.append("\\]");
                        first = false;
                        afterCaret = false;
                        lastChar = ']';
                        wasEscape = false;
                        break;
                    } else {
                        sb.append(Character.toChars(c));
                        return offset;
                    }
                case '^':
                    if (first) {
                        afterCaret = true;
                    }
                    sb.append(Character.toChars(c));
                    first = false;
                    lastChar = -1;  // Reset for special chars
                    wasEscape = false;
                    break;
                case '-':
                    // Check for invalid range
                    if (lastChar != -1 && !wasEscape && offset + 1 < length) {
                        int nextPos = offset + 1;
                        int nextChar = s.codePointAt(nextPos);

                        // Skip if next is ], then it's a literal -
                        if (nextChar != ']') {
                            // Handle escaped next character
                            if (nextChar == '\\' && nextPos + 1 < length) {
                                nextChar = s.codePointAt(nextPos + 1);
                                // Special handling for escape sequences
                                if (nextChar == 'b' || nextChar == 'N' || nextChar == 'p' || nextChar == 'P') {
                                    // These are special escapes, can't be in range
                                    nextChar = -1;
                                }
                            }

                            if (nextChar != -1 && nextChar < lastChar) {
                                String rangeStart = Character.toString(lastChar);
                                String rangeEnd = Character.toString(nextChar);
                                RegexPreprocessor.regexError(s, offset,
                                    "Invalid [] range \"" + rangeStart + "-" + rangeEnd + "\" in regex");
                            }
                        }
                    }
                    sb.append(Character.toChars(c));
                    first = false;
                    afterCaret = false;
                    lastChar = -1;  // Reset after dash
                    wasEscape = false;
                    break;
                case '[':
                    // Check for character class like [:ascii:]
                    offset = RegexPreprocessor.handleCharacterClass(offset, s, sb, length);
                    first = false;
                    afterCaret = false;
                    lastChar = -1;  // Reset for special constructs
                    wasEscape = false;
                    break;
                case '\\':  // Handle escape sequences
                    sb.append(Character.toChars(c));
                    offset++;
                    wasEscape = true;
                    if (offset < length && s.charAt(offset) == 'N') {
                        if (offset + 1 < length && s.charAt(offset + 1) == '{') {
                            // Handle \N{...} constructs
                            offset += 2; // Skip past \N{
                            int endBrace = s.indexOf('}', offset);
                            if (endBrace != -1) {
                                String content = s.substring(offset, endBrace).trim();
                                // Check if content is a quantifier
                                if (content.matches("\\d+(?:\\s*,\\s*\\d*)?")) {
                                    // Can't use quantifiers inside character class
                                    RegexPreprocessor.regexError(s, offset - 2, "Quantifier \\N{" + content + "} not allowed inside character class");
                                } else {
                                    // It's a Unicode name
                                    int codePoint = UnicodeResolver.getCodePointFromName(content);
                                    sb.append(String.format("x{%X}", codePoint));
                                    offset = endBrace;
                                }
                            } else {
                                RegexPreprocessor.regexError(s, offset, "Unmatched brace in \\N{name} construct");
                            }
                        } else {
                            // Plain \N - but inside character class we can't use [^\n]
                            // We need to handle this differently - maybe reject it
                            RegexPreprocessor.regexError(s, offset - 1, "\\N (non-newline) not supported inside character class");
                        }
                        lastChar = -1;  // Can't use \N in ranges
                    } else if (s.codePointAt(offset) == 'b') {
                        rejected.append("\\b"); // Java doesn't support \b inside [...]
                        offset++;
                        lastChar = -1;
                    } else {
                        int c2 = s.codePointAt(offset);
                        if (c2 >= '1' && c2 <= '3') {
                            if (offset < length + 1) {
                                int off = offset;
                                int c3 = s.codePointAt(off++);
                                int c4 = s.codePointAt(off++);
                                if ((c3 >= '0' && c3 <= '7') && (c4 >= '0' && c4 <= '7')) {
                                    // Handle \000 octal sequences
                                    sb.append('0');
                                }
                            }
                        } else if (c2 == '0') {
                            // Rewrite \0 to \00
                            sb.append('0');
                        }
                        sb.append(Character.toChars(c2));
                        // Remember the actual character for range validation
                        if (c2 != 'p' && c2 != 'P') {  // Skip property escapes
                            lastChar = c2;
                        } else {
                            lastChar = -1;
                        }
                    }
                    first = false;
                    afterCaret = false;
                    break;
                case ' ', '\t':
                    if (flag_xx) {
                        sb.append(Character.toChars(c));
                    } else {
                        // make this space a "token", even inside /x
                        sb.append("\\").append(Character.toChars(c));
                    }
                    first = false;
                    afterCaret = false;
                    lastChar = c;
                    wasEscape = false;
                    break;
                case '(', ')', '*', '?', '<', '>', '\'', '"', '`', '@', '#', '=', '&':
                    sb.append('\\');
                    sb.append(Character.toChars(c));
                    first = false;
                    afterCaret = false;
                    lastChar = c;
                    wasEscape = false;
                    break;
                default:
                    sb.append(Character.toChars(c));
                    first = false;
                    afterCaret = false;
                    lastChar = c;
                    wasEscape = false;
                    break;
            }
            offset++;
        }
        return offset;
    }

    static int handleFlagModifiers(String s, int offset, StringBuilder sb, RegexFlags regexFlags) {
        int start = offset + 2; // Skip past '(?'
        int colonPos = s.indexOf(':', start);
        int closeParen = s.indexOf(')', start);

        if (closeParen == -1) {
            RegexPreprocessor.regexError(s, offset, "Unterminated ( in regex");
        }

        int flagsEnd = (colonPos == -1 || closeParen < colonPos) ? closeParen : colonPos;
        String flags = s.substring(start, flagsEnd);
        sb.append("(?");

        // Split into positive and negative parts
        String[] parts = flags.split("-", 2);
        String positiveFlags = parts[0];
        String negativeFlags = parts.length > 1 ? parts[1] : "";

        // Handle caret case
        if (positiveFlags.charAt(0) == '^') {
            positiveFlags = positiveFlags.substring(1);
            Set<Character> negSet = new HashSet<>();
            for (char c : (negativeFlags + "imnsx").toCharArray()) {
                negSet.add(c);
            }
            // Remove any negative flags that appear in positive flags
            for (char c : positiveFlags.toCharArray()) {
                negSet.remove(c);
            }
            negativeFlags = negSet.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining());
        }

        RegexFlags newFlags = regexFlags.with(positiveFlags, negativeFlags);

        // Handle `n` flags
        if (positiveFlags.indexOf('n') >= 0) {
            positiveFlags = positiveFlags.replace("n", "");
        }
        if (negativeFlags.indexOf('n') >= 0) {
            negativeFlags = negativeFlags.replace("n", "");
        }

        // Filter flags to only include Java-supported ones
        positiveFlags = filterSupportedFlags(positiveFlags);
        negativeFlags = filterSupportedFlags(negativeFlags);

        // Build the new flag string
        sb.append(positiveFlags);
        if (!negativeFlags.isEmpty()) {
            sb.append('-').append(negativeFlags);
        }

        if (colonPos == -1 || closeParen < colonPos) {
            // Case: `(?flags)pattern`
            sb.append(")");
            offset = RegexPreprocessor.handleRegex(s, closeParen + 1, sb, newFlags, true);
            // The closing parenthesis, if any, is consumed by the caller
            if (offset < s.length()) {
                offset--;
            }
            return offset;
        }

        // Case: `(?flags:pattern)`
        sb.append(":");
        offset = RegexPreprocessor.handleRegex(s, colonPos + 1, sb, newFlags, true);

        // Ensure the closing parenthesis is consumed
        if (offset >= s.length() || s.codePointAt(offset) != ')') {
            RegexPreprocessor.regexError(s, offset, "Unterminated ( in regex");
        }
        sb.append(')');
        return offset;
    }

    /**
     * Filters out regex flags that are not supported by Java's regex engine.
     *
     * @param flags The flags string to filter
     * @return The filtered flags string containing only Java-supported flags
     */
    private static String filterSupportedFlags(String flags) {
        // Java supports: i, m, s, x, u, d
        // We need to remove: a, l, p, and any other Perl-specific flags
        StringBuilder filtered = new StringBuilder();
        for (char c : flags.toCharArray()) {
            switch (c) {
                case 'i': // case-insensitive
                case 'm': // multi-line
                case 's': // single-line (dotall)
                case 'x': // extended/comments
                case 'u': // Unicode case
                case 'd': // Unix lines
                    filtered.append(c);
                    break;
                // Skip unsupported flags
                case 'a': // ASCII
                case 'l': // locale
                case 'p': // preserve
                case 'n': // no capture (already handled separately)
                    // These are Perl flags not supported in Java
                    break;
                default:
                    // Unknown flag - you might want to warn or error here
                    break;
            }
        }
        return filtered.toString();
    }

    /**
     * Handles Perl's Extended Bracketed Character Class (?[...])
     * Transforms it into Java-compatible character class syntax.
     *
     * @param s The regex string
     * @param offset Current position (at '(')
     * @param sb StringBuilder for output
     * @param regexFlags Current regex flags
     * @return Position after the closing ])
     */
    static int handleExtendedCharacterClass(String s, int offset, StringBuilder sb, RegexFlags regexFlags) {
        int start = offset + 3; // Skip past '(?['
        int end = findExtendedClassEnd(s, start);

        if (end == -1) {
            RegexPreprocessor.regexError(s, offset, "Unterminated (?[...]) in regex");
        }

        String content = s.substring(start, end);

        try {
            // Parse and transform the extended character class
            String transformed = transformExtendedClass(content, s, start);
            sb.append(transformed);

            // Skip past the '])'
            return end + 1;
        } catch (Exception e) {
            RegexPreprocessor.regexError(s, start, e.getMessage());
            return -1; // Never reached due to exception
        }
    }

    /**
     * Find the end of the extended character class, handling nested brackets
     */
    private static int findExtendedClassEnd(String s, int start) {
        int depth = 1;
        int i = start;
        boolean inEscape = false;
        boolean inCharClass = false;
        boolean firstInClass = false;
        boolean afterCaret = false;

        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);

            if (inEscape) {
                inEscape = false;
                firstInClass = false;
                afterCaret = false;
                i++;
                continue;
            }

            switch (c) {
                case '\\':
                    inEscape = true;
                    firstInClass = false;
                    afterCaret = false;
                    break;
                case '[':
                    if (!inCharClass) {
                        inCharClass = true;
                        firstInClass = true;
                        afterCaret = false;
                    }
                    depth++;
                    break;
                case '^':
                    if (inCharClass && firstInClass) {
                        afterCaret = true;
                    }
                    firstInClass = false;
                    break;
                case ']':
                    // Special case: ] immediately after [ or [^ is literal
                    if (inCharClass && (firstInClass || afterCaret)) {
                        // This is a literal ], don't decrease depth
                        firstInClass = false;
                        afterCaret = false;
                    } else {
                        depth--;
                        if (inCharClass && depth > 0) {
                            // We just closed a character class
                            inCharClass = false;
                        }

                        if (depth == 0) {
                            // Check if this ends the extended character class
                            int j = i + 1;
                            while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                                j++;
                            }
                            if (j < s.length() && s.charAt(j) == ')') {
                                return i;
                            }
                            // Not properly terminated
                            return -1;
                        }
                    }
                    break;
                default:
                    if (!Character.isWhitespace(c)) {
                        firstInClass = false;
                        afterCaret = false;
                    }
                    break;
            }
            i++;
        }

        return -1;
    }

    /**
     * Transform the extended character class content into Java syntax
     */
    private static String transformExtendedClass(String content, String originalRegex, int contentStart) {
        // Tokenize the expression
        List<Token> tokens = tokenizeExtendedClass(content, originalRegex, contentStart);

        // Parse into expression tree
        ExprNode tree = parseExtendedClass(tokens, originalRegex, contentStart);

        // Transform to Java syntax
        return evaluateExtendedClass(tree);
    }

    // Token types for the parser
    private enum TokenType {
        CHAR_CLASS, OPERATOR, LPAREN, RPAREN, EOF
    }

    private static class Token {
        TokenType type;
        String value;
        int position;

        Token(TokenType type, String value, int position) {
            this.type = type;
            this.value = value;
            this.position = position;
        }
    }

    /**
     * Tokenize the extended character class content
     * Automatically handles whitespace (xx mode)
     */
    private static List<Token> tokenizeExtendedClass(String content, String originalRegex, int contentStart) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;

        while (i < content.length()) {
            // Skip whitespace (automatic /xx mode)
            while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
                i++;
            }

            if (i >= content.length()) break;

            char c = content.charAt(i);
            int tokenStart = contentStart + i;

            switch (c) {
                case '&':
                case '+':
                case '|':
                case '-':
                case '^':
                    tokens.add(new Token(TokenType.OPERATOR, String.valueOf(c), tokenStart));
                    i++;
                    break;

                case '!':
                    tokens.add(new Token(TokenType.OPERATOR, "!", tokenStart));
                    i++;
                    break;

                case '(':
                    tokens.add(new Token(TokenType.LPAREN, "(", tokenStart));
                    i++;
                    break;

                case ')':
                    tokens.add(new Token(TokenType.RPAREN, ")", tokenStart));
                    i++;
                    break;

                case '[':
                    // Parse a character class element
                    int classEnd = findCharClassEnd(content, i);
                    if (classEnd == -1) {
                        RegexPreprocessor.regexError(originalRegex, tokenStart, "Unterminated character class");
                    }
                    String classContent = content.substring(i, classEnd + 1);

                    // Validate character ranges in the class
                    validateCharacterRanges(classContent, originalRegex, tokenStart);

                    tokens.add(new Token(TokenType.CHAR_CLASS, classContent, tokenStart));
                    i = classEnd + 1;
                    break;

                case ':':
                    // Check for POSIX class
                    if (i + 1 < content.length() && content.charAt(i + 1) == ':') {
                        int posixEnd = content.indexOf("::", i + 2);
                        if (posixEnd != -1) {
                            String posixClass = content.substring(i, posixEnd + 2);
                            tokens.add(new Token(TokenType.CHAR_CLASS, posixClass, tokenStart));
                            i = posixEnd + 2;
                            break;
                        }
                    }
                    // Fall through to error

                case '\\':
                    // Parse escape sequence as a character class element
                    int escapeEnd = parseEscapeInExtendedClass(content, i);
                    String escapeSeq = content.substring(i, escapeEnd);
                    tokens.add(new Token(TokenType.CHAR_CLASS, "[" + escapeSeq + "]", tokenStart));
                    i = escapeEnd;
                    break;

                default:
                    // Single character must be wrapped in brackets
                    RegexPreprocessor.regexError(originalRegex, tokenStart,
                            "Bare character '" + c + "' not allowed in (?[...]). Use [" + c + "] instead");
            }
        }

        tokens.add(new Token(TokenType.EOF, "", contentStart + content.length()));
        return tokens;
    }

    private static void validateCharacterRanges(String charClass, String originalRegex, int classStart) {
        if (!charClass.startsWith("[") || !charClass.endsWith("]")) {
            return;
        }

        String content = charClass.substring(1, charClass.length() - 1);
        int i = 0;
        int lastChar = -1;
        boolean inEscape = false;

        while (i < content.length()) {
            char c = content.charAt(i);

            // Skip whitespace in extended character class
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (inEscape) {
                inEscape = false;
                lastChar = -1;
                i++;
                continue;
            }

            if (c == '\\') {
                inEscape = true;
            } else if (c == '-' && lastChar != -1 && i + 1 < content.length()) {
                char nextChar = content.charAt(i + 1);
                if (!Character.isWhitespace(nextChar) && nextChar != ']' && nextChar != '\\' && nextChar < lastChar) {
                    RegexPreprocessor.regexError(originalRegex, classStart + i + 1,
                        String.format("Invalid [] range \"%c-%c\" in regex", lastChar, nextChar));
                }
            } else if (c != '-' && c != '^' && c != '[' && c != ':') {
                lastChar = c;
            }

            i++;
        }
    }

    /**
     * Find the end of a character class [...]
     */
    private static int findCharClassEnd(String content, int start) {
        int i = start + 1; // Skip opening [
        boolean inEscape = false;
        boolean first = true;
        boolean afterCaret = false;

        while (i < content.length()) {
            char c = content.charAt(i);

            if (inEscape) {
                inEscape = false;
                first = false;
                afterCaret = false;
                i++;
                continue;
            }

            // In extended character classes, skip whitespace
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '\\') {
                inEscape = true;
                first = false;
                afterCaret = false;
            } else if (c == ']') {
                // Special case: ] immediately after [ or [^ is a literal ]
                if (first || afterCaret) {
                    // This is a literal ], not the closing bracket
                    first = false;
                    afterCaret = false;
                } else {
                    // This closes the character class
                    return i;
                }
            } else if (c == '^' && first) {
                afterCaret = true;
                first = false;
            } else {
                // Any non-whitespace character resets the flags
                first = false;
                afterCaret = false;
            }

            i++;
        }

        return -1;
    }

    /**
     * Parse an escape sequence in extended class
     */
    private static int parseEscapeInExtendedClass(String content, int start) {
        if (start + 1 >= content.length()) {
            return start + 1;
        }

        char next = content.charAt(start + 1);

        // Handle special escape sequences
        if (next == 'p' || next == 'P') {
            // Unicode property
            if (start + 2 < content.length() && content.charAt(start + 2) == '{') {
                int end = content.indexOf('}', start + 3);
                return end != -1 ? end + 1 : start + 2;
            }
        } else if (next == 'N') {
            // Named character
            if (start + 2 < content.length() && content.charAt(start + 2) == '{') {
                int end = content.indexOf('}', start + 3);
                return end != -1 ? end + 1 : start + 2;
            }
        } else if (next == 'x') {
            // Hex escape
            if (start + 2 < content.length() && content.charAt(start + 2) == '{') {
                int end = content.indexOf('}', start + 3);
                return end != -1 ? end + 1 : start + 4; // \xHH
            }
            return start + 4; // \xHH
        }

        // Default: single character escape
        return start + 2;
    }

    // Expression tree nodes
    private static abstract class ExprNode {
        abstract String evaluate();
    }

    private static class CharClassNode extends ExprNode {
        String content;

        CharClassNode(String content) {
            this.content = content;
        }

        @Override
        String evaluate() {
            // Process the character class content
            return processCharacterClass(content);
        }
    }

    private static class BinaryOpNode extends ExprNode {
        String operator;
        ExprNode left;
        ExprNode right;

        BinaryOpNode(String operator, ExprNode left, ExprNode right) {
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        @Override
        String evaluate() {
            String leftEval = left.evaluate();
            String rightEval = right.evaluate();

            // Unwrap outer brackets if present
            leftEval = unwrapBrackets(leftEval);
            rightEval = unwrapBrackets(rightEval);

            switch (operator) {
                case "+":
                case "|":
                    // Union: [A[B]]
                    return "[" + leftEval + rightEval + "]";

                case "&":
                    // Intersection: [A&&[B]]
                    return "[" + leftEval + "&&[" + rightEval + "]]";

                case "-":
                    // Subtraction: [A&&[^B]]
                    return "[" + leftEval + "&&[^" + rightEval + "]]";

                case "^":
                    // Symmetric difference: [[A&&[^B]][B&&[^A]]]
                    return "[[" + leftEval + "&&[^" + rightEval + "]][" +
                            rightEval + "&&[^" + leftEval + "]]]";

                default:
                    throw new IllegalStateException("Unknown operator: " + operator);
            }
        }
    }

    private static class UnaryOpNode extends ExprNode {
        String operator;
        ExprNode operand;

        UnaryOpNode(String operator, ExprNode operand) {
            this.operator = operator;
            this.operand = operand;
        }

        @Override
        String evaluate() {
            String operandEval = operand.evaluate();

            if (operator.equals("!")) {
                // Complement: [^A]
                operandEval = unwrapBrackets(operandEval);
                return "[^" + operandEval + "]";
            }

            throw new IllegalStateException("Unknown unary operator: " + operator);
        }
    }

    /**
     * Parse tokens into expression tree
     * Precedence: ! > & > (+|-|^|)
     * Binary ops are left-associative, unary is right-associative
     */
    private static ExprNode parseExtendedClass(List<Token> tokens, String originalRegex, int contentStart) {
        return new ExtendedClassParser(tokens, originalRegex, contentStart).parse();
    }

    private static class ExtendedClassParser {
        private List<Token> tokens;
        private int current = 0;
        private String originalRegex;
        private int contentStart;

        ExtendedClassParser(List<Token> tokens, String originalRegex, int contentStart) {
            this.tokens = tokens;
            this.originalRegex = originalRegex;
            this.contentStart = contentStart;
        }

        ExprNode parse() {
            ExprNode expr = parseExpression();
            if (!isAtEnd()) {
                error("Unexpected token: " + peek().value);
            }
            return expr;
        }

        private ExprNode parseExpression() {
            return parseUnion();
        }

        private ExprNode parseUnion() {
            ExprNode expr = parseIntersection();

            while (match("+", "|", "-", "^")) {
                String op = previous().value;
                ExprNode right = parseIntersection();
                expr = new BinaryOpNode(op, expr, right);
            }

            return expr;
        }

        private ExprNode parseIntersection() {
            ExprNode expr = parseUnary();

            while (match("&")) {
                String op = previous().value;
                ExprNode right = parseUnary();
                expr = new BinaryOpNode(op, expr, right);
            }

            return expr;
        }

        private ExprNode parseUnary() {
            if (match("!")) {
                ExprNode operand = parseUnary();
                return new UnaryOpNode("!", operand);
            }

            return parsePrimary();
        }

        private ExprNode parsePrimary() {
            if (match("(")) {
                ExprNode expr = parseExpression();
                consume(")", "Expected ')' after expression");
                return expr;
            }

            if (check(TokenType.CHAR_CLASS)) {
                return new CharClassNode(advance().value);
            }

            error("Expected character class or '('");
            return null; // Never reached
        }

        private boolean match(String... values) {
            for (String value : values) {
                if (check(value)) {
                    advance();
                    return true;
                }
            }
            return false;
        }

        private boolean check(String value) {
            return !isAtEnd() && peek().value.equals(value);
        }

        private boolean check(TokenType type) {
            return !isAtEnd() && peek().type == type;
        }

        private Token advance() {
            if (!isAtEnd()) current++;
            return previous();
        }

        private boolean isAtEnd() {
            return peek().type == TokenType.EOF;
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }

        private void consume(String value, String message) {
            if (check(value)) {
                advance();
                return;
            }
            error(message);
        }

        private void error(String message) {
            Token token = peek();
            RegexPreprocessor.regexError(originalRegex, token.position, message);
        }
    }

    /**
     * Process a character class element from extended syntax
     */
    private static String processCharacterClass(String charClass) {
        // Handle POSIX classes
        if (charClass.startsWith("::") && charClass.endsWith("::")) {
            // Transform ::word:: to [:word:]
            String posixClass = "[" + charClass.substring(1, charClass.length() - 1) + "]";
            String mapped = CharacterClassMapper.getMappedClass(posixClass);
            if (mapped != null) {
                return mapped;
            }
        } else if (charClass.startsWith("[:") && charClass.endsWith(":]")) {
            String mapped = CharacterClassMapper.getMappedClass(charClass);
            if (mapped != null) {
                return mapped;
            }
        }

        // Handle bracketed content
        if (charClass.startsWith("[") && charClass.endsWith("]")) {
            String content = charClass.substring(1, charClass.length() - 1);
            StringBuilder result = new StringBuilder();

            // Process the content - in extended character classes, spaces are ignored (xx mode)
            int i = 0;
            int lastChar = -1;  // Track last character for range validation

            while (i < content.length()) {
                char c = content.charAt(i);

                // Skip whitespace in extended character class (automatic /xx mode)
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }

                // Check for invalid range
                if (c == '-' && lastChar != -1 && i + 1 < content.length()) {
                    char nextChar = content.charAt(i + 1);
                    if (!Character.isWhitespace(nextChar) && nextChar != ']' && nextChar != '\\' && nextChar < lastChar) {
                        throw new IllegalArgumentException(
                            String.format("Invalid [] range \"%c-%c\"", lastChar, nextChar)
                        );
                    }
                }

                if (c == '\\') {
                    // Handle escape sequences
                    if (i + 1 < content.length()) {
                        char next = content.charAt(i + 1);

                        // Skip whitespace after backslash too
                        if (Character.isWhitespace(next)) {
                            // Literal whitespace needs to be escaped, but in extended mode it's ignored
                            i += 2;
                            continue;
                        }

                        if (next == 'p' || next == 'P') {
                            // Unicode property
                            int start = i;
                            if (i + 2 < content.length() && content.charAt(i + 2) == '{') {
                                int end = content.indexOf('}', i + 3);
                                if (end != -1) {
                                    String property = content.substring(i + 3, end);
                                    boolean negated = (next == 'P');
                                    String translated = UnicodeResolver.translateUnicodeProperty(property, negated);

                                    // Unwrap if necessary
                                    if (translated.startsWith("[") && translated.endsWith("]")) {
                                        result.append(translated.substring(1, translated.length() - 1));
                                    } else {
                                        result.append(translated);
                                    }
                                    i = end + 1;
                                    continue;
                                }
                            }
                        } else if (next == 'N' && i + 2 < content.length() && content.charAt(i + 2) == '{') {
                            // Named character
                            int end = content.indexOf('}', i + 3);
                            if (end != -1) {
                                String name = content.substring(i + 3, end).trim();
                                try {
                                    int codePoint = UnicodeResolver.getCodePointFromName(name);
                                    result.append(String.format("\\x{%X}", codePoint));
                                    i = end + 1;
                                    continue;
                                } catch (IllegalArgumentException e) {
                                    // Let it fall through to be handled as regular escape
                                }
                            }
                        } else if ((next == 'b' || next == 'B') && i + 2 < content.length() && content.charAt(i + 2) == '{') {
                            // Boundary assertions not allowed in character class
                            RegexPreprocessor.regexError("", i, "Boundary assertion \\" + next + "{...} not allowed in character class");
                        }
                    }

                    // Regular escape sequence
                    result.append(c);
                    if (i + 1 < content.length()) {
                        result.append(content.charAt(i + 1));
                        i += 2;
                    } else {
                        i++;
                    }
                    lastChar = -1;  // Reset after escape
                } else if (c == '[' && i + 1 < content.length() && content.charAt(i + 1) == ':') {
                    // POSIX class
                    int end = content.indexOf(":]", i + 2);
                    if (end != -1) {
                        String posixClass = content.substring(i, end + 2);
                        String mapped = CharacterClassMapper.getMappedClass(posixClass);
                        if (mapped != null) {
                            // Unwrap the mapped class
                            if (mapped.startsWith("\\p{") && mapped.endsWith("}")) {
                                result.append(mapped);
                            } else if (mapped.startsWith("\\P{") && mapped.endsWith("}")) {
                                result.append(mapped);
                            } else {
                                result.append(mapped);
                            }
                            i = end + 2;
                            continue;
                        }
                    }
                    result.append(c);
                    i++;
                    lastChar = -1;  // Reset after POSIX class
                } else {
                    result.append(c);
                    if (c != '-' && c != '^') {
                        lastChar = c;  // Remember this character
                    }
                    i++;
                }
            }

            return "[" + result.toString() + "]";
        }

        // Should not reach here
        return charClass;
    }

    /**
     * Unwrap outer brackets from a character class if present
     */
    private static String unwrapBrackets(String charClass) {
        if (charClass.startsWith("[") && charClass.endsWith("]")) {
            // But don't unwrap if it's a complex expression with operators
            String inner = charClass.substring(1, charClass.length() - 1);
            if (!inner.contains("&&")) {
                return inner;
            }
        }
        return charClass;
    }

    /**
     * Evaluate the expression tree to produce Java regex syntax
     */
    private static String evaluateExtendedClass(ExprNode tree) {
        return tree.evaluate();
    }
}


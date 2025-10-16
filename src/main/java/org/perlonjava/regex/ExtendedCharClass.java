package org.perlonjava.regex;

import java.util.ArrayList;
import java.util.List;

public class ExtendedCharClass {
    /**
     * Handles Perl's Extended Bracketed Character Class (?[...])
     * Transforms it into Java-compatible character class syntax.
     *
     * @param s          The regex string
     * @param offset     Current position (at '(')
     * @param sb         StringBuilder for output
     * @param regexFlags Current regex flags
     * @return Position after the closing ])
     */
    static int handleExtendedCharacterClass(String s, int offset, StringBuilder sb, RegexFlags regexFlags) {
        int start = offset + 3; // Skip past '(?['

        // First, check if this is an empty extended character class
        int i = start;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }

        if (i < s.length() && s.charAt(i) == ']' && i + 1 < s.length() && s.charAt(i + 1) == ')') {
            // This is an empty extended character class
            RegexPreprocessor.regexError(s, start, "Incomplete expression within '(?[ ])'");
        }

        // Now find the end
        int end = findExtendedClassEnd(s, start);

        if (end == -1) {
            RegexPreprocessor.regexError(s, offset, "Unterminated (?[...]) in regex");
        }

        String content = s.substring(start, end);

        // System.err.println("DEBUG: ExtendedCharClass content: '" + content + "'");

        // Check for empty or whitespace-only content
        if (content.trim().isEmpty()) {
            RegexPreprocessor.regexError(s, start, "Incomplete expression within '(?[ ])'");
        }

        // try {
        // Parse and transform the extended character class
        String transformed = transformExtendedClass(content, s, start);
        sb.append(transformed);

        // Skip past the '])'
        return end + 1;
//        } catch (Exception e) {
//            RegexPreprocessor.regexError(s, start, e.getMessage());
//            return -1; // Never reached due to exception
//        }
    }

    /**
     * Find the end of the extended character class, handling nested brackets
     */
    private static int findExtendedClassEnd(String s, int start) {
        int depth = 1;
        int i = start;
        boolean inEscape = false;

        // System.err.println("DEBUG: findExtendedClassEnd starting at position " + start);
        // System.err.println("DEBUG: Looking at: '" + s.substring(start) + "'");

        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);

            if (inEscape) {
                inEscape = false;
                // Special case: \c consumes two characters (c and the control char)
                if (c == 'c' && i + 1 < s.length()) {
                    i += 2;  // Skip both 'c' and the next character
                    // If the control char is \, we need to skip one more
                    if (i < s.length() && s.charAt(i - 1) == '\\') {
                        i++;  // Skip the character after the backslash
                    }
                } else {
                    i++;
                }
                continue;
            }

            // Check for nested (?[...])
            if (c == '(' && i + 2 < s.length() && s.charAt(i + 1) == '?' && s.charAt(i + 2) == '[') {
                // System.err.println("DEBUG: Found nested (?[ at position " + i);
                // We found a nested extended character class
                // We need to skip over it entirely
                int nestedEnd = findExtendedClassEnd(s, i + 3);
                if (nestedEnd == -1) {
                    // The nested one is unterminated, but we should continue
                    // The error will be caught when that nested one is processed
                    return -1;
                }
                // Skip to after the "])'" of the nested extended class
                i = nestedEnd + 2; // +2 for "])"
                continue;
            }

            switch (c) {
                case '\\':
                    inEscape = true;
                    break;
                case '[':
                    depth++;
                    break;
                case ']':
                    depth--;
                    if (depth == 0) {
                        // Check if this ends the extended character class
                        int j = i + 1;
                        while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                            j++;
                        }
                        if (j < s.length() && s.charAt(j) == ')') {
                            // System.err.println("DEBUG: Found end of extended class at position " + i);
                            return i;
                        }
                        // Not properly terminated
                        return -1;
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
        // Before tokenizing, scan for nested regex constructs and process them
        // This allows us to catch errors in nested patterns
        scanForNestedConstructs(content, originalRegex, contentStart);

        // Tokenize the expression
        List<Token> tokens = tokenizeExtendedClass(content, originalRegex, contentStart);

        // Parse into expression tree
        ExprNode tree = parseExtendedClass(tokens, originalRegex, contentStart);

        // Transform to Java syntax
        return evaluateExtendedClass(tree);
    }

    private static void scanForNestedConstructs(String content, String originalRegex, int contentStart) {
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);

            // Look for (?...) constructs
            if (c == '(' && i + 1 < content.length() && content.charAt(i + 1) == '?') {
                // System.err.println("DEBUG: Found nested (? at position " + i + " in extended char class content");

                // Check what type of construct this is
                if (i + 2 < content.length()) {
                    char nextChar = content.charAt(i + 2);

                    if (nextChar == '[') {
                        // Nested extended character class - process it recursively
                        // System.err.println("DEBUG: Found nested (?[ at position " + i);
                        // Call handleExtendedCharacterClass recursively
                        StringBuilder dummySb = new StringBuilder();
                        ExtendedCharClass.handleExtendedCharacterClass(originalRegex, contentStart + i, dummySb, RegexFlags.fromModifiers("", ""));
                    } else if (nextChar == 'x' && i + 3 < content.length() && content.charAt(i + 3) == ':') {
                        // (?x:...) construct - scan inside it
                        // System.err.println("DEBUG: Found (?x: at position " + i);
                        // Find the matching closing paren
                        int closePos = findMatchingParen(content, i);
                        if (closePos > i + 4) {
                            String innerContent = content.substring(i + 4, closePos);
                            scanForNestedConstructs(innerContent, originalRegex, contentStart + i + 4);
                        }
                    }
                }
            }
            i++;
        }
    }

    private static int findMatchingParen(String s, int start) {
        int depth = 1;
        int i = start + 1;
        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            i++;
        }
        return depth == 0 ? i - 1 : -1;
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
            // In extended character classes, Pattern_White_Space includes NEL (U+0085)
            while (i < content.length()) {
                char ch = content.charAt(i);
                if (Character.isWhitespace(ch) || ch == '\u0085') {  // NEL
                    i++;
                } else {
                    break;
                }
            }

            if (i >= content.length()) break;

            char c = content.charAt(i);
            
            // Handle comments (# to end of line)
            if (c == '#') {
                // Skip to end of line
                while (i < content.length() && content.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

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
                    // Check for inline comment (?#...)
                    if (i + 2 < content.length() && content.charAt(i + 1) == '?' && content.charAt(i + 2) == '#') {
                        // Skip to closing )
                        int closePos = content.indexOf(')', i + 3);
                        if (closePos != -1) {
                            i = closePos + 1;
                        } else {
                            // Unterminated comment, skip to end
                            i = content.length();
                        }
                    } else {
                        tokens.add(new Token(TokenType.LPAREN, "(", tokenStart));
                        i++;
                    }
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
                    // Wrap escape sequences in brackets to make them character class elements
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

        // Check if we have any meaningful tokens
        if (tokens.size() == 1 && tokens.get(0).type == TokenType.EOF) {
            RegexPreprocessor.regexError(originalRegex, contentStart, "Incomplete expression within '(?[ ])'");
        }

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
                    RegexPreprocessor.regexError(originalRegex, classStart + i + 3,
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
            // Unicode property: \p{Name} or \pL (single letter)
            if (start + 2 < content.length() && content.charAt(start + 2) == '{') {
                int end = content.indexOf('}', start + 3);
                return end != -1 ? end + 1 : start + 2;
            }
            // Single-letter property like \pN, \pL
            return start + 3;
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
        } else if (next == 'o') {
            // Octal escape
            if (start + 2 < content.length() && content.charAt(start + 2) == '{') {
                int end = content.indexOf('}', start + 3);
                return end != -1 ? end + 1 : start + 2;
            }
        } else if (next == 'c') {
            // Control character: \cX consumes the backslash, 'c', and the next character
            // Check if we have a character after \c
            if (start + 2 < content.length()) {
                return start + 3;
            }
            // Incomplete \c at end of string
            return start + 2;
        }

        // Default: single character escape
        return start + 2;
    }

    /**
     * Parse tokens into expression tree
     * Precedence: ! > & > (+|-|^|)
     * Binary ops are left-associative, unary is right-associative
     */
    private static ExprNode parseExtendedClass(List<Token> tokens, String originalRegex, int contentStart) {
        return new ExtendedClassParser(tokens, originalRegex, contentStart).parse();
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
        } else if (charClass.startsWith("[:") && charClass.endsWith(":]") && charClass.length() > 4) {
            // Valid POSIX class must have at least one character between [: and :]
            // e.g., [:word:] is valid, but [:] is not
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
                                        result.append(translated, 1, translated.length() - 1);
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

            return "[" + result + "]";
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
                
                // Check if already negated (starts with ^)
                if (operandEval.startsWith("^")) {
                    // Double negation: remove the ^
                    return "[" + operandEval.substring(1) + "]";
                } else {
                    return "[^" + operandEval + "]";
                }
            }

            throw new IllegalStateException("Unknown unary operator: " + operator);
        }
    }

    private static class ExtendedClassParser {
        private final List<Token> tokens;
        private final String originalRegex;
        private final int contentStart;
        private int current = 0;

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
}

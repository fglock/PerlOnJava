package org.perlonjava.operators;

import java.util.ArrayList;
import java.util.List;

public class SprintfFormatParser {

    public static class ParseResult {
        public final List<Object> elements = new ArrayList<>(); // String or FormatSpecifier

        public void addLiteral(String text) {
            if (!text.isEmpty()) {
                elements.add(text);
            }
        }

        public void addSpecifier(FormatSpecifier spec) {
            elements.add(spec);
        }
    }

    public static class FormatSpecifier {
        public int startPos;
        public int endPos;
        public String raw = "";

        // Parsed components
        public Integer parameterIndex;      // null or 1-based index
        public String flags = "";           // combination of -, +, space, #, 0
        public Integer width;               // null if not specified
        public boolean widthFromArg;        // true if width is from argument
        public Integer widthArgIndex;       // parameter index for width (1-based)
        public Integer precision;           // null if not specified
        public boolean precisionFromArg;    // true if precision is from argument
        public Integer precisionArgIndex;   // parameter index for precision (1-based)
        public String lengthModifier;       // h, l, ll, etc.
        public boolean vectorFlag;
        public char conversionChar;
        public boolean isValid = true;
        public String errorMessage;
    }

    public static ParseResult parse(String format) {
        ParseResult result = new ParseResult();
        Parser parser = new Parser(format);

        while (!parser.isAtEnd()) {
            int start = parser.pos;

            if (parser.current() == '%') {
                FormatSpecifier spec = parser.parseSpecifier();
                if (spec != null) {
                    result.addSpecifier(spec);
                } else {
                    // Failed to parse, add % as literal
                    result.addLiteral("%");
                    parser.advance();
                }
            } else {
                // Scan for next % or end
                StringBuilder literal = new StringBuilder();
                while (!parser.isAtEnd() && parser.current() != '%') {
                    literal.append(parser.current());
                    parser.advance();
                }
                result.addLiteral(literal.toString());
            }
        }

        return result;
    }

    private static class Parser {
        private final String input;
        private int pos = 0;

        Parser(String input) {
            this.input = input;
        }

        boolean isAtEnd() {
            return pos >= input.length();
        }

        char current() {
            return isAtEnd() ? '\0' : input.charAt(pos);
        }

        char peek(int ahead) {
            int index = pos + ahead;
            return index >= input.length() ? '\0' : input.charAt(index);
        }

        void advance() {
            if (!isAtEnd()) pos++;
        }

        boolean match(char expected) {
            if (current() == expected) {
                advance();
                return true;
            }
            return false;
        }

        FormatSpecifier parseSpecifier() {
            FormatSpecifier spec = new FormatSpecifier();
            spec.startPos = pos;

            // Must start with %
            if (!match('%')) return null;

            // Handle %%
            if (match('%')) {
                spec.conversionChar = '%';
                spec.endPos = pos;
                spec.raw = "%%";
                return spec;
            }

            // 1. Parse parameter index (e.g., 2$)
            int checkpoint = pos;
            Integer paramIndex = parseNumber();
            if (paramIndex != null && match('$')) {
                spec.parameterIndex = paramIndex;
            } else {
                pos = checkpoint; // Reset
            }

            // 2. Parse flags
            while (!isAtEnd()) {
                char c = current();
                if (c == '-' || c == '+' || c == ' ' || c == '#' || c == '0') {
                    if (spec.flags.indexOf(c) == -1) { // Avoid duplicates
                        spec.flags += c;
                    }
                    advance();
                } else {
                    break;
                }
            }

            // 3. Parse width
            if (match('*')) {
                spec.widthFromArg = true;
                // Check for parameter index
                checkpoint = pos;
                Integer widthParam = parseNumber();
                if (widthParam != null && match('$')) {
                    spec.widthArgIndex = widthParam;
                } else {
                    pos = checkpoint; // Reset
                }
            } else {
                spec.width = parseNumber();
            }

            // 4. Parse precision
            if (match('.')) {
                if (match('*')) {
                    spec.precisionFromArg = true;
                    // Check for parameter index
                    checkpoint = pos;
                    Integer precParam = parseNumber();
                    if (precParam != null && match('$')) {
                        spec.precisionArgIndex = precParam;
                    } else {
                        pos = checkpoint; // Reset
                    }
                } else {
                    Integer prec = parseNumber();
                    spec.precision = (prec != null) ? prec : 0; // . alone means 0
                }
            }

            // 5. Parse length modifier
            if (!isAtEnd()) {
                if (peek(0) == 'h' && peek(1) == 'h') {
                    spec.lengthModifier = "hh";
                    advance(); advance();
                } else if (peek(0) == 'l' && peek(1) == 'l') {
                    spec.lengthModifier = "ll";
                    advance(); advance();
                } else if ("hlLtqzV".indexOf(current()) >= 0) {
                    spec.lengthModifier = String.valueOf(current());
                    advance();
                }
            }

            // 6. Parse vector flag
            if (match('v')) {
                spec.vectorFlag = true;
            }

            // 7. Parse conversion character
            if (isAtEnd()) {
                spec.isValid = false;
                spec.errorMessage = "missing conversion character";
            } else {
                spec.conversionChar = current();
                advance();
            }

            spec.endPos = pos;
            spec.raw = input.substring(spec.startPos, spec.endPos);

            // Validate the specifier
            validateSpecifier(spec);

            return spec;
        }

        Integer parseNumber() {
            int start = pos;
            while (!isAtEnd() && Character.isDigit(current())) {
                advance();
            }
            if (pos > start) {
                return Integer.parseInt(input.substring(start, pos));
            }
            return null;
        }

        void validateSpecifier(FormatSpecifier spec) {
            // Check for invalid conversion characters
            if ("CHIJKLMNPQRSTVWYZhjklmqrtvwyz".indexOf(spec.conversionChar) >= 0) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";
                return;
            }

            // Check for spaces in format (not as flag)
            if (spec.raw.matches("%[^%]*\\s+[^%]*[a-zA-Z]") &&
                    !spec.flags.contains(" ")) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";
                return;
            }

            // Validate length modifier combinations
            if (spec.lengthModifier != null) {
                String combo = spec.lengthModifier + spec.conversionChar;
                if ("hf".equals(combo) || "hg".equals(combo) || "he".equals(combo)) {
                    spec.isValid = false;
                    spec.errorMessage = "INVALID";
                    return;
                }
            }

            // Validate vector flag combinations
            if (spec.vectorFlag && "diouxXbB".indexOf(spec.conversionChar) < 0) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";
                return;
            }
        }
    }
}

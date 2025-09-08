package org.perlonjava.operators;

import java.util.ArrayList;
import java.util.List;

public class SprintfFormatParser {

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
        public boolean invalidDueToSpace = false;
        public String invalidLengthModifierWarning;
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
            // // ADD THIS DEBUG LINE:
            // System.err.println("DEBUG: After flags: pos=" + pos + ", flags='" + spec.flags + "', next char='" + current() + "'");

            // 2.5 Parse vector flag (THIS IS CORRECT POSITION)
            if (!isAtEnd() && current() == 'v') {
                spec.vectorFlag = true;
                advance();
                // // ADD THIS DEBUG LINE:
                // System.err.println("DEBUG: Vector flag found, pos=" + pos + ", next char='" + current() + "'");
            }

            // 3. Parse width - SPECIAL HANDLING FOR VECTOR
            if (spec.vectorFlag && match('*')) {
                // For vector format, * means custom separator, not width!
                spec.widthFromArg = true;  // Repurpose this to mean "custom separator"

                // Now parse the actual width if present
                spec.width = parseNumber();  // This will parse the "2" in %*v2d
            } else if (match('*')) {
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
                // // ADD THIS DEBUG LINE:
                // System.err.println("DEBUG: Width parsed: " + spec.width + ", pos=" + pos + ", next char='" + current() + "'");
            }

            if (!isAtEnd() && current() == 'v') {
                spec.vectorFlag = true;
                advance();
                // System.err.println("DEBUG: Vector flag found after width, pos=" + pos);

                // // ADD THIS: For %*v formats, parse additional width
                if (spec.widthFromArg) {
                    // The * was for separator, now parse the actual width
                    Integer width2 = parseNumber();
                    if (width2 != null) {
                        spec.width = width2;
                        // System.err.println("DEBUG: Parsed width after vector: " + spec.width);
                    }
                }
            }

            // Check for spaces in the format (invalid)
            int savePos = pos;
            boolean hasInvalidSpace = false;

            // Check for space after width
            if (!isAtEnd() && current() == ' ' && peek(1) != '\0') {
                hasInvalidSpace = true;
            }

            // Skip any spaces (they make the format invalid)
            while (!isAtEnd() && current() == ' ') {
                advance();
            }

            // 4. Parse precision
            if (match('.')) {
                // Check for space after dot
                if (!isAtEnd() && current() == ' ') {
                    hasInvalidSpace = true;
                }

                // Skip any spaces
                while (!isAtEnd() && current() == ' ') {
                    advance();
                }

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

                // Check for space after precision
                if (!isAtEnd() && current() == ' ') {
                    hasInvalidSpace = true;
                }
            }

            // Skip any trailing spaces
            while (!isAtEnd() && current() == ' ') {
                advance();
            }

            // 5. Parse length modifier
            if (!isAtEnd()) {
                if (peek(0) == 'h' && peek(1) == 'h') {
                    spec.lengthModifier = "hh";
                    advance();
                    advance();
                } else if (peek(0) == 'l' && peek(1) == 'l') {
                    spec.lengthModifier = "ll";
                    advance();
                    advance();
                } else if ("hlLqzjtV".indexOf(current()) >= 0) {
                    spec.lengthModifier = String.valueOf(current());
                    advance();
                }
            }

            // 7. Parse conversion character
            if (isAtEnd()) {
                spec.isValid = false;
                spec.errorMessage = "MISSING";
            } else {
                spec.conversionChar = current();
                advance();
            }

            spec.endPos = pos;
            spec.raw = input.substring(spec.startPos, spec.endPos);

            // Add debug here to check the state before returning
            System.err.println("DEBUG: Before return - isValid=" + spec.isValid +
                          ", errorMessage='" + spec.errorMessage + "'");

            // Mark as invalid if we found spaces in the format
            if (hasInvalidSpace) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";  // Set errorMessage so warning is generated
            } else {
                // Validate the specifier
                validateSpecifier(spec);
            }

            // Add debug after validation
            System.err.println("DEBUG: After validation - isValid=" + spec.isValid +
                          ", errorMessage='" + spec.errorMessage + "'");

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
                System.err.println("DEBUG validateSpecifier: raw='" + spec.raw +
                    "', vectorFlag=" + spec.vectorFlag +
                    ", conversionChar='" + spec.conversionChar + "'");
            // Special case: %*v formats are valid
            if (spec.vectorFlag && spec.widthFromArg) {
                // This is a valid vector format with custom separator
                // Don't run normal validations
                return;
            }

            // Check if we have no conversion character (e.g., %L, %V, %h, %l, %q, %z, %t)
            if (spec.conversionChar == '\0') {
                spec.isValid = false;
                spec.errorMessage = "INVALID";
                return;
            }

            // Check for vector formats FIRST (before %n check)
            if (spec.vectorFlag) {
                // Vector flag is only valid with certain conversions
                String validVectorConversions = "diouxXbBs";
                System.err.println("DEBUG: Checking vector conversion '" + spec.conversionChar +
                      "' in '" + validVectorConversions + "'");
                if (validVectorConversions.indexOf(spec.conversionChar) < 0) {
                    System.err.println("DEBUG: Setting invalid for vector format");
                    spec.isValid = false;
                    spec.errorMessage = "INVALID";
                    return;
                }
            }

            if ("V".equals(spec.lengthModifier)) {
                // V is silently ignored in Perl
                spec.lengthModifier = null; // Clear it so it's processed as %d
                // Don't set invalidLengthModifierWarning
            }

    // Check for invalid conversion characters
    // Valid conversion characters are: diouxXeEfFgGaAbBcspn%DUO
    String validChars = "diouxXeEfFgGaAbBcspn%DUO";  // REMOVE 'v' from this list
    if (validChars.indexOf(spec.conversionChar) < 0) {
        spec.isValid = false;
        spec.errorMessage = "INVALID";
        return;
    }

            // Special case: uppercase letters that might look like length modifiers
            if (spec.lengthModifier != null) {
                // V is not a valid length modifier
                if (spec.lengthModifier.equals("V")) {
                    spec.isValid = false;
                    spec.errorMessage = "INVALID";
                    return;
                }
            }

            // Special case: standalone %v is invalid (without vector flag)
            if (spec.conversionChar == 'v' && !spec.vectorFlag) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";
                return;
            }

            // Validate %n
            if (spec.conversionChar == 'n') {
                // %n is technically valid but we'll handle it specially in the operator
                return;
            }

            // Validate length modifier combinations
            if (spec.lengthModifier != null) {
                String combo = spec.lengthModifier + spec.conversionChar;
                // h with floating point is invalid
                if ("hf".equals(combo) || "hF".equals(combo) || "hg".equals(combo) ||
                        "hG".equals(combo) || "he".equals(combo) || "hE".equals(combo) ||
                        "ha".equals(combo) || "hA".equals(combo)) {
                    spec.isValid = false;
                    spec.errorMessage = "INVALID";
                    return;
                }
            }

            // Validate flag combinations
            validateFlags(spec);

            // Check for parameter index issues
            if (spec.parameterIndex != null && spec.parameterIndex == 0) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";
                return;
            }

            // Check for invalid width/precision parameter indices
            if ((spec.widthArgIndex != null && spec.widthArgIndex == 0) ||
                    (spec.precisionArgIndex != null && spec.precisionArgIndex == 0)) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";
                return;
            }

            // Additional validation for specific format combinations that tests expect
            // Check for vector formats with spaces (e.g., "%v. 3d" should be invalid)
            if (spec.vectorFlag && spec.raw.contains(" ")) {
                // Check if the raw format has spaces in inappropriate places
                if (spec.raw.matches("%.*v.*\\s+.*[a-zA-Z]")) {
                    spec.isValid = false;
                    spec.errorMessage = "INVALID";
                }
            }

            // Check for %2$vd format (positional parameter with vector)
            if (spec.parameterIndex != null && spec.vectorFlag) {
                // This might need special handling
            }

            // Check for invalid format strings with spaces
            if (spec.raw.contains(" ") && !spec.raw.equals("% d") && !spec.raw.equals("% .0d")) {
                // Some space-containing formats are valid, others aren't
                // This needs careful handling based on the test cases
            }
        }

        void validateFlags(FormatSpecifier spec) {
            // Skip flag validation for vector formats with custom separator
            if (spec.vectorFlag && spec.widthFromArg) {
                return;  // %*v formats are special - don't validate flags
            }

            // + and space flags are ignored for unsigned conversions
            boolean isUnsigned = "uUoOxXbB".indexOf(spec.conversionChar) >= 0;

            // Space flag with certain conversions
            if (spec.flags.contains(" ")) {
                // Space flag is invalid with %c
                if (spec.conversionChar == 'c') {
                    spec.isValid = false;
                    spec.errorMessage = "INVALID";
                }

                // For some formats like "% .*d", it creates specific error patterns
                // This is handled in the operator
            }
        }
    }
}

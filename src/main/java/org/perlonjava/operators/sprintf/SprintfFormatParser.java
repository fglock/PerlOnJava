package org.perlonjava.operators.sprintf;

import java.util.ArrayList;
import java.util.List;

public class SprintfFormatParser {

    public static ParseResult parse(String format) {
        ParseResult result = new ParseResult();
        Parser parser = new Parser(format);

        while (!parser.isAtEnd()) {
            int start = parser.pos;

            if (parser.current() == '%') {
                int savedPos = parser.pos; // Save position before parsing
                FormatSpecifier spec = parser.parseSpecifier();
                if (spec != null) {
                    result.addSpecifier(spec);

                    // Special case: if the format ends with % as conversion char,
                    // check if that % starts another invalid format (for warning only)
                    if (spec.conversionChar == '%' && !spec.isValid && !parser.isAtEnd()) {
                        // Temporarily parse from the second % to check for invalid format
                        int currentPos = parser.pos;
                        parser.pos = savedPos + spec.raw.length() - 1;

                        if (parser.current() == '%') {
                            FormatSpecifier overlapSpec = parser.parseSpecifier();
                            if (overlapSpec != null && !overlapSpec.isValid) {
                                // Mark as overlapping - generate warning but not output
                                overlapSpec.isOverlapping = true;
                                result.addSpecifier(overlapSpec);
                            }
                        }

                        // Restore original position
                        parser.pos = currentPos;
                    }
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


            // 2.5 Parse vector flag if it appears after flags
            boolean vectorParsedWithWidth = false;
            if (!isAtEnd() && current() == 'v') {
                spec.vectorFlag = true;
                advance();

                // After 'v', parse any width specification
                if (!isAtEnd()) {
                    if (current() == '0' && Character.isDigit(peek(1))) {
                        // Zero-padded width like %v02d
                        spec.flags += '0';
                        advance();
                        spec.width = parseNumber();
                        vectorParsedWithWidth = true;
                    } else if (Character.isDigit(current())) {
                        // Regular width like %v3d
                        spec.width = parseNumber();
                        vectorParsedWithWidth = true;
                    }
                }
            }

            // 3. Parse width (skip if already parsed with vector flag)
            if (!vectorParsedWithWidth) {
                if (!spec.vectorFlag && match('*')) {
                    // Could be regular width or %*v format
                    checkpoint = pos;
                    Integer widthParam = parseNumber();
                    if (widthParam != null && match('$')) {
                        // This is %*N$ - positional width parameter
                        spec.widthFromArg = true;
                        spec.widthArgIndex = widthParam;

                        // After %*N$, check if next is 'v' for vector
                        if (!isAtEnd() && current() == 'v') {
                            spec.vectorFlag = true;
                            advance();

                            // For %N$*M$v pattern, the *M$ is the separator source
                            spec.separatorArgIndex = widthParam;
                            // Don't set widthArgIndex here - actual width may come later
                            spec.widthFromArg = true;  // Still indicates custom separator

                            // Parse width after vector flag if present
                            if (Character.isDigit(current())) {
                                spec.width = parseNumber();
                            }
                        }
                    } else {
                        pos = checkpoint; // Reset

                        // Check for %*v format
                        if (!isAtEnd() && current() == 'v') {
                            // This is %*v format - * is for custom separator
                            spec.vectorFlag = true;
                            spec.widthFromArg = true;  // Means "has custom separator"
                            advance(); // consume 'v'

                            // Now check for width after %*v
                            if (match('*')) {
                                // Check for invalid positional after %*v*
                                checkpoint = pos;
                                Integer posParam = parseNumber();

                                if (posParam != null && match('$')) {
                                    // %*v*999$ is valid - positional width for vector format
                                    spec.widthArgIndex = posParam;
                                    // Let parsing continue normally
                                } else if (posParam != null) {
                                    // ADDITIONAL CHANGE: Handle case where we have number but no $
                                    // This is still invalid - don't reset position

                                    // The number is part of the format, continue to find conversion char
                                    // Current position is after the number
                                    if (!isAtEnd()) {
                                        // Skip any backslashes or other characters until we find a letter
                                        while (!isAtEnd() && !Character.isLetter(current())) {
                                            advance();
                                        }
                                        if (!isAtEnd()) {
                                            spec.conversionChar = current();
                                            advance();
                                        }
                                    }

                                    spec.isValid = false;
                                    spec.errorMessage = "INVALID";
                                    spec.endPos = pos;
                                    spec.raw = input.substring(spec.startPos, spec.endPos);
                                    return spec;
                                } else {
                                    pos = checkpoint; // Reset only if no number was found
                                    spec.precisionFromArg = true; // HACK: means second *
                                }
                            } else {
                                spec.width = parseNumber();
                            }
                        } else {
                            // Regular * for width (not %*v)
                            spec.widthFromArg = true;
                        }
                    }
                } else if (spec.vectorFlag && match('*')) {
                    // %v*d format - * is for width
                    spec.widthFromArg = true;
                } else if (!spec.vectorFlag) {
                    // Check for 'v' flag before numeric width
                    if (!isAtEnd() && current() == 'v') {
                        spec.vectorFlag = true;
                        advance();

                        // Parse width after 'v'
                        if (Character.isDigit(current())) {
                            spec.width = parseNumber();
                        }
                    } else {
                        // Regular numeric width
                        spec.width = parseNumber();
                    }
                }
            }

            // Check for spaces in the format (invalid)
            int savePos = pos;
            boolean hasInvalidSpace = !isAtEnd() && current() == ' ' && peek(1) != '\0';

            // Check for space after width

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
                char firstChar = current();

                // Special handling for * after vector format
                if (firstChar == '*' && spec.vectorFlag) {
                    // For vector formats, * might be a width specifier, not conversion char
                    checkpoint = pos;
                    advance(); // consume *
                    Integer widthParam = parseNumber();
                    if (widthParam != null && match('$')) {
                        // This is a positional width like *2$, not a conversion character
                        spec.widthFromArg = true;
                        spec.widthArgIndex = widthParam;

                        // Continue parsing for precision
                        if (match('.')) {
                            if (match('*')) {
                                spec.precisionFromArg = true;
                                Integer precParam = parseNumber();
                                if (precParam != null && match('$')) {
                                    spec.precisionArgIndex = precParam;
                                }
                            } else {
                                Integer prec = parseNumber();
                                spec.precision = (prec != null) ? prec : 0;
                            }
                        }

                        // Now get the actual conversion character
                        if (!isAtEnd()) {
                            spec.conversionChar = current();
                            advance();
                        } else {
                            spec.isValid = false;
                            spec.errorMessage = "MISSING";
                        }
                    } else {
                        // Not a positional width, reset and treat * as conversion char
                        pos = checkpoint;
                        spec.conversionChar = firstChar;
                        advance();
                    }
                } else {
                    // Normal conversion character parsing
                    advance();

                    // Check if this might be part of a positional parameter
                    if (Character.isDigit(firstChar) && !isAtEnd()) {
                        // Look ahead to see if we have more digits and a $
                        int checkPos = pos;
                        while (!isAtEnd() && Character.isDigit(current())) {
                            advance();
                        }

                        if (!isAtEnd() && current() == '$') {
                            // This is an invalid positional parameter at the conversion position
                            advance(); // consume $

                            // Get the actual conversion character
                            if (!isAtEnd()) {
                                spec.conversionChar = current();
                                advance();
                            } else {
                                spec.conversionChar = firstChar; // Use the first digit if nothing follows
                            }

                            spec.isValid = false;
                            spec.errorMessage = "INVALID";
                        } else {
                            // Not a positional parameter, just use the first character
                            pos = checkPos; // Reset to after first char
                            spec.conversionChar = firstChar;
                        }
                    } else {
                        spec.conversionChar = firstChar;
                    }
                }
            }

       spec.endPos = pos;
       spec.raw = input.substring(spec.startPos, spec.endPos);

       // Add this debug line here:

            //

            //

            // Mark as invalid if we found spaces in the format
            if (hasInvalidSpace) {
                spec.isValid = false;
                spec.errorMessage = "INVALID";  // Set errorMessage so warning is generated
            } else {
                // Validate the specifier
                validateSpecifier(spec);
            }

            //

            return spec;
        }

        Integer parseNumber() {
            int start = pos;
            while (!isAtEnd() && Character.isDigit(current())) {
                advance();
            }
            if (pos > start) {
                String numStr = input.substring(start, pos);
                try {
                    return Integer.parseInt(numStr);
                } catch (NumberFormatException e) {
                    // Integer overflow detected
                    // Return a special marker value that will be checked later
                    return Integer.MAX_VALUE;
                }
            }
            return null;
        }

        void validateSpecifier(FormatSpecifier spec) {
            //

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
                String validVectorConversions = "diouxXbB";
                //
                if (validVectorConversions.indexOf(spec.conversionChar) < 0) {
                    //
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
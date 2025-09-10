package org.perlonjava.regex;

import org.perlonjava.runtime.PerlCompilerException;

import static java.lang.Character.isAlphabetic;

/**
 * The RegexPreprocessor class provides functionality to preprocess regular expressions
 * to make them compatible with Java's regex engine. It handles various regex constructs
 * and modifies them as necessary to adhere to Java's regex syntax requirements.
 */
public class RegexPreprocessor {

    // Regex escape rules:
    //
    // \[       as-is
    // \120     becomes: \0120 - Java requires octal sequences to start with zero
    // \0       becomes: \00 - Java requires the extra zero
    // (?#...)  inline comment is removed
    // \N{name}    named Unicode character or character sequence
    // \N{U+263D}  Unicode character
    // \G       \G is removed, it is handled separately
    //
    // inside [ ... ]
    //      space    becomes: "\ " unless the /xx flag is used (flag_xx)
    //      \120     becomes: \0120 - Java requires octal sequences to start with zero
    //      \0       becomes: \00 - Java requires the extra zero
    //      \N{name}    named Unicode character or character sequence
    //      \N{U+263D}  Unicode character
    //      [:ascii:]  becomes: \p{ASCII}
    //      [:^ascii:] becomes: \P{ASCII}
    //      \b       is moved, Java doesn't support \b inside [...]
    //               [xx \b xx]  becomes: (?:[xx xx]|\b) - Java doesn't support \b as a character
    //
    // WIP:
    // named capture (?<one> ... ) replace underscore in name

    static int captureGroupCount;

    /**
     * Preprocesses a given regex string to make it compatible with Java's regex engine.
     * This involves handling various constructs and escape sequences that Java does not
     * natively support or requires in a different format.
     *
     * @param s          The regex string to preprocess.
     * @param regexFlags The regex flags to use.
     * @return A preprocessed regex string compatible with Java's regex engine.
     * @throws PerlCompilerException If there are unmatched parentheses in the regex.
     */
    static String preProcessRegex(String s, RegexFlags regexFlags) {
        captureGroupCount = 0;
        StringBuilder sb = new StringBuilder();
        handleRegex(s, 0, sb, regexFlags, false);
        return sb.toString();
    }

    /**
     * Preprocesses a given regex string to make it compatible with Java's regex engine.
     * This involves handling various constructs and escape sequences that Java does not
     * natively support or requires in a different format.
     *
     * @param s                  The regex string to preprocess.
     * @param offset             The position in the string.
     * @param sb                 The string builder to append the preprocessed regex to.
     * @param regexFlags         The regex flags to use.
     * @param stopAtClosingParen A flag indicating whether to stop processing at the first closing parenthesis.
     * @return The position in the string after the preprocessed regex.
     * @throws PerlCompilerException If there are unmatched parentheses in the regex.
     */
    static int handleRegex(String s, int offset, StringBuilder sb, RegexFlags regexFlags, boolean stopAtClosingParen) {
        final int length = s.length();

        // Remove \G from the pattern string for Java compilation
        if (s.startsWith("\\G", offset)) {
            offset += 2;
        }

        while (offset < length) {
            final int c = s.codePointAt(offset);
            switch (c) {
                case '\\':  // Handle escape sequences
                    offset = RegexPreprocessorHelper.handleEscapeSequences(s, sb, c, offset);
                    break;
                case '[':   // Handle character classes
                    offset = handleCharacterClass(s, regexFlags.isExtendedWhitespace(), sb, c, offset);
                    break;
                case '(':
                    offset = handleParentheses(s, offset, length, sb, c, regexFlags, stopAtClosingParen);
                    break;
                case ')':
                    if (stopAtClosingParen) {
                        return offset;
                    }
                    regexError(s, offset, "Unmatched ) in regex");
                    break;
                case '#':
                    if (regexFlags.isExtended() || regexFlags.isExtendedWhitespace()) {
                        // Consume comment to end of line
                        while (offset < length) {
                            if (s.codePointAt(offset) == '\n') {
                                break;
                            }
                            offset++;
                        }
                        break;
                    } else {
                        sb.append(Character.toChars(c));
                        break;
                    }
                default:    // Append normal characters
                    sb.append(Character.toChars(c));
                    break;
            }
            offset++;
        }

        return offset;
    }

    static void regexError(String s, int offset, String errMsg) {
        if (offset > s.length()) {
            offset = s.length();
        }
        // Remove debug prints for now
        // System.err.println("DEBUG: offset=" + offset + ", s='" + s + "'");
        // System.err.println("DEBUG: marker at: '" + s.substring(0, offset) + "{#}" + s.substring(offset) + "'");

        // The error format expected by tests is:
        // "Error message in regex; marked by <-- HERE in m/regex <-- HERE remaining/"
        throw new PerlCompilerException(errMsg + " in regex; marked by <-- HERE in m/" +
                s.substring(0, offset) + " <-- HERE " + s.substring(offset) + "/");
    }

    private static int handleParentheses(String s, int offset, int length, StringBuilder sb, int c, RegexFlags regexFlags, boolean stopAtClosingParen) {
        // Check for incomplete (?
        if (offset + 1 >= length) {
            regexError(s, offset + 1, "Sequence (? incomplete");
        }

        int c2 = s.codePointAt(offset + 1);

        // Handle (?
        if (c2 == '?') {
            if (offset + 2 >= length) {
                regexError(s, offset + 1, "Sequence (? incomplete");
            }

            int c3 = s.codePointAt(offset + 2);

            // For sequences that need a 4th character
            int c4 = (offset + 3 < length) ? s.codePointAt(offset + 3) : -1;

            if (c3 == '#') {
                // Remove inline comments (?# ... )
                offset = handleSkipComment(offset, s, length);
            } else if (c3 == '@') {
                // Handle (?@...) which is not implemented
                regexError(s, offset + 2, "Sequence (?@...) not implemented");
            } else if (c3 == '{') {
                // Handle (?{ ... }) code blocks
                int braceEnd = findClosingBrace(s, offset + 3, length);
                if (braceEnd == -1) {
                    regexErrorNoPosition(s, "Missing right curly or square bracket");
                }
                // For now, just skip the code block
                sb.append("(?:");  // Convert to non-capturing group
                offset = braceEnd;
            } else if (c3 == '(') {
                // Handle (?(condition)yes|no) conditionals
                return handleConditionalPattern(s, offset, length, sb, regexFlags);
            } else if (c3 == ';') {
                // (?;...) is not recognized
                regexError(s, offset + 2, "Sequence (?;...) not recognized");
            } else if (c3 == '\\') {
                // (?\...) is not recognized
                regexError(s, offset + 2, "Sequence (?\\...) not recognized");
            } else if (c3 == '<' && c4 == '=') {
                // Positive lookbehind (?<=...)
                validateLookbehindLength(s, offset);
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else if (c3 == '<' && c4 == '!') {
                // Negative lookbehind (?<!...)
                validateLookbehindLength(s, offset);
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else if (c3 == '<' && c4 == ';') {
                // (?<;...) invalid group name
                regexError(s, offset + 3, "Group name must start with a non-digit word character");
            } else if (c3 == '<' && isAlphabetic(c4)) {
                // Handle named capture (?<name> ... )
                offset = handleNamedCapture(c3, s, offset, length, sb, regexFlags);
            } else if (c3 == '\'') {
                // Handle named capture (?'name' ... )
                offset = handleNamedCapture(c3, s, offset, length, sb, regexFlags);
            } else if (c3 == '[') {
                // Handle extended bracketed character class (?[...])
                return ExtendedCharClass.handleExtendedCharacterClass(s, offset, sb, regexFlags);
            } else if ((c3 >= 'a' && c3 <= 'z') || c3 == '-' || c3 == '^' || c3 == ':') {
                // Handle (?modifiers: ... ) construct and non-capturing groups
                return RegexPreprocessorHelper.handleFlagModifiers(s, offset, sb, regexFlags);
            } else if (c3 == ':') {
                // Handle non-capturing group (?:...)
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else {
                // Unknown sequence - show the actual character
                String seq = "(?";
                if (offset + 2 < length) {
                    seq += Character.toString((char)s.codePointAt(offset + 2));
                }
                regexError(s, offset + 2, "Sequence " + seq + "...) not recognized");
            }
        } else {
            // Regular parenthesis
            offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
        }

        // Ensure the closing parenthesis is consumed
        if (offset >= length || s.codePointAt(offset) != ')') {
            regexError(s, offset, "Missing right curly or square bracket");
        }
        sb.append(')');
        return offset;
    }

    private static int handleRegularParentheses(String s, int offset, int length, StringBuilder sb, RegexFlags regexFlags) {
        if (regexFlags.isNonCapturing()) {
            // Check if it's already a non-capturing group
            boolean isNonCapturing = offset + 1 < length &&
                    s.charAt(offset + 1) == '?' &&
                    s.charAt(offset + 2) == ':';

            // If not already non-capturing and not a special construct, make it non-capturing
            if (!isNonCapturing && s.charAt(offset + 1) != '?') {
                sb.append("(?:");
            } else {
                sb.append('(');
                if (!isNonCapturing) {
                    captureGroupCount++; // Only increment for capturing groups
                }
            }
        } else {
            sb.append('(');
            captureGroupCount++; // Increment counter for capturing groups
        }

        return handleRegex(s, offset + 1, sb, regexFlags, true);
    }

    private static int handleNamedCapture(int c, String s, int offset, int length, StringBuilder sb, RegexFlags regexFlags) {
        int start = offset + 3; // Skip past '(?<'
        int end = c == '<'
                ? s.indexOf('>', start)
                : s.indexOf('\'', start);
        if (end == -1) {
            regexError(s, offset, "Unterminated named capture in regex");
        }
        String name = s.substring(start, end);
        sb.append("(?<").append(name).append(">");
        captureGroupCount++; // Increment counter for capturing groups
        return handleRegex(s, end + 1, sb, regexFlags, true); // Process content inside the group
    }

    private static int handleCharacterClass(String s, boolean flag_xx, StringBuilder sb, int c, int offset) {
        final int length = s.length();
        int len = sb.length();
        sb.append(Character.toChars(c));  // Append the '['
        offset++;

        // Add check for POSIX syntax at the start of character class
        if (offset < length && s.charAt(offset) == '[') {
            // Check for [[=...=]] or [[.....]]
            if (offset + 1 < length) {
                char nextChar = s.charAt(offset + 1);
                if (nextChar == '=' || nextChar == '.') {
                    // Look for closing syntax
                    int searchPos = offset + 2;
                    while (searchPos < length - 1) {
                        if (s.charAt(searchPos) == nextChar && s.charAt(searchPos + 1) == ']') {
                            // Found complete POSIX syntax
                            String syntaxType = nextChar == '=' ? "[= =]" : "[. .]";
                            regexError(s, searchPos + 2, "POSIX syntax " + syntaxType + " is reserved for future extensions");
                        }
                        searchPos++;
                    }
                }
            }
        }

        StringBuilder rejected = new StringBuilder();
        offset = RegexPreprocessorHelper.handleRegexCharacterClassEscape(offset, s, sb, length, flag_xx, rejected);
        if (!rejected.isEmpty()) {
            // Process \b inside character class
            String subseq;
            if ((sb.length() - len) == 2) {
                subseq = "(?:" + rejected + ")";
            } else {
                subseq = "(?:" + sb.substring(len) + "|" + rejected + ")";
            }
            rejected.setLength(0);
            sb.setLength(len);
            sb.append(subseq);
        }
        return offset;
    }

//    public static void hexPrinter(String emojiString) {
//        // Print each code point as a hex value
//        System.out.println("String: <<" + emojiString + ">>");
//        System.out.println("Hexadecimal representation:");
//        emojiString.codePoints()
//                .forEach(codePoint -> System.out.printf("U+%04X ", codePoint));
//
//        // Optional: Print raw UTF-16 encoding
//        System.out.println("\nUTF-16 Encoding:");
//        for (char c : emojiString.toCharArray()) {
//            System.out.printf("\\u%04X ", (int) c);
//        }
//        System.out.println("\n\n");
//    }

//    private static String generateGraphemeClusterRegex() {
//        return "(?x:                                # Free-spacing mode\n" +
//                "      # Basic grapheme cluster\n" +
//                "      \\P{M}\\p{M}*\n" +
//                "      |\n" +
//
//                "      \\uD83D\\uDC4B\\uD83C\\uDFFB" +  // Special case
//                "      |\n" +
//                "      \\uD83C \\uDDFA \\uD83C \\uDDF8" +  // Special case
//                "      |\n" +
//
//                "      # Regional indicators for flags\n" +
//                "      (?:[\uD83C][\uDDE6-\uDDFF]){2}\n" +
//                "      |\n" +
//                "      # Emoji with modifiers and ZWJ sequences\n" +
//                "      (?:[\uD83C-\uDBFF\uDC00-\uDFFF]|[\u2600-\u27BF])\n" +
//                "      (?:[\uD83C][\uDFFB-\uDFFF])?\n" +
//                "      (?:\u200D\n" +
//                "        (?:[\uD83C-\uDBFF\uDC00-\uDFFF]|[\u2600-\u27BF])\n" +
//                "        (?:[\uD83C][\uDFFB-\uDFFF])?\n" +
//                "      )*\n" +
//                "      (?:[\uFE00-\uFE0F])?\n" +
//                ")";
//    }

    /**
     * Handles predefined character classes within the regex.
     *
     * @param offset The current offset in the regex string.
     * @param s      The regex string.
     * @param sb     The StringBuilder to append processed characters.
     * @param length The length of the regex string.
     * @return The updated offset after processing the character class.
     */
    static int handleCharacterClass(int offset, String s, StringBuilder sb, int length) {
        // Check for POSIX syntax [= =] or [. .]
        if (offset + 3 < length && s.charAt(offset) == '[' &&
            (s.charAt(offset + 1) == '=' || s.charAt(offset + 1) == '.')) {
            char syntaxChar = s.charAt(offset + 1);
            // Look for closing syntax
            int closePos = offset + 2;
            while (closePos < length - 1) {
                if (s.charAt(closePos) == syntaxChar && s.charAt(closePos + 1) == ']') {
                    // Found POSIX syntax
                    String syntaxType = syntaxChar == '=' ? "[= =]" : "[. .]";
                    // Point to after the ']' that closes the POSIX syntax
                    regexError(s, closePos + 2, "POSIX syntax " + syntaxType + " is reserved for future extensions");
                }
                closePos++;
            }
        }
        int endDelimiter = s.indexOf(":]", offset);
        if (endDelimiter > -1) {
            String potentialClass = s.substring(offset, endDelimiter + 2);
            String mapping = CharacterClassMapper.getMappedClass(potentialClass);
            if (mapping != null) {
                sb.append(mapping);
                return endDelimiter + 1;
            }
        }
        sb.append("\\[");
        return offset;
    }

    /**
      * Skips over inline comments within the regex.
      *
      * @param offset The current offset in the regex string.
      * @param s      The regex string.
      * @param length The length of the regex string.
      * @return The updated offset after skipping the comment.
      */
    private static int handleSkipComment(int offset, String s, int length) {
        // comment (?# ... )
        int offset3 = offset;
        while (offset3 < length) {
            final int c3 = s.codePointAt(offset3);
            switch (c3) {
                case ')':
                    return offset3;
                case '\\':
                    offset3++;
                    break;
                default:
                    break;
            }
            offset3++;
        }
        return offset;  // possible error - end of comment not found
    }

    // Lookbehind errors don't show position
    static void regexErrorNoPosition(String s, String errMsg) {
        throw new PerlCompilerException(errMsg + " in regex m/" + s + "/");
    }

    /**
      * Validates that a lookbehind assertion doesn't potentially match more than 255 characters.
      */
    private static void validateLookbehindLength(String s, int offset) {
        int start = offset + 4; // Skip past (?<= or (?<!
        int maxLength = calculateMaxLength(s, start);

        if (maxLength >= 255 || maxLength == -1) { // >= 255 means 255 or more
            regexErrorNoPosition(s, "Lookbehind longer than 255 not implemented");
        }
    }

    /**
      * Calculates the maximum length a pattern can match.
      * Returns -1 if the pattern can match unlimited length.
      */
    private static int calculateMaxLength(String pattern, int start) {
        int pos = start;
        int totalLength = 0;
        int depth = 1; // We're inside the lookbehind parentheses

        while (pos < pattern.length() && depth > 0) {
            char ch = pattern.charAt(pos);

            if (ch == '(') {
                depth++;
                pos++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) break;
                pos++;
            } else if (ch == '\\' && pos + 1 < pattern.length()) {
                // Handle escape sequences
                pos += 2;
                totalLength++;
            } else if (ch == '.') {
                // Check if followed by * or +
                if (pos + 1 < pattern.length()) {
                    char next = pattern.charAt(pos + 1);
                    if (next == '*' || next == '+') {
                        return -1; // Unlimited length
                    }
                }
                totalLength++;
                pos++;
            } else if (ch == '*' || ch == '+') {
                return -1; // Previous element can repeat unlimited times
            } else if (ch == '?') {
                // Handle special case of (? which might be a group
                if (pos + 1 < pattern.length() && pattern.charAt(pos + 1) == '&') {
                    // This is (?&...) which is a subroutine call
                    return -1; // Can match unlimited
                }
                pos++;
            } else if (ch == '{') {
                // Handle {n,m} quantifiers
                int endBrace = pattern.indexOf('}', pos);
                if (endBrace > pos) {
                    String quantifier = pattern.substring(pos + 1, endBrace);
                    int multiplier = parseQuantifierMax(quantifier);
                    if (multiplier == -1) {
                        return -1; // Unlimited
                    }
                    // The quantifier applies to the immediately preceding atom
                    totalLength = totalLength - 1 + multiplier;
                    pos = endBrace + 1;
                } else {
                    totalLength++;
                    pos++;
                }
            } else {
                // Regular character
                totalLength++;
                pos++;
            }
        }

        return totalLength;
    }

    /**
      * Parses a quantifier like "200", "0,255", "1000" and returns the maximum count.
      * Returns -1 if unbounded (e.g., "5,").
      */
    private static int parseQuantifierMax(String quantifier) {
        quantifier = quantifier.trim();
        if (quantifier.contains(",")) {
            String[] parts = quantifier.split(",");
            if (parts.length == 1 || parts[1].trim().isEmpty()) {
                return -1; // {n,} is unbounded
            }
            return Integer.parseInt(parts[1].trim());
        } else {
            return Integer.parseInt(quantifier);
        }
    }

    private static int findClosingBrace(String s, int start, int length) {
        int depth = 1;
        int pos = start;
        while (pos < length && depth > 0) {
            char ch = s.charAt(pos);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return pos;
                }
            } else if (ch == '\\' && pos + 1 < length) {
                pos++; // Skip escaped character
            }
            pos++;
        }
        return -1; // Not found
    }

    // Add this method to find matching parenthesis
    private static int findMatchingParen(String s, int start, int length) {
        int depth = 1;
        int pos = start + 1;  // Start after the opening paren
        while (pos < length && depth > 0) {
            char ch = s.charAt(pos);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return pos;
                }
            } else if (ch == '\\' && pos + 1 < length) {
                pos++; // Skip escaped character
            }
            pos++;
        }
        return -1; // Not found
    }

    // Add this method to handle conditional patterns
    private static int handleConditionalPattern(String s, int offset, int length, StringBuilder sb, RegexFlags regexFlags) {
        // offset is at '(' of (?(condition)yes|no)
        int condStart = offset + 3;  // Skip (?(

        // Find the end of the condition
        int condEnd = condStart;
        int parenDepth = 0;
        boolean foundEnd = false;

        while (condEnd < length && !foundEnd) {
            char ch = s.charAt(condEnd);
            if (ch == '(') {
                parenDepth++;
            } else if (ch == ')' && parenDepth == 0) {
                foundEnd = true;
                break;
            } else if (ch == ')') {
                parenDepth--;
            }
            condEnd++;
        }

        if (!foundEnd) {
            regexError(s, condEnd, "Switch (?(condition)... not terminated");
        }

        // Extract and validate the condition
        String condition = s.substring(condStart, condEnd).trim();

        // Check for invalid conditions
        if (condition.matches("\\d+[a-zA-Z].*")) {
            // Find where the non-digit starts
            int i = 0;
            while (i < condition.length() && Character.isDigit(condition.charAt(i))) {
                i++;
            }
            regexError(s, condStart + i + 1, "Switch condition not recognized");
        }

        // Check for specific invalid patterns
        if (condition.equals("??{}") || condition.equals("?[")) {
            regexError(s, condStart, "Unknown switch condition (?(...))");
        }

        if (condition.startsWith("?")) {
            regexError(s, condStart, "Unknown switch condition (?(...))");
        }

        // Check for non-numeric conditions that aren't valid
        if (!condition.matches("\\d+") && !condition.matches("<[^>]+>") && !condition.matches("'[^']+'")) {
            regexError(s, condStart, "Unknown switch condition (?(...))");
        }

        // Now parse the yes|no branches
        int pos = condEnd + 1;  // Skip past the closing )
        int pipeCount = 0;
        int branchStart = pos;
        parenDepth = 0;

        while (pos < length) {
            char ch = s.charAt(pos);
            if (ch == '(') {
                parenDepth++;
            } else if (ch == ')' && parenDepth == 0) {
                // End of conditional
                break;
            } else if (ch == ')') {
                parenDepth--;
            } else if (ch == '|' && parenDepth == 0) {
                pipeCount++;
                if (pipeCount > 1) {
                    regexError(s, pos, "Switch (?(condition)... contains too many branches");
                }
            }
            pos++;
        }

        if (pos >= length) {
            regexError(s, pos, "Switch (?(condition)... not terminated");
        }

        // For now, just convert to a non-capturing group
        sb.append("(?:");
        return handleRegex(s, branchStart, sb, regexFlags, true);
    }
}

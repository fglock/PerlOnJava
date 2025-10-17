package org.perlonjava.regex;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.PerlJavaUnimplementedException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        s = convertPythonStyleGroups(s);
        s = transformSimpleConditionals(s);
        s = removeUnderscoresFromEscapes(s);
        s = normalizeQuantifiers(s);
        StringBuilder sb = new StringBuilder();
        handleRegex(s, 0, sb, regexFlags, false);
        String result = sb.toString();
        return result;
    }

    /**
     * Remove underscores from \x{...} and \o{...} escape sequences.
     * Perl allows underscores in numeric literals for readability, but Java doesn't.
     * Example: \x{_1_0000} becomes \x{10000}
     */
    private static String removeUnderscoresFromEscapes(String pattern) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = pattern.length();

        while (i < len) {
            if (i + 2 < len && pattern.charAt(i) == '\\' &&
                    (pattern.charAt(i + 1) == 'x' || pattern.charAt(i + 1) == 'o') &&
                    pattern.charAt(i + 2) == '{') {
                // Found \x{ or \o{
                result.append(pattern.charAt(i));   // \
                result.append(pattern.charAt(i + 1)); // x or o
                result.append('{');
                i += 3;

                // Copy content until }, removing underscores
                while (i < len && pattern.charAt(i) != '}') {
                    char ch = pattern.charAt(i);
                    if (ch != '_') {
                        result.append(ch);
                    }
                    i++;
                }

                if (i < len) {
                    result.append('}'); // Closing brace
                    i++;
                }
            } else {
                result.append(pattern.charAt(i));
                i++;
            }
        }

        return result.toString();
    }

    /**
     * Normalize quantifiers to Java regex format.
     * Perl allows {,n} to mean {0,n} (omitting minimum).
     * Perl allows spaces inside quantifiers like {2, 5} or { , 2 }.
     * Java requires explicit {0,n} and no spaces (unless in /x mode).
     * Example: {,2} becomes {0,2}, { , 2 } becomes {0,2}
     */
    private static String normalizeQuantifiers(String pattern) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = pattern.length();
        boolean inCharClass = false;
        boolean escaped = false;

        while (i < len) {
            char ch = pattern.charAt(i);

            if (escaped) {
                result.append(ch);
                escaped = false;
                i++;
                continue;
            }

            if (ch == '\\') {
                result.append(ch);
                escaped = true;
                i++;
                continue;
            }

            if (ch == '[') {
                inCharClass = true;
                result.append(ch);
                i++;
                continue;
            }

            if (ch == ']' && inCharClass) {
                inCharClass = false;
                result.append(ch);
                i++;
                continue;
            }

            if (ch == '{' && !inCharClass) {
                // Potential quantifier
                int start = i;
                i++;
                StringBuilder quantifier = new StringBuilder();

                // Read the quantifier content
                while (i < len && pattern.charAt(i) != '}') {
                    quantifier.append(pattern.charAt(i));
                    i++;
                }

                if (i < len && pattern.charAt(i) == '}') {
                    // We have a complete quantifier
                    String content = quantifier.toString().trim();

                    // Check if it's a {,n} pattern or has spaces
                    if (content.matches("\\s*,\\s*\\d+\\s*")) {
                        // {,n} pattern - add 0 at the start
                        content = content.replaceAll("\\s*,\\s*(\\d+)\\s*", "0,$1");
                        result.append('{').append(content).append('}');
                        i++;
                    } else if (content.contains(",") && content.matches(".*\\s+.*")) {
                        // Has comma and spaces - remove spaces
                        content = content.replaceAll("\\s+", "");
                        result.append('{').append(content).append('}');
                        i++;
                    } else {
                        // Normal quantifier, keep as-is
                        result.append('{').append(quantifier).append('}');
                        i++;
                    }
                } else {
                    // Unclosed {, not a quantifier - keep as-is
                    result.append('{').append(quantifier);
                }
            } else {
                result.append(ch);
                i++;
            }
        }

        return result.toString();
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
        boolean lastWasQuantifiable = false; // Track if the last thing can be quantified

        // Remove \G from the pattern string for Java compilation
        if (s.startsWith("\\G", offset)) {
            offset += 2;
        }

        while (offset < length) {
            final int c = s.codePointAt(offset);
            boolean isQuantifier = false;

            switch (c) {
                case '*':
                case '+':
                case '?':
                    // Check if this is at the start or after certain characters
                    if (offset == 0 || sb.length() == 0) {
                        regexError(s, offset + 1, "Quantifier follows nothing");
                    }

                    // Check what the last character was
                    if (sb.length() > 0) {
                        char lastChar = sb.charAt(sb.length() - 1);
                        // Check if quantifier follows |
                        if (lastChar == '|') {
                            regexError(s, offset + 1, "Quantifier follows nothing");
                        }
                    }

                    // Check if this might be a possessive quantifier
                    boolean isPossessive = offset + 1 < length && s.charAt(offset + 1) == '+';

                    // Check if this might be a non-greedy quantifier
                    boolean isNonGreedy = offset + 1 < length && s.charAt(offset + 1) == '?';

                    // Check what the last character was
                    if (sb.length() > 0) {
                        char lastChar = sb.charAt(sb.length() - 1);
                        // Check if quantifier follows |
                        if (lastChar == '|') {
                            regexError(s, offset + 1, "Quantifier follows nothing");
                        }
                    }

                    // Check for nested quantifiers (but not possessive or non-greedy)
                    if (!isPossessive && !isNonGreedy && sb.length() > 0) {
                        char lastChar = sb.charAt(sb.length() - 1);
                        // Also check if the previous character was a backslash (escaped)
                        boolean isEscaped = sb.length() >= 2 && sb.charAt(sb.length() - 2) == '\\';
                        if (!isEscaped && (lastChar == '*' || lastChar == '+' || lastChar == '?')) {
                            regexError(s, offset + 1, "Nested quantifiers");
                        }
                    }

                    sb.append(Character.toChars(c));

                    // If possessive or non-greedy, also append the following character
                    if (isPossessive) {
                        sb.append('+');
                        offset++; // Skip the extra +
                        lastWasQuantifiable = false; // Can't quantify a possessive quantifier
                    } else if (isNonGreedy) {
                        sb.append('?');
                        offset++; // Skip the extra ?
                        lastWasQuantifiable = false; // Can't quantify a non-greedy quantifier
                    } else {
                        lastWasQuantifiable = false; // Regular quantifier
                    }
                    break;

                case '\\':  // Handle escape sequences
                    offset = RegexPreprocessorHelper.handleEscapeSequences(s, sb, c, offset);
                    lastWasQuantifiable = true;
                    break;

                case '[':   // Handle character classes
                    offset = handleCharacterClass(s, regexFlags.isExtendedWhitespace(), sb, c, offset);
                    break;

                case '(':
                    offset = handleParentheses(s, offset, length, sb, c, regexFlags, stopAtClosingParen);
                    lastWasQuantifiable = true;
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
                        lastWasQuantifiable = true;
                        break;
                    }

                case '{':
                    // Only treat '{' as a quantifier if the previous item is quantifiable.
                    // Otherwise, '{' must be a literal (Perl allows literal braces).
                    if (!lastWasQuantifiable) {
                        // Literal '{' (not a quantifier), append as-is
                        sb.append(Character.toChars(c));
                        lastWasQuantifiable = true;
                    } else {
                        // Parse as quantifier
                        offset = handleQuantifier(s, offset, sb);

                        // Check for possessive quantifier {n,m}+
                        if (offset + 1 < length && s.charAt(offset + 1) == '+') {
                            sb.append('+');
                            offset++; // Skip the extra +
                        }
                        // Check for non-greedy quantifier {n,m}?
                        else if (offset + 1 < length && s.charAt(offset + 1) == '?') {
                            sb.append('?');
                            offset++; // Skip the extra ?
                        }

                        isQuantifier = true;
                        lastWasQuantifiable = false; // Can't quantify a quantifier
                    }
                    break;

                case '^':
                    sb.append(Character.toChars(c));
                    lastWasQuantifiable = false; // Can't quantify anchors
                    break;

                case '|':
                    sb.append(Character.toChars(c));
                    lastWasQuantifiable = false; // Next item starts fresh
                    break;

                default:    // Append normal characters
                    sb.append(Character.toChars(c));
                    lastWasQuantifiable = true;
                    break;
            }

            // Check for nested quantifiers
            if (isQuantifier && offset + 1 < length) {
                char nextChar = s.charAt(offset + 1);
                // Don't flag *+, ++, ?+ as nested quantifiers (they're possessive)
                // Don't flag *?, +?, ??, }? as nested quantifiers (they're non-greedy)
                boolean isModifier = (nextChar == '+' || nextChar == '?');
                if (!isModifier && (nextChar == '*' || nextChar == '+' || nextChar == '?' || nextChar == '{')) {
                    regexError(s, offset + 2, "Nested quantifiers");
                }
            }

            offset++;
        }

        return offset;
    }

    static void regexError(String s, int offset, String errMsg) {
        if (offset > s.length()) {
            offset = s.length();
        }


        // "Error message in regex; marked by <-- HERE in m/regex <-- HERE remaining/"
        String before = s.substring(0, offset);
        String after = s.substring(offset);

        // When marker is at the end, no space before <-- HERE
        String marker = after.isEmpty() ? " <-- HERE" : " <-- HERE ";

        throw new PerlCompilerException(errMsg + " in regex; marked by <-- HERE in m/" +
                before + marker + after + "/");
    }

    private static int handleParentheses(String s, int offset, int length, StringBuilder sb, int c, RegexFlags regexFlags, boolean stopAtClosingParen) {
        // Check for incomplete (?
        if (offset + 1 >= length) {
            regexError(s, offset + 1, "Sequence (? incomplete");
        }

        int c2 = s.codePointAt(offset + 1);

        // Check for (*...) verb patterns FIRST, before checking (?
        if (c2 == '*') {
            // (*...) control verbs like (*ACCEPT), (*FAIL), (*COMMIT), etc.
            // These are Perl-specific and not supported by Java regex

            // Find the end of the verb
            int verbEnd = offset + 2;
            while (verbEnd < length && s.codePointAt(verbEnd) != ')') {
                verbEnd++;
            }
            if (verbEnd < length) {
                verbEnd++; // Include the closing paren
            }

            // Extract the verb name for error reporting
            String verb = s.substring(offset, Math.min(verbEnd, length));

            // Replace with empty non-capturing group as placeholder
            sb.append("(?:)");

            // Throw error that can be caught by JPERL_UNIMPLEMENTED=warn
            regexError(s, offset + 2, "Regex control verb " + verb + " not implemented");

            return verbEnd; // Skip past the entire verb construct
        }

        // Handle (?
        if (c2 == '?') {
            if (offset + 2 >= length) {
                // Marker should be after the ?
                regexError(s, offset + 2, "Sequence (? incomplete");
            }

            int c3 = s.codePointAt(offset + 2);

            // For sequences that need a 4th character
            int c4 = (offset + 3 < length) ? s.codePointAt(offset + 3) : -1;

            if (c3 == '#') {
                // Remove inline comments (?# ... )
                offset = handleSkipComment(offset, s, length);
                // Comment is completely removed - don't append anything, just return
                return offset; // offset points to ')', caller will increment past it
            } else if (c3 == '@') {
                // Handle (?@...) which is not implemented
                regexError(s, offset + 3, "Sequence (?@...) not implemented");
            } else if (c3 == '{') {
                // Check if this is our special unimplemented marker
                if (s.startsWith("(?{UNIMPLEMENTED_CODE_BLOCK})", offset)) {
                    regexUnimplemented(s, offset + 2, "(?{...}) code blocks in regex not implemented");
                }
                // Handle (?{ ... }) code blocks - try constant folding
                offset = handleCodeBlock(s, offset, length, sb, regexFlags);
            } else if (c3 == '?' && c4 == '{') {
                // Check if this is our special unimplemented recursive pattern marker
                if (s.startsWith("(??{UNIMPLEMENTED_RECURSIVE_PATTERN})", offset)) {
                    regexError(s, offset + 2, "(??{...}) recursive regex patterns not implemented");
                }
                // Handle (??{ ... }) recursive/dynamic regex patterns
                // These insert a regex pattern at runtime based on code execution
                // For now, replace with a placeholder that will be caught later
                sb.append("(?:");  // Non-capturing group as placeholder

                // Skip the (??{ part
                offset += 4;

                // Find the closing }
                int braceCount = 1;
                while (offset < length && braceCount > 0) {
                    int ch = s.codePointAt(offset);
                    if (ch == '{') {
                        braceCount++;
                    } else if (ch == '}') {
                        braceCount--;
                    }
                    offset++;
                }

                // Throw error that can be caught by JPERL_UNIMPLEMENTED=warn
                regexError(s, offset - 1, "(??{...}) recursive regex patterns not implemented");
            } else if (c3 == '(') {
                // Handle (?(condition)yes|no) conditionals
                // handleConditionalPattern processes the entire conditional including its closing )
                // so we need to return directly without further processing
                return handleConditionalPattern(s, offset, length, sb, regexFlags);
            } else if (c3 == ';') {
                // (?;...) is not recognized - marker should be after ;
                regexError(s, offset + 3, "Sequence (?;...) not recognized");
            } else if (c3 == '\\') {
                // (?\...) is not recognized - marker should be after \
                regexError(s, offset + 3, "Sequence (?\\...) not recognized");
            } else if (c3 == '<' && c4 == '=') {
                // Positive lookbehind (?<=...)
                validateLookbehindLength(s, offset);
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else if (c3 == '<' && c4 == '!') {
                // Negative lookbehind (?<!...)
                validateLookbehindLength(s, offset);
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else if (c3 == '<' && isAlphabetic(c4)) {
                // Handle named capture (?<name> ... )
                offset = handleNamedCapture(c3, s, offset, length, sb, regexFlags);
            } else if (c3 == '<') {
                // Invalid character after (?<
                if (offset + 3 < length) {
                    // For (?<;x, the marker should be after the invalid character (the ;)
                    regexError(s, offset + 4, "Group name must start with a non-digit word character");
                } else {
                    // Pattern ends after (?<
                    regexError(s, offset + 3, "Sequence (?<... not terminated");
                }
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
            } else if (c3 == '=') {
                // Positive lookahead (?=...)
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else if (c3 == '!') {
                // Negative lookahead (?!...)
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else if (c3 == '>') {
                // Atomic group (?>...) - non-backtracking group
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            } else if (c3 == '|') {
                // Handle (?|...) branch reset groups
                offset = handleBranchReset(s, offset, length, sb, regexFlags);
            } else if (Character.isDigit(c3)) {
                // Recursive subpattern reference (?1), (?2), etc.
                // These refer to the subpattern with that number and are recursive
                regexError(s, offset + 2, "Sequence (?" + ((char) c3) + "...) not recognized");
            } else {
                // Unknown sequence - show the actual character
                String seq = "(?";
                if (offset + 2 < length) {
                    seq += Character.toString((char) s.codePointAt(offset + 2));
                }
                regexError(s, offset + 2, "Sequence " + seq + "...) not recognized");
            }
        } else {
            // Regular parenthesis
            offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
        }

        // Ensure the closing parenthesis is consumed
        if (offset >= length || s.codePointAt(offset) != ')') {
            // Change this error message based on what we're looking for
            regexError(s, offset - 1, "Unmatched (");
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

    /**
     * Handles branch reset groups (?|alt1|alt2|alt3)
     * <p>
     * Branch reset groups reset capture group numbering for each alternative.
     * In Perl, (?|(a)|(b)) means both alternatives capture to $1.
     * <p>
     * Phase 1 Implementation: Converts to non-capturing group with alternatives.
     * This allows compilation and works for same-structure alternatives.
     * Full runtime remapping would be needed for perfect Perl emulation.
     *
     * @param s          The regex string
     * @param offset     Current position (at '(' of '(?|')
     * @param length     Length of regex string
     * @param sb         StringBuilder for output
     * @param regexFlags Regex flags
     * @return New offset after processing the branch reset group
     */
    private static int handleBranchReset(String s, int offset, int length, StringBuilder sb, RegexFlags regexFlags) {
        // Save the starting group count
        int startGroupCount = captureGroupCount;

        // Skip past '(?|'
        offset += 3;

        // First pass: collect raw alternative strings
        java.util.List<String> rawAlternatives = new java.util.ArrayList<>();
        StringBuilder altSb = new StringBuilder();
        int parenDepth = 1; // We're inside the (?| already
        boolean inEscape = false;
        boolean inCharClass = false;

        while (offset < length && parenDepth > 0) {
            char c = s.charAt(offset);

            if (inEscape) {
                altSb.append(c);
                inEscape = false;
                offset++;
                continue;
            }

            if (c == '\\') {
                altSb.append(c);
                inEscape = true;
                offset++;
                continue;
            }

            if (inCharClass) {
                altSb.append(c);
                if (c == ']') {
                    inCharClass = false;
                }
                offset++;
                continue;
            }

            if (c == '[') {
                altSb.append(c);
                inCharClass = true;
                offset++;
                continue;
            }

            if (c == '(') {
                parenDepth++;
                altSb.append(c);
                offset++;
                continue;
            }

            if (c == ')') {
                parenDepth--;
                if (parenDepth == 0) {
                    // End of branch reset group - save last alternative
                    rawAlternatives.add(altSb.toString());
                    break;
                }
                altSb.append(c);
                offset++;
                continue;
            }

            if (c == '|' && parenDepth == 1) {
                // End of this alternative, start of next
                rawAlternatives.add(altSb.toString());
                altSb = new StringBuilder();
                offset++;
                continue;
            }

            // Regular character
            altSb.append(c);
            offset++;
        }

        if (parenDepth != 0) {
            regexError(s, offset, "Unmatched ( in branch reset group");
        }

        // Second pass: process each alternative and track capture counts
        java.util.List<String> processedAlternatives = new java.util.ArrayList<>();
        java.util.List<Integer> captureCounts = new java.util.ArrayList<>();

        for (String rawAlt : rawAlternatives) {
            // Reset capture count for this alternative
            captureGroupCount = startGroupCount;

            // Process this alternative through handleRegex
            StringBuilder processedAlt = new StringBuilder();
            handleRegex(rawAlt, 0, processedAlt, regexFlags, false);

            processedAlternatives.add(processedAlt.toString());
            captureCounts.add(captureGroupCount - startGroupCount);
        }

        // Find the maximum capture count across all alternatives
        int maxCaptures = 0;
        for (int count : captureCounts) {
            if (count > maxCaptures) {
                maxCaptures = count;
            }
        }

        // Set the final capture group count to start + max captures
        captureGroupCount = startGroupCount + maxCaptures;

        // Build the output: (?:alt1|alt2|alt3)
        // This is a non-capturing wrapper with all alternatives
        // Note: We don't append the closing ')' here - the caller (handleParentheses) will do that
        sb.append("(?:");
        for (int i = 0; i < processedAlternatives.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(processedAlternatives.get(i));
        }

        return offset;
    }

    private static int handleCharacterClass(String s, boolean flag_xx, StringBuilder sb, int c, int offset) {
        final int length = s.length();
        int len = sb.length();
        sb.append(Character.toChars(c));  // Append the '['
        offset++;


        // Check if the bracket is properly closed
        int bracketEnd = offset;
        int depth = 1;
        boolean inEscape = false;

        while (bracketEnd < length && depth > 0) {
            char ch = s.charAt(bracketEnd);
            if (inEscape) {
                inEscape = false;
            } else if (ch == '\\') {
                inEscape = true;
            } else if (ch == '[') {
                // Don't increment depth - [ is just a literal inside a character class
            } else if (ch == ']') {
                depth--;
            }
            bracketEnd++;
        }

        if (depth > 0) {
            regexError(s, offset, "Unmatched [");
        }

        // Check for POSIX syntax at the start of character class
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
    static void regexErrorNoPosition(String errMsg) {
        throw new PerlCompilerException(errMsg);
    }

    /**
     * Validates that a lookbehind assertion doesn't potentially match more than 255 characters.
     */
    private static void validateLookbehindLength(String s, int offset) {
        // System.err.println("DEBUG: validateLookbehindLength called with string length " + s.length());
        // System.err.println("DEBUG: String codepoints: ");
        // s.codePoints().forEach(cp -> System.err.printf("U+%04X ", cp));
        // System.err.println();

        int start = offset + 4; // Skip past (?<= or (?<!
        int maxLength = calculateMaxLength(s, start);

        if (maxLength >= 255 || maxLength == -1) { // >= 255 means 255 or more
            regexErrorSimple(s, "Lookbehind longer than 255 not implemented");
        }
    }

    static void regexErrorSimple(String s, String errMsg) {
        throw new PerlCompilerException(errMsg + " in regex m/" + s + "/");
    }

    /**
     * Throws PerlJavaUnimplementedException with proper error formatting including marker and context.
     * Used for regex features that are valid Perl but not yet implemented in PerlOnJava.
     */
    static void regexUnimplemented(String s, int offset, String errMsg) {
        if (offset > s.length()) {
            offset = s.length();
        }

        // "Error message in regex; marked by <-- HERE in m/regex <-- HERE remaining/"
        String before = s.substring(0, offset);
        String after = s.substring(offset);

        // When marker is at the end, no space before <-- HERE
        String marker = after.isEmpty() ? " <-- HERE" : " <-- HERE ";

        throw new PerlJavaUnimplementedException(errMsg + " in regex; marked by <-- HERE in m/" +
                before + marker + after + "/");
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

    // Find matching parenthesis
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

    // Handle conditional patterns
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
            // Error should point to where we expected the closing paren
            regexError(s, condEnd, "Switch (?(condition)... not terminated");
        }

        // Extract and validate the condition
        String condition = s.substring(condStart, condEnd).trim();

        // System.err.println("DEBUG: Conditional pattern condition: '" + condition + "'");

        // Check for invalid conditions like "1x" or "1x(?#)"
        if (condition.matches("\\d+[a-zA-Z].*")) {
            // Find where the alphabetic part starts
            int i = 0;
            while (i < condition.length() && Character.isDigit(condition.charAt(i))) {
                i++;
            }
            // For "1x(?#)", we want the marker after "x", not after the comment
            // So we need to find where the alphabetic part ends
            int alphaEnd = i;
            while (alphaEnd < condition.length() && Character.isLetter(condition.charAt(alphaEnd))) {
                alphaEnd++;
            }
            // Error should point after the alphanumeric part
            regexError(s, condStart + alphaEnd, "Switch condition not recognized");
        }

        // Check for specific invalid patterns
        if (condition.equals("??{}") || condition.equals("?[")) {
            // Marker should be after the first ?
            regexUnimplemented(s, condStart + 1, "Unknown switch condition (?(...))");
        }

        if (condition.startsWith("?")) {
            // Marker should be after the first ?
            regexUnimplemented(s, condStart + 1, "Unknown switch condition (?(...))");
        }

        // Check for non-numeric conditions that aren't valid
        if (!condition.matches("\\d+") && !condition.matches("<[^>]+>") && !condition.matches("'[^']+'")) {
            // For single character conditions like "x", marker should be after the character
            if (condition.length() == 1) {
                regexUnimplemented(s, condStart + 1, "Unknown switch condition (?(...))");
            } else {
                regexUnimplemented(s, condStart, "Unknown switch condition (?(...))");
            }
        }

        // Now parse the yes|no branches
        int pos = condEnd + 1;  // Skip past the closing )
        int pipeCount = 0;
        int branchStart = pos;
        int pipePos = -1;
        parenDepth = 0;

        // First, check if we have any content after the condition
        if (pos >= length) {
            // No branches at all - for /(?(1)/ the marker should be right after the )
            // condEnd is at the position of ), so condEnd + 1 is after it
            regexError(s, condEnd + 1, "Switch (?(condition)... not terminated");
        }

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
                if (pipeCount == 1) {
                    pipePos = pos;
                }
                if (pipeCount > 1) {
                    // Mark the error right after this pipe character
                    regexError(s, pos + 1, "Switch (?(condition)... contains too many branches");
                }
            } else if (ch == '\\' && pos + 1 < length) {
                pos++; // Skip escaped character
            }
            pos++;
        }

        if (pos >= length || s.charAt(pos) != ')') {
            // The pattern ends without closing the conditional
            regexError(s, pos, "Switch (?(condition)... not terminated");
        }

        // Conditional patterns are not supported by Java regex
        // (?(1)yes|no) means: if group 1 matched, use 'yes' branch, else use 'no' branch
        // This is fundamentally different from alternation and cannot be converted

        // Simple cases are transformed in transformSimpleConditionals()
        // If we reach here, it's a complex case that couldn't be transformed

        // Use regexUnimplemented so it can be caught with JPERL_UNIMPLEMENTED=warn
        // Append a placeholder that won't match anything
        sb.append("(?!)"); // Negative lookahead that always fails

        regexUnimplemented(s, condStart - 1, "Conditional patterns (?(...)...) not implemented");

        return pos + 1; // Skip past the closing ) of the conditional
    }

    private static int handleQuantifier(String s, int offset, StringBuilder sb) {
        int start = offset; // Position of '{'
        int end = s.indexOf('}', start);

        if (end == -1) {
            // Let it through - Java will handle the error
            sb.append('{');
            return offset;
        }

        String quantifier = s.substring(start + 1, end);
        String[] parts = quantifier.split(",", -1);

        try {
            // Check first number
            if (!parts[0].isEmpty()) {
                // Check for leading zeros
                if (parts[0].length() > 1 && parts[0].charAt(0) == '0') {
                    regexError(s, start + 1, "Invalid quantifier in {,}");
                }

                long n = Long.parseLong(parts[0]);
                if (n > Integer.MAX_VALUE) {
                    regexError(s, start + 1, "Quantifier in {,} bigger than " + Integer.MAX_VALUE);
                }
            }

            // Check second number if present
            if (parts.length > 1 && !parts[1].isEmpty()) {
                // Check for leading zeros
                if (parts[1].length() > 1 && parts[1].charAt(0) == '0') {
                    int commaPos = quantifier.indexOf(',');
                    regexError(s, start + 1 + commaPos + 1, "Invalid quantifier in {,}");
                }

                long m = Long.parseLong(parts[1]);
                if (m > Integer.MAX_VALUE) {
                    int commaPos = quantifier.indexOf(',');
                    regexError(s, start + 1 + commaPos, "Quantifier in {,} bigger than " + Integer.MAX_VALUE);
                }
            }
        } catch (NumberFormatException e) {
            // Not a valid quantifier, let it through
        }

        // If we get here, it's valid
        sb.append(s, start, end + 1);
        return end;
    }


    private static String convertPythonStyleGroups(String pattern) {
        // Convert (?P<name>...) to (?<name>...)
        pattern = pattern.replaceAll("\\(\\?P<", "(?<");

        // Convert (?P=name) to \k<name>
        StringBuffer result = new StringBuffer();
        Matcher m = Pattern.compile("\\(\\?P=([^)]+)\\)").matcher(pattern);
        while (m.find()) {
            m.appendReplacement(result, "\\\\k<" + m.group(1) + ">");
        }
        m.appendTail(result);
        return result.toString();
    }

    /**
     * Transform simple conditional patterns (?(N)yes|no) that can be converted to alternations.
     * <p>
     * Phase 1 implementation handles the common pattern: (group)?(?(N)yes|no)
     * Transforms to: (?:(group)yes|no)
     * <p>
     * This works because:
     * - If group matches: first alternative (group)yes is tried
     * - If group doesn't match: second alternative no is tried
     *
     * @param pattern The regex pattern
     * @return Transformed pattern with simple conditionals converted to alternations
     */
    private static String transformSimpleConditionals(String pattern) {
        // For now, we'll handle the simplest case: (?)?(?(1)yes|no) or (?)?(?(1)yes)
        // More complex transformations can be added later

        // Pattern to match: (capture)? followed by (?(N)yes|no) or (?(N)yes)
        // We need to be careful about nested parentheses and escapes

        StringBuilder result = new StringBuilder();
        int pos = 0;
        int len = pattern.length();

        while (pos < len) {
            // Look for (?(digit)
            int condStart = pattern.indexOf("(?(", pos);
            if (condStart == -1) {
                // No more conditionals
                result.append(pattern.substring(pos));
                break;
            }

            // Check if next char after (?( is a digit
            if (condStart + 3 >= len || !Character.isDigit(pattern.charAt(condStart + 3))) {
                // Not a simple numeric conditional, skip it
                result.append(pattern, pos, condStart + 3);
                pos = condStart + 3;
                continue;
            }

            // Extract the group number
            int digitEnd = condStart + 3;
            while (digitEnd < len && Character.isDigit(pattern.charAt(digitEnd))) {
                digitEnd++;
            }

            if (digitEnd >= len || pattern.charAt(digitEnd) != ')') {
                // Invalid format, skip
                result.append(pattern, pos, digitEnd);
                pos = digitEnd;
                continue;
            }

            int groupNum = Integer.parseInt(pattern.substring(condStart + 3, digitEnd));

            // Now find the yes|no branches
            // We need to find the matching ) for the conditional
            int branchStart = digitEnd + 1; // After the ) of (?(N)
            int parenDepth = 0;
            int pipePos = -1;
            int condEnd = branchStart;
            boolean inCharClass = false;
            boolean escaped = false;

            while (condEnd < len) {
                char ch = pattern.charAt(condEnd);

                if (escaped) {
                    escaped = false;
                    condEnd++;
                    continue;
                }

                if (ch == '\\') {
                    escaped = true;
                    condEnd++;
                    continue;
                }

                if (inCharClass) {
                    if (ch == ']') {
                        inCharClass = false;
                    }
                    condEnd++;
                    continue;
                }

                if (ch == '[') {
                    inCharClass = true;
                    condEnd++;
                    continue;
                }

                if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')') {
                    if (parenDepth == 0) {
                        // Found the end of conditional
                        break;
                    }
                    parenDepth--;
                } else if (ch == '|' && parenDepth == 0 && pipePos == -1) {
                    pipePos = condEnd;
                }

                condEnd++;
            }

            if (condEnd >= len) {
                // Unterminated conditional, let normal error handling catch it
                result.append(pattern.substring(pos));
                break;
            }

            // Extract yes and no branches
            String yesBranch = pipePos > 0 ? pattern.substring(branchStart, pipePos) : pattern.substring(branchStart, condEnd);
            String noBranch = pipePos > 0 ? pattern.substring(pipePos + 1, condEnd) : "";

            // Now try to find the referenced group BEFORE this conditional
            // For simplicity in Phase 1, we only handle if the group appears immediately before
            // or with simple pattern between (like literals)

            // Look backwards for group N
            // Count groups from the start to condStart
            int groupCount = 0;
            int groupNStart = -1;
            int groupNEnd = -1;
            int searchPos = 0;
            int depth = 0;
            boolean isOptional = false;

            while (searchPos < condStart) {
                char ch = pattern.charAt(searchPos);

                if (ch == '\\') {
                    searchPos += 2; // Skip escaped char
                    continue;
                }

                if (ch == '(') {
                    // Check if it's a capturing group
                    if (searchPos + 1 < condStart && pattern.charAt(searchPos + 1) != '?') {
                        // It's a capturing group
                        groupCount++;
                        if (groupCount == groupNum) {
                            groupNStart = searchPos;
                            // Find the end of this group
                            depth = 1;
                            int endPos = searchPos + 1;
                            while (endPos < condStart && depth > 0) {
                                char c = pattern.charAt(endPos);
                                if (c == '\\') {
                                    endPos += 2;
                                    continue;
                                }
                                if (c == '(') depth++;
                                if (c == ')') depth--;
                                endPos++;
                            }
                            groupNEnd = endPos;

                            // Check if followed by ? or * or {0,
                            if (groupNEnd < condStart) {
                                char next = pattern.charAt(groupNEnd);
                                if (next == '?' || next == '*') {
                                    isOptional = true;
                                } else if (next == '{') {
                                    // Check for {0,n}
                                    int closePos = pattern.indexOf('}', groupNEnd);
                                    if (closePos > 0 && closePos < condStart) {
                                        String quant = pattern.substring(groupNEnd + 1, closePos);
                                        if (quant.startsWith("0,")) {
                                            isOptional = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                searchPos++;
            }

            // Only transform if we found the group and it's optional and appears directly before the conditional
            if (groupNStart >= 0 && isOptional) {
                // Check if there's only simple content between group and conditional
                String between = pattern.substring(groupNEnd + 1, condStart); // +1 to skip the ? or * after group

                // For Phase 1, only transform if:
                // 1. The group is immediately before conditional OR
                // 2. There's only simple literal text between
                boolean canTransform = between.isEmpty() || between.matches("[a-zA-Z0-9\\s\\^\\$]+");

                if (canTransform) {
                    // Perform transformation!
                    // Append everything before the group
                    result.append(pattern, pos, groupNStart);

                    // Build the alternation: (?:(group)between+yes|between+no)
                    result.append("(?:");

                    // First alternative: group+between+yes
                    result.append(pattern, groupNStart, groupNEnd); // The group itself (without the ? or *)
                    result.append(between);
                    result.append(yesBranch);

                    // Second alternative: between+no
                    // Always add second alternative (even if noBranch is empty)
                    // Empty noBranch means "match nothing" when group doesn't match
                    result.append("|");
                    result.append(between);
                    result.append(noBranch);

                    result.append(")");

                    // Continue after the conditional
                    pos = condEnd + 1;
                    continue;
                }
            }

            // Could not transform, keep original
            result.append(pattern, pos, condEnd + 1);
            pos = condEnd + 1;
        }

        return result.toString();
    }

    /**
     * Handles (?{...}) code blocks in regex patterns.
     * For simple constant expressions, replaces them with empty named capture groups.
     * This allows pack.t tests to work without full (?{...}) implementation.
     *
     * @param s          The regex string
     * @param offset     Current position (at '(' of '(?{')
     * @param length     Length of the regex string
     * @param sb         StringBuilder to append the processed regex
     * @param regexFlags The regex flags
     * @return New offset after processing the code block
     */
    private static int handleCodeBlock(String s, int offset, int length, StringBuilder sb, RegexFlags regexFlags) {
        // Find the matching closing brace
        int braceCount = 1;
        int codeStart = offset + 3; // Skip '(?{'
        int codeEnd = codeStart;

        while (codeEnd < length && braceCount > 0) {
            char c = s.charAt(codeEnd);
            if (c == '{') {
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    break;
                }
            } else if (c == '\\' && codeEnd + 1 < length) {
                // Skip escaped characters
                codeEnd++;
            }
            codeEnd++;
        }

        if (braceCount != 0) {
            regexError(s, offset + 2, "Unmatched '{' in (?{...}) code block");
        }

        // Extract the code block content
        String codeBlock = s.substring(codeStart, codeEnd).trim();

        // Try to detect simple constant expressions
        // This handles the common cases in pack.t without full parser integration
        if (isSimpleConstant(codeBlock)) {
            // Replace with an empty named capture group
            // This allows the regex to compile and match correctly
            // Generate a unique name for this code block capture
            // Java regex requires capture group names to start with a letter
            String captureName = "codeblock" + captureGroupCount++;

            // Append a named capture group that matches empty string
            // This allows us to store the constant value without affecting the match
            sb.append("(?<").append(captureName).append(">)");

            // Skip past '}' and ')' - the closing brace and paren of (?{...})
            // codeEnd points to the '}', so we need to skip '}' and ')'
            if (codeEnd + 1 < length && s.charAt(codeEnd + 1) == ')') {
                return codeEnd + 2; // Skip past both '}' and ')'
            }
            return codeEnd + 1; // Just skip past '}' if no ')' found
        }

        // If we couldn't handle it, throw an unimplemented exception that can be caught by RuntimeRegex
        // RuntimeRegex will handle JPERL_UNIMPLEMENTED=warn to make it non-fatal
        throw new PerlJavaUnimplementedException("(?{...}) code blocks in regex not implemented (only constant expressions supported) in regex; marked by <-- HERE in m/" +
                s.substring(0, offset + 2) + " <-- HERE " + s.substring(offset + 2) + "/");
    }

    /**
     * {{ ... }}
     * Handles the common patterns used in pack.t tests.
     *
     * @param codeBlock The code block content (without (?{ and }))
     * @return true if this is a simple constant we can handle
     */
    private static boolean isSimpleConstant(String codeBlock) {
        // Handle common constant patterns from pack.t:
        // - undef
        // - 'string'
        // - "string"
        // - numbers (integer or float)
        // - simple arithmetic (e.g., 1.36514538e37)

        if (codeBlock.equals("undef")) {
            return true;
        }

        // String literals
        if ((codeBlock.startsWith("'") && codeBlock.endsWith("'")) ||
                (codeBlock.startsWith("\"") && codeBlock.endsWith("\""))) {
            return true;
        }

        // Numeric literals (including scientific notation)
        if (codeBlock.matches("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            return true;
        }

        // Simple arithmetic expressions (for pack.t compatibility)
        // Just check if it looks like a number-only expression
        return codeBlock.matches("[\\d\\s+\\-*/().eE]+");
    }
}

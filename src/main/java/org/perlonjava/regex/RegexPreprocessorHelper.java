package org.perlonjava.regex;

import java.util.HashSet;
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
        while (offset < length) {
            final int c = s.codePointAt(offset);
            switch (c) {
                case ']':
                    if (first) {
                        sb.append("\\]");
                        break;
                    } else {
                        sb.append(Character.toChars(c));
                        return offset;
                    }
                case '[':
                    // Check for character class like [:ascii:]
                    offset = RegexPreprocessor.handleCharacterClass(offset, s, sb, length);
                    break;
                case '\\':  // Handle escape sequences
                    sb.append(Character.toChars(c));
                    offset++;
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
                    } else if (s.codePointAt(offset) == 'b') {
                        rejected.append("\\b"); // Java doesn't support \b inside [...]
                        offset++;
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
                    }
                    break;
                case ' ', '\t':
                    if (flag_xx) {
                        sb.append(Character.toChars(c));
                    } else {
                        // make this space a "token", even inside /x
                        sb.append("\\").append(Character.toChars(c));
                    }
                    break;
                case '(', ')', '*', '?', '<', '>', '\'', '"', '$', '@', '#', '=', '&':
                    sb.append('\\');
                    sb.append(Character.toChars(c));
                    break;
                default:
                    sb.append(Character.toChars(c));
                    break;
            }
            first = false;
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
}

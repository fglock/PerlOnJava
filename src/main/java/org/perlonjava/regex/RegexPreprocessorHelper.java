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

        // Check for numeric backreferences
        if (nextChar >= '1' && nextChar <= '9') {
            // This is a backreference like \1, \2, etc.
            int refNum = nextChar - '0';

            // Check if we have captured this many groups
            if (refNum > RegexPreprocessor.captureGroupCount) {
                sb.setLength(sb.length() - 1); // Remove the backslash
                RegexPreprocessor.regexError(s, offset + 1, "Reference to nonexistent group");
            }

            sb.append(nextChar);
            return offset;
        }

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
                RegexPreprocessor.regexError(s, offset, "Missing right brace on \\\\p{}");
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

        // POSIX syntax checking
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
                            // IMPORTANT: Use searchPos + 2, not offset + 2
                            RegexPreprocessor.regexError(s, searchPos + 2, "POSIX syntax " + syntaxType + " is reserved for future extensions");
                        }
                        searchPos++;
                    }
                }
            }
        }

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
                                RegexPreprocessor.regexError(s, offset + 2,
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
                    // Check if this could be a POSIX character class like [:ascii:]
                    if (offset + 1 < length && s.charAt(offset + 1) == ':') {
                        // This might be a POSIX character class
                        offset = RegexPreprocessor.handleCharacterClass(offset, s, sb, length);
                    } else {
                        // It's just a literal [ inside a character class
                        sb.append("\\[");  // Escape it for Java regex
                    }
                    first = false;
                    afterCaret = false;
                    lastChar = '[';
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

        // Check for invalid (?^- pattern
        if (flags.startsWith("^-")) {
            RegexPreprocessor.regexError(s, start + 1, "Sequence (?^-...) not recognized");
        }

        // Check for invalid (?^d pattern
        if (flags.startsWith("^d")) {
            RegexPreprocessor.regexError(s, start + 2, "Sequence (?^d...) not recognized");
        }

        sb.append("(?");

        // Split into positive and negative parts
        String[] parts = flags.split("-", 2);
        String positiveFlags = parts[0];
        String negativeFlags = parts.length > 1 ? parts[1] : "";

        // Check for mutually exclusive modifiers
        if (positiveFlags.contains("l") && positiveFlags.contains("u")) {
            // Find position of second flag
            int lPos = positiveFlags.indexOf('l');
            int uPos = positiveFlags.indexOf('u');
            int errorPos = Math.max(lPos, uPos);
            RegexPreprocessor.regexError(s, start + errorPos + 1, "Regexp modifiers \"l\" and \"u\" are mutually exclusive");
        }

        // Check for d and a being mutually exclusive
        if (positiveFlags.contains("d") && positiveFlags.contains("a")) {
            // Find position of second flag
            int dPos = positiveFlags.indexOf('d');
            int aPos = positiveFlags.indexOf('a');
            int errorPos = Math.max(dPos, aPos);
            RegexPreprocessor.regexError(s, start + errorPos + 1, "Regexp modifiers \"d\" and \"a\" are mutually exclusive");
        }

        // Check for duplicate modifiers
        if (positiveFlags.indexOf('l') != positiveFlags.lastIndexOf('l')) {
            int secondL = positiveFlags.lastIndexOf('l');
            RegexPreprocessor.regexError(s, start + secondL + 1, "Regexp modifier \"l\" may not appear twice");
        }

        // Check for 'a' appearing more than twice
        int aCount = positiveFlags.length() - positiveFlags.replace("a", "").length();
        if (aCount > 2) {
            // Find position of third 'a'
            int pos = -1;
            for (int i = 0; i < 3; i++) {
                pos = positiveFlags.indexOf('a', pos + 1);
            }
            RegexPreprocessor.regexError(s, start + pos + 1, "Regexp modifier \"a\" may appear a maximum of twice");
        }

        // Check for modifiers in negative part that shouldn't be there
        if (negativeFlags.contains("l")) {
            int lPos = negativeFlags.indexOf('l');
            RegexPreprocessor.regexError(s, start + parts[0].length() + 1 + lPos + 1, "Regexp modifier \"l\" may not appear after the \"-\"");
        }

        // Handle caret case
        if (!positiveFlags.isEmpty() && positiveFlags.charAt(0) == '^') {
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

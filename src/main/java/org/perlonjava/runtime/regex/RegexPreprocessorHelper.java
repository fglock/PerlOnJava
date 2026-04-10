package org.perlonjava.runtime.regex;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.perlonjava.runtime.regex.UnicodeResolver.translateUnicodeProperty;

public class RegexPreprocessorHelper {
    static int handleEscapeSequences(String s, StringBuilder sb, int c, int offset, RegexFlags regexFlags) {
        sb.append(Character.toChars(c));  // This appends the backslash
        final int length = s.length();

        offset++;
        if (offset >= length) {
            return offset;
        }

        char nextChar = s.charAt(offset);

        // Check for numeric backreferences vs octal escapes
        // Perl disambiguation rules (from perlrebackslash):
        // 1. If the backslash is followed by a single digit, it's a backreference.
        // 2. If the first digit following the backslash is a 0, it's an octal escape.
        // 3. If the number N (in decimal) and Perl has already seen N capture groups,
        //    it's a backreference. Otherwise, it's an octal escape.
        //
        // Examples:
        //   \100 with 100 capture groups -> backreference to group 100
        //   \100 with 0 capture groups   -> octal 100 = '@'
        //   \037 with 0 capture groups   -> octal 037 = unit separator (used in Archive::Tar)
        //   \9 is always a backreference (single digit rule)
        boolean isOctalNotBackref = false;
        if (nextChar >= '1' && nextChar <= '9') {
            // Parse all consecutive digits to get the full number
            int endDigits = offset;
            while (endDigits < length && s.charAt(endDigits) >= '0' && s.charAt(endDigits) <= '9') {
                endDigits++;
            }
            String digitStr = s.substring(offset, endDigits);
            int refNum = Integer.parseInt(digitStr);
            
            // Rule 3: If refNum > captureGroupCount, it's an octal escape (if valid octal)
            if (refNum > RegexPreprocessor.captureGroupCount) {
                // Check if all digits are octal (0-7)
                boolean allOctal = true;
                for (int i = 0; i < digitStr.length(); i++) {
                    char d = digitStr.charAt(i);
                    if (d > '7') {
                        allOctal = false;
                        break;
                    }
                }
                if (allOctal && digitStr.length() >= 2) {
                    // This is an octal escape, not a backreference
                    isOctalNotBackref = true;
                }
            }
        }

        if (!isOctalNotBackref && nextChar >= '1' && nextChar <= '9') {
            // This is a backreference like \1, \2, etc.
            int refNum = nextChar - '0';

            // Check if we have ANY capture groups at all
            // If there are no groups, this is always an error
            // But if there are groups, allow forward references
            if (RegexPreprocessor.captureGroupCount == 0) {
                sb.setLength(sb.length() - 1); // Remove the backslash
                RegexPreprocessor.regexError(s, offset + 1, "Reference to nonexistent group");
            }
            // Forward references are allowed when there are capture groups
            // Perl allows forward references like (\3|b)\2(a) where \3 refers to group 3
            // which hasn't been captured yet. This is valid and the reference just won't match
            // until group 3 is actually captured.

            sb.append(nextChar);
            return offset;
        }
        if (nextChar == 'k' && offset + 1 < length && s.charAt(offset + 1) == '\'') {
            // Handle \k'name' backreference (Perl syntax)
            offset += 2; // Skip past \k'
            int endQuote = s.indexOf('\'', offset);
            if (endQuote != -1) {
                String name = s.substring(offset, endQuote);
                // Convert to Java syntax \k<name>
                sb.setLength(sb.length() - 1); // Remove the backslash
                sb.append("\\k<").append(name).append(">");
                return endQuote; // Return position at closing quote
            } else {
                RegexPreprocessor.regexError(s, offset - 2, "Unterminated \\k'...' backreference");
            }
        }
        if (nextChar == 'g') {
            if (offset + 1 < length && s.charAt(offset + 1) == '{') {
                // Handle \g{name} backreference
                offset += 2; // Skip past \g{
                int endBrace = s.indexOf('}', offset);
                if (endBrace != -1) {
                    String ref = s.substring(offset, endBrace).trim(); // Trim whitespace

                    // Check if it's numeric (including negative)
                    try {
                        int groupNum = Integer.parseInt(ref);
                        if (groupNum == 0) {
                            RegexPreprocessor.regexError(s, offset - 2, "Reference to invalid group 0");
                        } else if (groupNum < 0) {
                            // Handle relative backreference
                            int absoluteRef = RegexPreprocessor.captureGroupCount + groupNum + 1;
                            if (absoluteRef > 0) {
                                sb.setLength(sb.length() - 1); // Remove the backslash
                                sb.append("\\").append(absoluteRef);
                            } else {
                                RegexPreprocessor.regexError(s, offset - 2, "Reference to nonexistent or unclosed group");
                            }
                        } else {
                            // Positive numeric reference
                            // If there are no groups at all, this is an error
                            if (RegexPreprocessor.captureGroupCount == 0) {
                                RegexPreprocessor.regexError(s, offset - 2, "Reference to nonexistent group");
                            }
                            // Otherwise allow forward references
                            sb.setLength(sb.length() - 1); // Remove the backslash
                            sb.append("\\").append(groupNum);
                        }
                    } catch (NumberFormatException e) {
                        // It's a named reference
                        sb.setLength(sb.length() - 1); // Remove the backslash
                        sb.append("\\k<").append(ref).append(">");
                    }
                    offset = endBrace;
                }
            } else if (offset + 1 < length && Character.isDigit(s.charAt(offset + 1))) {
                // Handle \g0, \g1, etc.
                int start = offset + 1;
                int end = start;
                while (end < length && Character.isDigit(s.charAt(end))) {
                    end++;
                }
                String numStr = s.substring(start, end);
                int groupNum = Integer.parseInt(numStr);

                if (groupNum == 0) {
                    RegexPreprocessor.regexError(s, offset, "Reference to invalid group 0");
                } else if (RegexPreprocessor.captureGroupCount == 0) {
                    // No groups at all - this is an error
                    RegexPreprocessor.regexError(s, offset, "Reference to nonexistent group");
                }
                // Otherwise allow forward references - they're valid in Perl

                sb.setLength(sb.length() - 1); // Remove the backslash
                sb.append("\\").append(groupNum);
                return end - 1; // -1 because the main loop will increment
            } else if (offset + 1 < length && s.charAt(offset + 1) == '-') {
                // Handle \g-1, \g-2, etc.
                int start = offset + 1;
                int end = start + 1; // Skip the minus
                while (end < length && Character.isDigit(s.charAt(end))) {
                    end++;
                }
                String numStr = s.substring(start, end);
                int relativeRef = Integer.parseInt(numStr);
                int absoluteRef = RegexPreprocessor.captureGroupCount + relativeRef + 1;

                if (absoluteRef > 0) {
                    sb.setLength(sb.length() - 1); // Remove the backslash
                    sb.append("\\").append(absoluteRef);
                } else {
                    RegexPreprocessor.regexError(s, offset, "Reference to nonexistent group");
                }
                return end - 1;
            }
        } else if (nextChar == 's' || nextChar == 'S') {
            // Handle \s and \S based on ASCII mode
            sb.setLength(sb.length() - 1); // Remove the backslash
            if (regexFlags.isAscii()) {
                // ASCII mode: \s matches only ASCII whitespace
                if (nextChar == 's') {
                    sb.append("[\\t\\n\\u000B\\f\\r\\x20]");
                } else {
                    sb.append("[^\\t\\n\\u000B\\f\\r\\x20]");
                }
            } else {
                // Unicode mode: Perl's \s matches Unicode whitespace
                // Expand \s to match all Perl whitespace characters:
                // \t \n \f \r space (ASCII: 09-0D, 20)
                // U+000B (vertical tab - Perl includes this)
                // U+1680 (OGHAM SPACE MARK)
                // U+2000-U+200A (EN QUAD through HAIR SPACE)
                // U+2028 (LINE SEPARATOR)
                // U+2029 (PARAGRAPH SEPARATOR)
                // U+202F (NARROW NO-BREAK SPACE)
                // U+205F (MEDIUM MATHEMATICAL SPACE)
                // U+3000 (IDEOGRAPHIC SPACE)
                if (nextChar == 's') {
                    // Positive: matches whitespace
                    // Use \x20 instead of literal space to avoid issues with /x modifier
                    sb.append("[\\t\\n\\u000B\\f\\r\\x20\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]");
                } else {
                    // Negative: matches non-whitespace
                    sb.append("[^\\t\\n\\u000B\\f\\r\\x20\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]");
                }
            }
            return offset;
        } else if (nextChar == 'h') {
            // \h - horizontal whitespace
            sb.setLength(sb.length() - 1); // Remove the backslash
            sb.append("[\\t\\x20\\xA0\\x{1680}\\x{2000}-\\x{200A}\\x{202F}\\x{205F}\\x{3000}]");
            return offset;
        } else if (nextChar == 'H') {
            // \H - not horizontal whitespace
            sb.setLength(sb.length() - 1); // Remove the backslash
            sb.append("[^\\t\\x20\\xA0\\x{1680}\\x{2000}-\\x{200A}\\x{202F}\\x{205F}\\x{3000}]");
            return offset;
        } else if (nextChar == 'v') {
            // \v - vertical whitespace
            sb.setLength(sb.length() - 1); // Remove the backslash
            sb.append("[\\n\\x0B\\f\\r\\x85\\x{2028}\\x{2029}]");
            return offset;
        } else if (nextChar == 'V') {
            // \V - not vertical whitespace
            sb.setLength(sb.length() - 1); // Remove the backslash
            sb.append("[^\\n\\x0B\\f\\r\\x85\\x{2028}\\x{2029}]");
            return offset;
        } else if (nextChar == 'K') {
            // \K - keep assertion (reset start of match)
            // Insert a zero-width named capture group to mark the \K position.
            // During substitution, text before this position is preserved ("kept").
            sb.setLength(sb.length() - 1); // Remove the backslash
            RegexPreprocessor.markBackslashK();
            RegexPreprocessor.captureGroupCount++;
            sb.append("(?<perlK>)");
            return offset;
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
                        // It's a Unicode name; emit literal Unicode character(s)
                        int codePoint = UnicodeResolver.getCodePointFromName(content);
                        // Remove the previously appended backslash
                        sb.setLength(sb.length() - 1);
                        sb.append(Character.toChars(codePoint));
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
                try {
                    String translatedProperty = translateUnicodeProperty(property, negated);
                    sb.setLength(sb.length() - 1); // Remove the backslash
                    // Wrap in (?-i:...) to protect Unicode property from /i flag.
                    // Unicode properties should match by codepoint, not case-folded value.
                    // In Perl, /i doesn't affect \p{} matching.
                    sb.append("(?-i:").append(translatedProperty).append(")");
                } catch (IllegalArgumentException e) {
                    // Perl allows user-defined properties (InFoo/IsFoo) to be unknown at compile time;
                    // they are resolved at runtime when the property sub is available.
                    // If it's currently undefined, emit a placeholder that compiles in Java and mark for recompilation.
                    // But if the error already contains "in expansion of", it is a real user-property definition error
                    // that should be reported (not deferred).
                    String msg = e.getMessage();
                    if (property.matches("^(.*::)?(Is|In)[A-Z].*") && (msg == null || !msg.contains("in expansion of"))) {
                        RegexPreprocessor.markDeferredUnicodePropertyEncountered();
                        sb.setLength(sb.length() - 1); // Remove the backslash
                        // Placeholder: match any single character, including newline
                        sb.append("[\\s\\S]");
                    } else {
                        RegexPreprocessor.regexError(s, offset, msg == null ? "Invalid Unicode property" : msg);
                    }
                }
                offset = endBrace;
            } else {
                RegexPreprocessor.regexError(s, offset, "Missing right brace on \\\\p{}");
            }
        } else if (nextChar == 'o' && offset + 1 < length && s.charAt(offset + 1) == '{') {
            // Handle \o{...} octal escape construct
            // Perl allows \o{123} for octal numbers
            // Java doesn't support this syntax, so convert to \x{hex}
            offset += 2; // Skip past \o{
            int endBrace = s.indexOf('}', offset);
            if (endBrace != -1) {
                String octalStr = s.substring(offset, endBrace).trim();
                // Remove underscores (Perl allows them in number literals)
                octalStr = octalStr.replace("_", "");
                try {
                    // Parse as octal and convert to hex
                    int value = Integer.parseInt(octalStr, 8);
                    sb.setLength(sb.length() - 1); // Remove the backslash
                    sb.append(String.format("\\x{%X}", value));
                    offset = endBrace;
                } catch (NumberFormatException e) {
                    RegexPreprocessor.regexError(s, offset, "Invalid octal number in \\o{...}");
                }
            } else {
                RegexPreprocessor.regexError(s, offset, "Missing right brace on \\o{}");
            }
        } else if ((nextChar == 'w' || nextChar == 'W' || nextChar == 'd' || nextChar == 'D') && regexFlags.isAscii()) {
            // In ASCII mode (/a flag), restrict \w, \W, \d, \D to ASCII only
            sb.setLength(sb.length() - 1); // Remove the backslash
            switch (nextChar) {
                case 'w':
                    sb.append("[a-zA-Z0-9_]");
                    break;
                case 'W':
                    sb.append("[^a-zA-Z0-9_]");
                    break;
                case 'd':
                    sb.append("[0-9]");
                    break;
                case 'D':
                    sb.append("[^0-9]");
                    break;
            }
            return offset;
        } else {
            int c2 = s.codePointAt(offset);
            if (c2 >= '0' && c2 <= '7') {
                // Potential octal sequence - parse it
                int octalValue = c2 - '0';
                int octalLength = 1;

                // Read up to 2 more octal digits
                for (int i = 1; i <= 2 && offset + i < length; i++) {
                    int nextDigit = s.codePointAt(offset + i);
                    if (nextDigit >= '0' && nextDigit <= '7') {
                        octalValue = octalValue * 8 + (nextDigit - '0');
                        octalLength++;
                    } else {
                        break;
                    }
                }

                // Check if value is > 255 (requires hex conversion)
                if (octalValue > 255) {
                    // Convert to hex for Java regex
                    sb.setLength(sb.length() - 1); // Remove the backslash
                    sb.append(String.format("\\x{%X}", octalValue));
                    offset += octalLength - 1; // -1 because caller will increment
                } else if (octalValue <= 255 && octalLength == 3) {
                    // Standard 3-digit octal - convert to hex for Java regex
                    // Using \x{hex} avoids issues with \0mnn parsing and ensures
                    // all 3 digits are consumed (e.g., \177 → \x{7F})
                    sb.setLength(sb.length() - 1); // Remove the backslash
                    sb.append(String.format("\\x{%X}", octalValue));
                    offset += octalLength - 1; // -1 because caller will increment
                } else if (c2 == '0' && octalLength == 1) {
                    // Single \0 becomes \00
                    sb.append('0');
                    sb.append('0');
                } else {
                    // Short octal or single digit, pass through
                    sb.append(Character.toChars(c2));
                }
            } else if (c2 == '8' || c2 == '9') {
                // \8 and \9 are not valid octals - treat as literal digits
                sb.setLength(sb.length() - 1); // Remove the backslash
                sb.append(Character.toChars(c2));
            } else if (c2 == 'x' && offset + 1 < length && s.charAt(offset + 1) == '{') {
                // \x{...} hex escape - parse and normalize the hex value.
                // Perl stops at the first non-hex character (after removing underscores).
                offset += 2; // Skip past x{
                int endBrace = -1;
                for (int i = offset; i < length; i++) {
                    if (s.charAt(i) == '}') {
                        endBrace = i;
                        break;
                    }
                }
                if (endBrace != -1) {
                    String hexStr = s.substring(offset, endBrace).trim().replace("_", "");
                    // Extract valid hex prefix
                    int validLen = 0;
                    for (int i = 0; i < hexStr.length(); i++) {
                        char ch = hexStr.charAt(i);
                        if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')) {
                            validLen++;
                        } else {
                            break;
                        }
                    }
                    int value;
                    if (validLen == 0) {
                        value = 0; // No valid hex digits → \x00
                    } else {
                        value = Integer.parseInt(hexStr.substring(0, validLen), 16);
                    }
                    sb.append(String.format("x{%X}", value));
                    offset = endBrace;
                } else {
                    // No closing brace - pass through as-is
                    sb.append('x');
                }
                // offset now points to '}', caller will increment
            } else if (c2 == 'x') {
                // Bare \xNN (no braces) - Perl takes up to 2 hex digits.
                // If fewer than 2 valid hex digits, stop at first non-hex char.
                // Java's Pattern requires exactly 2 hex digits for \xHH, so normalize.
                int hexVal = 0;
                int hexDigits = 0;
                int pos = offset + 1; // position after 'x'
                while (hexDigits < 2 && pos < length) {
                    char ch = s.charAt(pos);
                    if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')) {
                        hexVal = hexVal * 16 + Character.digit(ch, 16);
                        hexDigits++;
                        pos++;
                    } else {
                        break;
                    }
                }
                if (hexDigits == 2) {
                    // Standard \xHH - pass through (Java handles it natively)
                    sb.append('x');
                    sb.append(s.charAt(offset + 1));
                    sb.append(s.charAt(offset + 2));
                    offset += 2;
                } else {
                    // 0 or 1 hex digits - use \x{H} format for Java
                    sb.append(String.format("x{%X}", hexVal));
                    offset = pos - 1; // -1 because caller will increment
                }
            } else {
                // Other escape sequences, pass through
                sb.append(Character.toChars(c2));
            }
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
                            // Check if next is start of POSIX class like [:alpha:]
                            // In that case, the hyphen is literal, not a range
                            if (nextChar == '[' && nextPos + 1 < length && s.charAt(nextPos + 1) == ':') {
                                // Next is a POSIX class, hyphen is literal
                                sb.append(Character.toChars(c));
                                first = false;
                                afterCaret = false;
                                lastChar = -1;
                                wasEscape = false;
                                break;
                            }
                            
                            // Handle escaped next character
                            int rangeEndCharCount = 1;  // How many chars to skip for range end
                            if (nextChar == '\\' && nextPos + 1 < length) {
                                nextChar = s.codePointAt(nextPos + 1);
                                rangeEndCharCount = 2;  // Escaped char is 2 chars
                                // Special handling for escape sequences
                                if (nextChar == 'b' || nextChar == 'N' || nextChar == 'p' || nextChar == 'P') {
                                    // These are special escapes, can't be in range
                                    nextChar = -1;
                                } else if (nextChar == 'x' && nextPos + 2 < length && s.charAt(nextPos + 2) == '{') {
                                    // Parse \x{NNNN} as range endpoint
                                    int endBrace = s.indexOf('}', nextPos + 3);
                                    if (endBrace != -1) {
                                        String hex = s.substring(nextPos + 3, endBrace).trim().replace("_", "");
                                        // Extract valid hex prefix (Perl stops at first non-hex char)
                                        int vLen = 0;
                                        for (int i = 0; i < hex.length(); i++) {
                                            char ch = hex.charAt(i);
                                            if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')) {
                                                vLen++;
                                            } else {
                                                break;
                                            }
                                        }
                                        if (vLen == 0) {
                                            nextChar = 0;
                                        } else {
                                            nextChar = Integer.parseInt(hex.substring(0, vLen), 16);
                                        }
                                        rangeEndCharCount = endBrace - nextPos + 1;
                                    }
                                } else if (nextChar == 'o' && nextPos + 2 < length && s.charAt(nextPos + 2) == '{') {
                                    // Parse \o{NNNN} as range endpoint
                                    int endBrace = s.indexOf('}', nextPos + 3);
                                    if (endBrace != -1) {
                                        String oct = s.substring(nextPos + 3, endBrace).trim().replace("_", "");
                                        try {
                                            nextChar = Integer.parseInt(oct, 8);
                                            rangeEndCharCount = endBrace - nextPos + 1;
                                        } catch (NumberFormatException e) {
                                            nextChar = -1;
                                        }
                                    }
                                } else if (nextChar >= '0' && nextChar <= '7') {
                                    // Parse bare octal escape (\NNN) as range endpoint
                                    // e.g., \237 → octal 237 = 159
                                    int octalVal = nextChar - '0';
                                    int digits = 1;
                                    for (int k = 2; k <= 3 && nextPos + k < length; k++) {
                                        int d = s.codePointAt(nextPos + k);
                                        if (d >= '0' && d <= '7') {
                                            octalVal = octalVal * 8 + (d - '0');
                                            digits++;
                                        } else {
                                            break;
                                        }
                                    }
                                    if (digits >= 2) {
                                        // Multi-digit octal: use computed value
                                        nextChar = octalVal;
                                        rangeEndCharCount = 1 + digits; // backslash + digits
                                    }
                                    // Single digit \N stays as-is (rangeEndCharCount = 2)
                                }
                            }

                            if (nextChar != -1) {
                                if (nextChar < lastChar) {
                                    String rangeStart = Character.toString(lastChar);
                                    String rangeEnd = Character.toString(nextChar);
                                    RegexPreprocessor.regexError(s, offset + 2,
                                            "Invalid [] range \"" + rangeStart + "-" + rangeEnd + "\" in regex");
                                }
                                // Valid range - append the dash and range end, then skip past range end
                                sb.append(Character.toChars(c));  // Append the '-'
                                // Append and skip the range end character so it won't be processed again
                                // This prevents the range end from being used as a range start
                                for (int i = 0; i < rangeEndCharCount; i++) {
                                    sb.append(s.charAt(nextPos + i));
                                }
                                offset += rangeEndCharCount;  // Skip past range end
                                first = false;
                                afterCaret = false;
                                lastChar = -1;  // Range complete, next dash is literal or starts new range
                                wasEscape = false;
                                break;
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
                        first = false;
                        afterCaret = false;
                        lastChar = -1;  // POSIX classes can't be range endpoints
                        wasEscape = false;
                    } else {
                        // It's just a literal [ inside a character class
                        sb.append("\\[");  // Escape it for Java regex
                        first = false;
                        afterCaret = false;
                        lastChar = '[';
                        wasEscape = false;
                    }
                    break;
                case '\\':  // Handle escape sequences
                    sb.append(Character.toChars(c));
                    offset++;
                    wasEscape = true;
                    if (offset < length && (s.charAt(offset) == 'p' || s.charAt(offset) == 'P')
                            && offset + 1 < length && s.charAt(offset + 1) == '{') {
                        // Handle \p{...} and \P{...} inside character class
                        // Translate Perl Unicode property names to Java-compatible patterns
                        boolean pNegated = (s.charAt(offset) == 'P');
                        int pEndBrace = s.indexOf('}', offset + 2);
                        if (pEndBrace != -1) {
                            String property = s.substring(offset + 2, pEndBrace).trim();
                            try {
                                String translatedProperty = UnicodeResolver.translateUnicodeProperty(property, pNegated);
                                // Remove the backslash that was already appended
                                sb.setLength(sb.length() - 1);
                                // Append the translated property (e.g., a character class pattern from ICU4J)
                                sb.append(translatedProperty);
                                offset = pEndBrace;
                            } catch (IllegalArgumentException e) {
                                // If translation fails, pass through as-is and let Java handle it
                                // (works for standard Java properties like \p{L}, \p{IsAlphabetic}, etc.)
                                sb.append(Character.toChars(s.charAt(offset)));
                            }
                        } else {
                            sb.append(Character.toChars(s.charAt(offset)));
                        }
                        lastChar = -1;  // Unicode properties can't be range endpoints
                    } else if (offset < length && s.charAt(offset) == 'N') {
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
                                    // It's a Unicode name; emit literal Unicode character(s)
                                    int codePoint = UnicodeResolver.getCodePointFromName(content);
                                    sb.append(Character.toChars(codePoint));
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
                    } else if (offset + 1 < length && s.charAt(offset) == 'o' && s.charAt(offset + 1) == '{') {
                        // Handle \o{...} octal escape construct in character class
                        offset += 2; // Skip past \o{
                        int endBrace = s.indexOf('}', offset);
                        if (endBrace != -1) {
                            String octalStr = s.substring(offset, endBrace).trim();
                            // Remove underscores (Perl allows them in number literals)
                            octalStr = octalStr.replace("_", "");
                            try {
                                // Parse as octal and convert to hex
                                int value = Integer.parseInt(octalStr, 8);
                                sb.append(String.format("x{%X}", value));
                                offset = endBrace;
                                lastChar = value;
                            } catch (NumberFormatException e) {
                                RegexPreprocessor.regexError(s, offset, "Invalid octal number in \\o{...}");
                            }
                        } else {
                            RegexPreprocessor.regexError(s, offset, "Missing right brace on \\o{}");
                        }
                    } else if (offset + 1 < length && s.charAt(offset) == 'x' && s.charAt(offset + 1) == '{') {
                        // Handle \x{...} hex escape construct in character class
                        offset += 2; // Skip past x{
                        int endBrace = s.indexOf('}', offset);
                        if (endBrace != -1) {
                            String hexStr = s.substring(offset, endBrace).trim();
                            // Remove underscores (Perl allows them in number literals)
                            hexStr = hexStr.replace("_", "");
                            // Extract valid hex prefix (Perl stops at first non-hex char)
                            int validLen = 0;
                            for (int i = 0; i < hexStr.length(); i++) {
                                char ch = hexStr.charAt(i);
                                if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F')) {
                                    validLen++;
                                } else {
                                    break;
                                }
                            }
                            int value;
                            if (validLen == 0) {
                                value = 0; // No valid hex digits → \x00
                            } else {
                                value = Integer.parseInt(hexStr.substring(0, validLen), 16);
                            }
                            sb.append(String.format("x{%X}", value));
                            offset = endBrace;
                            lastChar = value;
                        } else {
                            RegexPreprocessor.regexError(s, offset, "Missing right brace on \\x{}");
                        }
                    } else if (s.codePointAt(offset) == 'b') {
                        // \b inside character class = backspace in Perl
                        // Java doesn't support \b in [...], so convert to \x08
                        // Remove the \ that was already appended to sb
                        sb.setLength(sb.length() - 1);
                        // Use \x08 directly in the class (works for ranges too)
                        sb.append("\\x08");
                        // Don't increment offset here - the outer loop will do it
                        lastChar = 0x08;  // Backspace character for range validation
                        first = false;
                        afterCaret = false;
                    } else {
                        int c2 = s.codePointAt(offset);
                        if (c2 >= '0' && c2 <= '7') {
                            // Potential octal sequence - parse it
                            int octalValue = c2 - '0';
                            int octalLength = 1;

                            // Read up to 2 more octal digits
                            for (int i = 1; i <= 2 && offset + i < length; i++) {
                                int nextDigit = s.codePointAt(offset + i);
                                if (nextDigit >= '0' && nextDigit <= '7') {
                                    octalValue = octalValue * 8 + (nextDigit - '0');
                                    octalLength++;
                                } else {
                                    break;
                                }
                            }

                            // Check if value is > 255 (requires hex conversion)
                            if (octalValue > 255) {
                                // Convert to hex for Java regex
                                sb.append(String.format("x{%X}", octalValue));
                                offset += octalLength - 1; // -1 because outer loop will increment
                                lastChar = octalValue;
                            } else if (octalValue <= 255 && octalLength == 3) {
                                // Standard 3-digit octal - convert to hex for Java regex
                                // Using \x{hex} avoids issues with \0mnn parsing and ensures
                                // correct range validation (e.g., [\177-\237])
                                sb.append(String.format("x{%X}", octalValue));
                                offset += octalLength - 1; // -1 because outer loop will increment
                                lastChar = octalValue;
                            } else if (c2 == '0' && octalLength == 1) {
                                // Single \0 becomes \00
                                sb.append('0');
                                sb.append('0');
                                lastChar = 0;
                            } else {
                                // Short octal (1-2 digits) — prepend 0 for Java
                                // In Perl, \1-\7 inside [] are octal; in Java, \N is a backreference
                                sb.append('0');
                                for (int j = 0; j < octalLength; j++) {
                                    sb.append(Character.toChars(s.codePointAt(offset + j)));
                                }
                                offset += octalLength - 1;
                                lastChar = octalValue;
                            }
                        } else if (c2 == '8' || c2 == '9') {
                            // \8 and \9 are not valid octals - treat as literal digits
                            // Remove the backslash that was already appended
                            sb.setLength(sb.length() - 1);
                            sb.append(Character.toChars(c2));
                            lastChar = c2;
                        } else {
                            // Other escape sequences
                            sb.append(Character.toChars(c2));
                            // Remember the actual character for range validation
                            if (c2 != 'p' && c2 != 'P') {  // Skip property escapes
                                lastChar = c2;
                            } else {
                                lastChar = -1;
                            }
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

        if (positiveFlags.indexOf('p') >= 0) {
            RegexPreprocessor.inlinePFlagEncountered = true;
        }

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

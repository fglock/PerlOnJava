package org.perlonjava.runtime;

import com.ibm.icu.lang.UCharacter;

import java.util.HashMap;
import java.util.Map;

/**
 * The RegexPreprocessor class transforms Perl-compatible regular expressions into re2j-compatible format.
 * This preprocessing is necessary due to syntax and feature differences between Perl and re2j regex engines.
 *
 * Regex transformations performed:
 *
 * General transformations:
 * - \G anchor is removed from pattern start
 * - (?#...) inline comments are removed
 * - \N{name} converts Unicode character names to code points
 * - \N{U+XXXX} converts to explicit Unicode code points
 * - Octal escapes are properly zero-padded
 * - Lookarounds are transformed:
 *   (?=) → (?-m:)
 *   (?<=) → (?-m:)
 *   (?<!) → (?-m:^)
 *
 * Character class [...] transformations:
 * - POSIX classes are converted to explicit ranges:
 *   [:ascii:] → [\x00-\x7F]
 *   [:alpha:] → [A-Za-z]
 * - Whitespace handling in /xx mode
 * - Proper escaping of special characters
 *
 * Extended mode features:
 * - /x flag: ignores whitespace and # comments
 * - /xx flag: extra strict whitespace handling in character classes
 *
 * TODO:
 * - Add support for named capture cleanup: (?<my_group>...) → (?<mygroup>...)
 */
public class RegexPreprocessor {

    private static final Map<String, String> CHARACTER_CLASSES = new HashMap<>();

    static {
        String[][] characterClasses = {
                {"[:ascii:]", "[\\x00-\\x7F]"},
                {"[:^ascii:]", "[^\\x00-\\x7F]"},
                {"[:alpha:]", "[A-Za-z]"},
                {"[:^alpha:]", "[^A-Za-z]"},
                {"[:alnum:]", "[A-Za-z0-9]"},
                {"[:^alnum:]", "[^A-Za-z0-9]"},
                {"[:blank:]", "[ \\t]"},
                {"[:^blank:]", "[^ \\t]"},
                {"[:cntrl:]", "[\\x00-\\x1F\\x7F]"},
                {"[:^cntrl:]", "[^\\x00-\\x1F\\x7F]"},
                {"[:digit:]", "[0-9]"},
                {"[:^digit:]", "[^0-9]"},
                {"[:graph:]", "[\\x21-\\x7E]"},
                {"[:^graph:]", "[^\\x21-\\x7E]"},
                {"[:lower:]", "[a-z]"},
                {"[:^lower:]", "[^a-z]"},
                {"[:print:]", "[\\x20-\\x7E\\p{L}\\p{M}\\p{N}\\p{P}\\p{S}]"},
                {"[:^print:]", "[^\\x20-\\x7E\\p{L}\\p{M}\\p{N}\\p{P}\\p{S}]"},
                {"[:punct:]", "[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~]"},
                {"[:^punct:]", "[^!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~]"},
                {"[:space:]", "[ \\t\\r\\n\\v\\f]"},
                {"[:^space:]", "[^ \\t\\r\\n\\v\\f]"},
                {"[:upper:]", "[A-Z]"},
                {"[:^upper:]", "[^A-Z]"},
                {"[:word:]", "[A-Za-z0-9_]"},
                {"[:^word:]", "[^A-Za-z0-9_]"},
                {"[:xdigit:]", "[0-9A-Fa-f]"},
                {"[:^xdigit:]", "[^0-9A-Fa-f]"}
        };
        for (String[] characterClass : characterClasses) {
            CHARACTER_CLASSES.put(characterClass[0], characterClass[1]);
        }
    }

    public static String preProcessRegex(String s, boolean flag_x, boolean flag_xx) {
        final int length = s.length();
        StringBuilder sb = new StringBuilder();
        StringBuilder rejected = new StringBuilder();
        int offset = 0;
        boolean inCharClass = false;

        if (s.startsWith("\\G")) {
            offset += 2;
        }

        while (offset < length) {
            final int c = s.codePointAt(offset);
            switch (c) {
                case '[':
                    inCharClass = true;
                    offset = handleCharacterClass(s, flag_xx, sb, c, offset, length, rejected);
                    inCharClass = false;  // Reset after character class processing
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    if (!inCharClass && (flag_x || flag_xx)) {
                        if (offset > 0 && s.charAt(offset - 1) == '\\') {
                            sb.append(Character.toChars(c));
                        } else {
                            while (offset + 1 < length &&
                                    Character.isWhitespace(s.charAt(offset + 1))) {
                                offset++;
                            }
                        }
                        offset++;
                        continue;
                    }
                    sb.append(Character.toChars(c));
                    break;
                case '#':
                    if (!inCharClass && (flag_x || flag_xx)) {
                        while (offset < length && s.charAt(offset) != '\n') {
                            offset++;
                        }
                        if (offset < length && s.charAt(offset) == '\n') {
                            offset++;
                        }
                        continue;
                    }
                    sb.append(Character.toChars(c));
                    break;
                case '\\':
                    offset = handleEscapeSequences(s, sb, c, offset, length);
                    break;
                default:
                    sb.append(Character.toChars(c));
                    break;
            }
            offset++;
        }

        String result = sb.toString();
        result = result.replaceAll("\\(\\?=", "(?-m:");
        result = result.replaceAll("\\(\\?<=", "(?-m:");
        result = result.replaceAll("\\(\\?<!", "(?-m:^");

        return result;
    }

    private static int handleParentheses(String s, int offset, int length, StringBuilder sb, int c) {
        boolean append = true;
        if (offset < length - 3) {
            int c2 = s.codePointAt(offset + 1);
            int c3 = s.codePointAt(offset + 2);
            if (c2 == '?' && c3 == '#') {
                offset = handleSkipComment(offset, s, length);
                append = false;
            }
        }
        if (append) {
            sb.append(Character.toChars(c));
        }
        return offset;
    }

    private static int handleCharacterClass(String s, boolean flag_xx, StringBuilder sb, int c, int offset, int length, StringBuilder rejected) {
        int len = sb.length();
        sb.append(Character.toChars(c));
        offset++;

        // First check for POSIX character classes
        for (Map.Entry<String, String> entry : CHARACTER_CLASSES.entrySet()) {
            String className = entry.getKey();
            String classReplacement = entry.getValue();
            if (offset + className.length() <= length && s.startsWith(className, offset)) {
                String replacement = classReplacement;
                if (replacement.startsWith("[") && replacement.endsWith("]")) {
                    replacement = replacement.substring(1, replacement.length() - 1);
                }
                sb.append(replacement);
                return offset + className.length() - 1;
            }
        }

        // Handle regular character class content
        offset = handleRegexCharacterClassEscape(offset, s, sb, length, flag_xx, rejected);

        if (!rejected.isEmpty()) {
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

    private static int handleEscapeSequences(String s, StringBuilder sb, int c, int offset, int length) {
        sb.append(Character.toChars(c));
        offset++;
        if (offset < length && s.charAt(offset) == 'N' && offset + 1 < length && s.charAt(offset + 1) == '{') {
            offset += 2;
            int endBrace = s.indexOf('}', offset);
            if (endBrace != -1) {
                String name = s.substring(offset, endBrace).trim();
                int codePoint = getCodePointFromName(name);
                sb.append(String.format("x{%X}", codePoint));
                offset = endBrace;
            } else {
                throw new IllegalArgumentException("Unmatched brace in \\N{name} construct");
            }
        } else {
            int c2 = s.codePointAt(offset);
            if (c2 >= '1' && c2 <= '3') {
                if (offset < length + 1) {
                    int off = offset;
                    int c3 = s.codePointAt(off++);
                    int c4 = s.codePointAt(off++);
                    if ((c3 >= '0' && c3 <= '7') && (c4 >= '0' && c4 <= '7')) {
                        sb.append('0');
                    }
                }
            } else if (c2 == '0') {
                sb.append('0');
            }
            sb.append(Character.toChars(c2));
        }
        return offset;
    }

    private static int handleRegexCharacterClassEscape(int offset, String s, StringBuilder sb, int length, boolean flag_xx,
                                                       StringBuilder rejected) {
        boolean first = true;
        boolean lastWasChar = false;
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
                    offset = handleCharacterClass(offset, s, sb, length);
                    break;
                case '\\':
                    sb.append(Character.toChars(c));
                    offset++;
                    if (offset < length) {
                        sb.append(Character.toChars(s.codePointAt(offset)));
                    }
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    if (flag_xx) {
                        break;
                    }
                    // Fall through to default if not in /xx mode
                default:
                    if (c == '^' && first) {
                        sb.append(Character.toChars(c));
                    } else if (c == '-' && lastWasChar && offset + 1 < length && Character.isLetterOrDigit(s.codePointAt(offset + 1))) {
                        sb.append(Character.toChars(c));
                    } else if ("\\]".indexOf(c) != -1) {
                        sb.append('\\');
                        sb.append(Character.toChars(c));
                    } else {
                        sb.append(Character.toChars(c));
                        lastWasChar = Character.isLetterOrDigit(c);
                    }
                    break;
            }
            first = false;
            offset++;
        }
        return offset;
    }

    private static int handleCharacterClass(int offset, String s, StringBuilder sb, int length) {
        for (Map.Entry<String, String> entry : CHARACTER_CLASSES.entrySet()) {
            String className = entry.getKey();
            String classReplacement = entry.getValue();
            if (offset + className.length() <= length && s.startsWith(className, offset)) {
                // Remove the outer brackets from classReplacement if it has them
                String replacement = classReplacement;
                if (replacement.startsWith("[") && replacement.endsWith("]")) {
                    replacement = replacement.substring(1, replacement.length() - 1);
                }
                sb.append(replacement);
                return offset + className.length() - 1;
            }
        }
        sb.append("[");
        return offset;
    }

    private static int handleSkipComment(int offset, String s, int length) {
        int offset3 = offset;
        while (offset3 < length) {
            final int c3 = s.codePointAt(offset3);
            switch (c3) {
                case ')':
                    return offset3;
                case '\\':
                    offset3++;
                    break;
            }
            offset3++;
        }
        return offset;
    }

    private static int getCodePointFromName(String name) {
        int codePoint;
        if (name.startsWith("U+")) {
            try {
                codePoint = Integer.parseInt(name.substring(2), 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid Unicode code point: " + name);
            }
        } else {
            codePoint = UCharacter.getCharFromName(name);
            if (codePoint == -1) {
                throw new IllegalArgumentException("Invalid Unicode character name: " + name);
            }
        }
        return codePoint;
    }
}

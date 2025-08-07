package org.perlonjava.regex;

import org.perlonjava.runtime.PerlCompilerException;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.Character.isAlphabetic;
import static org.perlonjava.regex.UnicodeResolver.translateUnicodeProperty;

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

    private static int captureGroupCount;

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
                    offset = handleEscapeSequences(s, sb, c, offset);
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

    private static void regexError(String s, int offset, String errMsg) {
        if (offset > s.length()) {
            offset = s.length();
        }
        throw new PerlCompilerException(errMsg + "; marked by <-- HERE in m/" +
                s.substring(0, offset) + " <-- HERE " + s.substring(offset) + "/");
    }

    private static int handleParentheses(String s, int offset, int length, StringBuilder sb, int c, RegexFlags regexFlags, boolean stopAtClosingParen) {
        if (offset < length - 3) {
            int c2 = s.codePointAt(offset + 1);
            int c3 = s.codePointAt(offset + 2);
            int c4 = s.codePointAt(offset + 3);
            if (c2 == '?' && c3 == '#') {
                // Remove inline comments (?# ... )
                offset = handleSkipComment(offset, s, length);
            } else if (c2 == '?' && ((c3 >= 'a' && c3 <= 'z') || c3 == '-' || c3 == '^')) {
                // Handle (?modifiers: ... ) construct
                return handleFlagModifiers(s, offset, sb, regexFlags);
            } else if (c2 == '?' && c3 == '<' && isAlphabetic(c4)) {
                // Handle named capture (?<name> ... )
                offset = handleNamedCapture(c3, s, offset, length, sb, regexFlags);
            } else if (c2 == '?' && c3 == '\'') {
                // Handle named capture (?'name' ... )
                offset = handleNamedCapture(c3, s, offset, length, sb, regexFlags);
            } else {
                offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
            }
        } else {
            // Recursively preprocess the content inside the parentheses
            offset = handleRegularParentheses(s, offset, length, sb, regexFlags);
        }

        // Ensure the closing parenthesis is consumed
        if (offset >= length || s.codePointAt(offset) != ')') {
            regexError(s, offset, "Unterminated ( in regex");
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

    private static int handleFlagModifiers(String s, int offset, StringBuilder sb, RegexFlags regexFlags) {
        int start = offset + 2; // Skip past '(?'
        int colonPos = s.indexOf(':', start);
        int closeParen = s.indexOf(')', start);

        if (closeParen == -1) {
            regexError(s, offset, "Unterminated ( in regex");
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

        // Build the new flag string
        sb.append(positiveFlags);
        if (!negativeFlags.isEmpty()) {
            sb.append('-').append(negativeFlags);
        }

        if (colonPos == -1 || closeParen < colonPos) {
            // Case: `(?flags)pattern`
            sb.append(")");
            offset = handleRegex(s, closeParen + 1, sb, newFlags, true);
            // The closing parenthesis, if any, is consumed by the caller
            if (offset < s.length()) {
                offset--;
            }
            return offset;
        }

        // Case: `(?flags:pattern)`
        sb.append(":");
        offset = handleRegex(s, colonPos + 1, sb, newFlags, true);

        // Ensure the closing parenthesis is consumed
        if (offset >= s.length() || s.codePointAt(offset) != ')') {
            regexError(s, offset, "Unterminated ( in regex");
        }
        sb.append(')');
        return offset;
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
        sb.append(Character.toChars(c));
        offset++;
        StringBuilder rejected = new StringBuilder();
        offset = handleRegexCharacterClassEscape(offset, s, sb, length, flag_xx, rejected);
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

    private static int handleEscapeSequences(String s, StringBuilder sb, int c, int offset) {
        sb.append(Character.toChars(c));
        final int length = s.length();

        offset++;
        if (offset >= length) {
            return offset;
        }

        // Note: \Q .. \E sequences are handled separately, in escapeQ()

        char nextChar = s.charAt(offset);
//        if (nextChar == 'X') {
//            // Translate \X to a Java-compatible grapheme cluster pattern
//            sb.setLength(sb.length() - 1); // Remove the backslash
//            sb.append(generateGraphemeClusterRegex());
//        } else
        if (nextChar == 'g' && offset + 1 < length && s.charAt(offset + 1) == '{') {
            // Handle \g{name} backreference
            offset += 2; // Skip past \g{
            int endBrace = s.indexOf('}', offset);
            if (endBrace != -1) {
                String ref = s.substring(offset, endBrace);
                if (ref.startsWith("-")) {
                    // Handle relative backreference
                    int relativeRef = Integer.parseInt(ref);
                    int absoluteRef = captureGroupCount + relativeRef + 1;
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
        } else if (nextChar == 'N' && offset + 1 < length && s.charAt(offset + 1) == '{') {
            // Handle \N{name} constructs
            offset += 2; // Skip past \N{
            int endBrace = s.indexOf('}', offset);
            if (endBrace != -1) {
                String name = s.substring(offset, endBrace).trim();
                int codePoint = UnicodeResolver.getCodePointFromName(name);
                sb.append(String.format("x{%X}", codePoint));
                offset = endBrace;
            } else {
                regexError(s, offset, "Unmatched brace in \\N{name} construct");
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
                regexError(s, offset, "Unmatched brace in \\p{...} or \\P{...} construct");
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
    private static int handleRegexCharacterClassEscape(int offset, String s, StringBuilder sb, int length, boolean flag_xx,
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
                    offset = handleCharacterClass(offset, s, sb, length);
                    break;
                case '\\':  // Handle escape sequences
                    sb.append(Character.toChars(c));
                    offset++;
                    if (offset < length && s.charAt(offset) == 'N' && offset + 1 < length && s.charAt(offset + 1) == '{') {
                        // Handle \N{name} constructs
                        offset += 2; // Skip past \N{
                        int endBrace = s.indexOf('}', offset);
                        if (endBrace != -1) {
                            String name = s.substring(offset, endBrace).trim();
                            int codePoint = UnicodeResolver.getCodePointFromName(name);
                            sb.append(String.format("x{%X}", codePoint));
                            offset = endBrace;
                        } else {
                            regexError(s, offset, "Unmatched brace in \\N{name} construct");
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

    /**
     * Handles predefined character classes within the regex.
     *
     * @param offset The current offset in the regex string.
     * @param s      The regex string.
     * @param sb     The StringBuilder to append processed characters.
     * @param length The length of the regex string.
     * @return The updated offset after processing the character class.
     */
    private static int handleCharacterClass(int offset, String s, StringBuilder sb, int length) {
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

    /**
     * WIP - replace undescore in named capture
     */
    private static void handleUnderscoreInNamedCapture(int c2, int c3, int c4) {
        if (c2 == '?' && c3 == '<' &&
                ((c4 >= 'A' && c4 <= 'Z') || (c4 >= 'a' && c4 <= 'z') || (c4 == '_'))
        ) {
//                                    // named capture (?<one> ... )
//                                    // replace underscore in name
//                                    int endName = s.indexOf(">", offset+3);
//                                    // PlCORE.say("endName " + endName + " offset " + offset);
//                                    if (endName > offset) {
//                                        String name = s.substring(offset+3, endName);
//                                        String validName = name.replace("_", "UnderScore") + "Num" + named_capture_count; // See: regex_named_capture()
//                                        if (this.namedCaptures == null) {
//                                            this.namedCaptures = new PlHash();
//                                        }
//                                        this.namedCaptures.hget_arrayref(name).array_deref_strict().push_void(new PlString(validName));
//                                        // PlCORE.say("name [" + name + "]");
//                                        sb.append("(?<");
//                                        sb.append(validName);
//                                        sb.append(">");
//                                        offset = endName;
//                                        named_capture_count++;
//                                        // PlCORE.say("name sb [" + sb + "]");
//                                        append = false;
//                                    }
        }
    }
}

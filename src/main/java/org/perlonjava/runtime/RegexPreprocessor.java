package org.perlonjava.runtime;

import com.ibm.icu.lang.UCharacter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    private static final Map<String, String> CHARACTER_CLASSES = new HashMap<>();

    static {
        String[][] characterClasses = {
                {"[:ascii:]", "\\p{ASCII}"},
                {"[:^ascii:]", "\\P{ASCII}"},
                {"[:alpha:]", "\\p{Alpha}"},
                {"[:^alpha:]", "\\P{Alpha}"},
                {"[:alnum:]", "\\p{Alnum}"},
                {"[:^alnum:]", "\\P{Alnum}"},
                {"[:blank:]", "\\p{Blank}"},
                {"[:^blank:]", "\\P{Blank}"},
                {"[:cntrl:]", "\\p{Cntrl}"},
                {"[:^cntrl:]", "\\P{Cntrl}"},
                {"[:digit:]", "\\p{Digit}"},
                {"[:^digit:]", "\\P{Digit}"},
                {"[:graph:]", "\\p{Graph}"},
                {"[:^graph:]", "\\P{Graph}"},
                {"[:lower:]", "\\p{Lower}"},
                {"[:^lower:]", "\\P{Lower}"},
                {"[:print:]", "\\p{Print}"},
                {"[:^print:]", "\\P{Print}"},
                {"[:punct:]", "\\p{Punct}"},
                {"[:^punct:]", "\\P{Punct}"},
                {"[:space:]", "\\p{Space}"},
                {"[:^space:]", "\\P{Space}"},
                {"[:upper:]", "\\p{Upper}"},
                {"[:^upper:]", "\\P{Upper}"},
                {"[:word:]", "\\p{Alnum}_"},
                {"[:^word:]", "\\P{Alnum}_"},
                {"[:xdigit:]", "\\p{XDigit}"},
                {"[:^xdigit:]", "\\P{XDigit}"}
        };
        for (String[] characterClass : characterClasses) {
            CHARACTER_CLASSES.put(characterClass[0], characterClass[1]);
        }
    }

    /**
     * Preprocesses a given regex string to make it compatible with Java's regex engine.
     * This involves handling various constructs and escape sequences that Java does not
     * natively support or requires in a different format.
     *
     * @param s                  The regex string to preprocess.
     * @param flag_xx            A flag indicating whether to treat spaces as tokens.
     * @param stopAtClosingParen A flag indicating whether to stop processing at the first closing parenthesis.
     * @return A preprocessed regex string compatible with Java's regex engine.
     * @throws IllegalArgumentException If there are unmatched parentheses in the regex.
     */
    static Pair preProcessRegex(String s, boolean flag_xx, boolean stopAtClosingParen) {
        final int length = s.length();
        StringBuilder sb = new StringBuilder();
        StringBuilder rejected = new StringBuilder();
        int offset = 0;

        // Remove \G from the pattern string for Java compilation
        if (s.startsWith("\\G")) {
            offset += 2;
        }

        while (offset < length) {
            final int c = s.codePointAt(offset);
            switch (c) {
                case '\\':  // Handle escape sequences
                    offset = handleEscapeSequences(s, sb, c, offset, length);
                    break;
                case '[':   // Handle character classes
                    offset = handleCharacterClass(s, flag_xx, sb, c, offset, length, rejected);
                    break;
                case '(':
                    offset = handleParentheses(s, offset, length, sb, c, flag_xx);

                    // Ensure the closing parenthesis is consumed
                    if (offset >= length || s.codePointAt(offset) != ')') {
                        throw new IllegalArgumentException("Unterminated ( in regex; marked by <-- HERE in m/" +
                                s.substring(0, offset) + " <-- HERE " + s.substring(offset) + "/");
                    }
                    sb.append(')');
                    break;
                case ')':
                    if (stopAtClosingParen) {
                        return new Pair(sb.toString(), offset);
                    }
                    // Check for unmatched closing parenthesis
                    throw new IllegalArgumentException("Unmatched ) in regex; marked by <-- HERE in m/" +
                            s.substring(0, offset) + " <-- HERE " + s.substring(offset) + "/");
                default:    // Append normal characters
                    sb.append(Character.toChars(c));
                    break;
            }
            offset++;
        }

        return new Pair(sb.toString(), offset);
    }

    private static int handleParentheses(String s, int offset, int length, StringBuilder sb, int c, boolean flag_xx) {
        boolean append = true;
        if (offset < length - 3) {
            int c2 = s.codePointAt(offset + 1);
            int c3 = s.codePointAt(offset + 2);
            int c4 = s.codePointAt(offset + 3);
            if (c2 == '?' && c3 == '#') {
                // Remove inline comments (?# ... )
                offset = handleSkipComment(offset, s, length);
                return offset;
            } else if (c2 == '?' && c3 == '^') {
                // Handle (?^ism: ... ) construct
                offset = handleFlagModifiers(s, offset, length, sb, flag_xx);
                return offset;
            } else if (c2 == '?' && c3 == '<' && c4 != '=') {
                // Handle named capture (?<name> ... )
                offset = handleNamedCapture(s, offset, length, sb, flag_xx);
                return offset;
            }
        }
        // Recursively preprocess the content inside the parentheses
        sb.append('(');
        Pair insideParens = preProcessRegex(s.substring(offset + 1), flag_xx, true);
        sb.append(insideParens.processed);
        offset += insideParens.consumed + 1; // Move past the processed content
        return offset;
    }

    private static int handleFlagModifiers(String s, int offset, int length, StringBuilder sb, boolean flag_xx) {
        int start = offset + 3; // Skip past '(?^'
        int colonPos = s.indexOf(':', start);
        int closeParen = s.indexOf(')', start);

        if (colonPos == -1 || closeParen < colonPos) {
            if (closeParen == -1) {
                return closeParen;
            }
            // (?^i)pattern - flags apply to rest of pattern
            handleFlags result = getHandleFlags(s, start, closeParen);
            sb.append("(?").append(result.javaFlags);
            return closeParen;
        }

        handleFlags result = getHandleFlags(s, start, colonPos);

        // Preprocess the subpattern (the part after the ':')
        Pair content = preProcessRegex(s.substring(colonPos + 1), flag_xx, true);

        // Append the modified regex to the StringBuilder
        sb.append("(?").append(result.javaFlags()).append(":").append(content.processed);

        // Calculate the new offset
        return offset + 3 + result.flags().length() + content.consumed + 1; // Move past the processed content
    }

    private static handleFlags getHandleFlags(String s, int start, int colonPos) {
        String flags = s.substring(start, colonPos);
        Set<Character> positiveFlags = new HashSet<>();
        Set<Character> negativeFlags = new HashSet<>();

        // Single pass to collect flags
        for (int i = 0; i < flags.length(); i++) {
            char flag = flags.charAt(i);
            if (flag == '-' && i + 1 < flags.length()) {
                negativeFlags.add(flags.charAt(++i));
            } else if ("imsx".indexOf(flag) != -1) {
                positiveFlags.add(flag);
            }
        }

        // Add negative versions of standard flags not explicitly set
        for (char standardFlag : "imsx".toCharArray()) {
            if (!positiveFlags.contains(standardFlag) && !negativeFlags.contains(standardFlag)) {
                negativeFlags.add(standardFlag);
            }
        }

        // Build Java flags string
        StringBuilder javaFlags = new StringBuilder();

        // Add positive flags
        for (char flag : "imsx".toCharArray()) {
            if (positiveFlags.contains(flag)) {
                javaFlags.append(flag);
            }
        }

        // Add negative flags
        if (!negativeFlags.isEmpty()) {
            javaFlags.append('-');
            for (char flag : "imsx".toCharArray()) {
                if (negativeFlags.contains(flag)) {
                    javaFlags.append(flag);
                }
            }
        }

        return new handleFlags(flags, javaFlags.toString());
    }

    private record handleFlags(String flags, String javaFlags) {
    }

    private static int handleNamedCapture(String s, int offset, int length, StringBuilder sb, boolean flag_xx) {
        int start = offset + 3; // Skip past '(?<'
        int end = s.indexOf('>', start);
        if (end == -1) {
            throw new IllegalArgumentException("Unterminated named capture in regex; marked by <-- HERE in m/" +
                    s.substring(0, start) + " <-- HERE " + s.substring(start) + "/");
        }
        String name = s.substring(start, end);
        Pair content = preProcessRegex(s.substring(end + 1), flag_xx, true); // Process content inside the group
        sb.append("(?<").append(name).append(">").append(content.processed);
        return end + content.consumed + 1;
    }

    private static int handleCharacterClass(String s, boolean flag_xx, StringBuilder sb, int c, int offset, int length, StringBuilder rejected) {
        int len = sb.length();
        sb.append(Character.toChars(c));
        offset++;
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

    private static int handleEscapeSequences(String s, StringBuilder sb, int c, int offset, int length) {
        sb.append(Character.toChars(c));
        offset++;
        if (offset >= length) {
            return offset;
        }

        char nextChar = s.charAt(offset);
        if (nextChar == 'Q') {
            // Handle \Q - quote until \E or end of pattern
            System.out.println("Start \\Q-quoted string");
            sb.append('Q');
            offset++;
            while (offset < length) {
                int currentChar = s.codePointAt(offset);
                if (currentChar == '\\' && offset + 1 < length && s.charAt(offset + 1) == 'E') {
                    sb.append("\\E");
                    return offset + 1;
                }
                sb.append(Character.toChars(currentChar));
                offset++;
            }
            sb.append("\\E");
            return offset - 1;
        } else if (nextChar == 'N' && offset + 1 < length && s.charAt(offset + 1) == '{') {
            // Handle \N{name} constructs
            offset += 2; // Skip past \N{
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
                            int codePoint = getCodePointFromName(name);
                            sb.append(String.format("x{%X}", codePoint));
                            offset = endBrace;
                        } else {
                            throw new IllegalArgumentException("Unmatched brace in \\N{name} construct");
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
                case ' ':
                    if (flag_xx) {
                        sb.append(Character.toChars(c));
                    } else {
                        sb.append("\\ ");   // make this space a "token", even inside /x
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
        for (Map.Entry<String, String> entry : CHARACTER_CLASSES.entrySet()) {
            String className = entry.getKey();
            String classReplacement = entry.getValue();
            if (offset + className.length() - 1 < length && s.startsWith(className, offset)) {
                sb.append(classReplacement);
                return offset + className.length() - 1;
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
     * Retrieves the Unicode code point for a given character name.
     *
     * @param name The name of the Unicode character.
     * @return The Unicode code point.
     * @throws IllegalArgumentException If the name is invalid or not found.
     */
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

    public record Pair(String processed, int consumed) {
    }
}

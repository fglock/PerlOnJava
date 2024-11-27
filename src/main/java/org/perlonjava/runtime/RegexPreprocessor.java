package org.perlonjava.runtime;

import com.ibm.icu.lang.UCharacter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    // [xx \b xx]  becomes: (?:[xx xx]|\b) - Java doesn't support \b as a character
    // \N{name}    named Unicode character or character sequence
    // \N{U+263D}  Unicode character
    // \G       \G is removed, it is handled separately
    //
    // inside [ ... ]
    //      space    becomes: "\ " unless the /xx flag is used (flag_xx)
    //      \120     becomes: \0120 - Java requires octal sequences to start with zero
    //      \0       becomes: \00 - Java requires the extra zero
    //      [:ascii:]  becomes: \p{ASCII}
    //      [:^ascii:] becomes: \P{ASCII}
    //      \b       is moved, Java doesn't support \b inside [...]
    //
    // WIP:
    // named capture (?<one> ... ) replace underscore in name

    /**
     * Preprocesses a given regex string to make it compatible with Java's regex engine.
     * This involves handling various constructs and escape sequences that Java does not
     * natively support or requires in a different format.
     *
     * @param s       The regex string to preprocess.
     * @param flag_xx A flag indicating whether to treat spaces as tokens.
     * @return A preprocessed regex string compatible with Java's regex engine.
     */
    static String preProcessRegex(String s, boolean flag_xx) {
        final int length = s.length();
        int named_capture_count = 0;
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
                case '[':   // Handle character classes
                    int len = sb.length();
                    sb.append(Character.toChars(c));
                    offset++;
                    offset = _regex_character_class_escape(offset, s, sb, length, flag_xx, rejected);
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
                    break;
                case '(':
                    boolean append = true;
                    if (offset < length - 3) {
                        int c2 = s.codePointAt(offset + 1);
                        int c3 = s.codePointAt(offset + 2);
                        int c4 = s.codePointAt(offset + 3);
                        if (c2 == '?' && c3 == '#') {
                            // Remove inline comments (?# ... )
                            offset = _regex_skip_comment(offset, s, length);
                            append = false;
                        }

                        // Named capture (?<name> ... ) - WIP
                        // Replace underscore in name
                        // This section is currently commented out and marked as work in progress.
                        //
                        // } else handleUnderscoreInNamedCapture(c2, c3, c4);

                    }
                    if (append) {
                        sb.append(Character.toChars(c));
                    }
                    break;
                default:    // Append normal characters
                    sb.append(Character.toChars(c));
                    break;
            }
            offset++;
        }
        return sb.toString();
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
    private static int _regex_character_class_escape(int offset, String s, StringBuilder sb, int length, boolean flag_xx,
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
                case '\\':  // escape - \[ \120

                    if (s.codePointAt(offset + 1) == 'b') {
                        rejected.append("\\b");      // Java doesn't support \b inside [...]
                        offset++;
                        break;
                    }

                    sb.append(Character.toChars(c));
                    offset++;
                    int c2 = s.codePointAt(offset);
                    if (c2 >= '1' && c2 <= '3') {
                        if (offset < length + 1) {
                            int off = offset;
                            int c3 = s.codePointAt(off++);
                            int c4 = s.codePointAt(off++);
                            if ((c3 >= '0' && c3 <= '7') && (c4 >= '0' && c4 <= '7')) {
                                // a \000 octal sequence
                                sb.append('0');
                            }
                        }
                    } else if (c2 == '0') {
                        // rewrite \0 to \00
                        sb.append('0');
                    }
                    sb.append(Character.toChars(c2));
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
            String className = characterClass[0];
            String classReplacement = characterClass[1];
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
    private static int _regex_skip_comment(int offset, String s, int length) {
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
     *  WIP - replace undescore in named capture
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

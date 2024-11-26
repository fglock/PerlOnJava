package org.perlonjava.runtime;

import com.ibm.icu.lang.UCharacter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexPreprocessor {

    public static String replaceNamedCharacters(String pattern) {
        // Compile a regex pattern to find \N{name} constructs
        Pattern namedCharPattern = Pattern.compile("\\\\N\\{([^}]+)\\}");
        Matcher matcher = namedCharPattern.matcher(pattern);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1).trim();
            int codePoint;
            if (name.startsWith("U+")) {
                // Handle \N{U+263D} format
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

            // Replace the match with the escaped Unicode representation
            matcher.appendReplacement(result, String.format("\\\\x{%X}", codePoint));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static String preProcessRegex(String patternString, boolean flag_xx) {
        // Remove \G from the pattern string for Java compilation
        String javaPatternString = patternString.replace("\\G", "");

        // Find \N{name} constructs
        javaPatternString = replaceNamedCharacters(javaPatternString);

        // Replace [:ascii:] with Java's \p{ASCII}
        javaPatternString = javaPatternString.replace("[:ascii:]", "\\p{ASCII}");

        // Replace [:^ascii:] with Java's \\P{ASCII}
        javaPatternString = javaPatternString.replace("[:^ascii:]", "\\P{ASCII}");

        // Replace [:^print:] with Java's \\P{Print}
        javaPatternString = javaPatternString.replace("[:^print:]", "\\P{Print}");

        // Replace [:print:] with Java's \\p{Print}
        javaPatternString = javaPatternString.replace("[:print:]", "\\p{Print}");

        javaPatternString = regex_escape(javaPatternString, flag_xx);

        return javaPatternString;
    }


    // regex escape rules:
    //
    // \[       as-is
    // [xx xx]  becomes: [xx\ xx] - this will make sure space is a token, even when /x modifier is set
    // \120     becomes: \0120 - Java requires octal sequences to start with zero
    // \0       becomes: \00 - Java requires the extra zero
    // (?#...)  inline comment is removed
    // [xx \b xx]  becomes: (?:[xx xx]|\b) - java doesn't support \b as a character
    //
    // WIP:
    // named capture (?<one> ... ) replace underscore in name
    // /xx flag
    private static String regex_escape(String s, boolean flag_xx) {
        // escape spaces in character classes
        final int length = s.length();
        int named_capture_count = 0;
        StringBuilder sb = new StringBuilder();
        StringBuilder rejected = new StringBuilder();
        // System.out.println("regex_escape " + s );
        for (int offset = 0; offset < length; ) {
            final int c = s.codePointAt(offset);
            switch (c) {
                case '\\':  // escape - \[ \120
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
                case '[':   // character class
                    int len = sb.length();
                    sb.append(Character.toChars(c));
                    offset++;
                    offset = _regex_character_class_escape(offset, s, sb, length, flag_xx, rejected);
                    if (!rejected.isEmpty()) {
                        // process \b inside character class
                        String subseq;
                        if ((sb.length() - len) == 2) {
                            subseq = "(?:" + rejected + ")";
                        } else {
                            subseq = "(?:" + sb.substring(len) + "|" + rejected + ")";
                        }
                        // PlCORE.warn(PlCx.VOID, new PlArray(new PlString("Rejected: " + subseq)));
                        rejected.setLength(0);
                        sb.setLength(len);
                        sb.append(subseq);
                    }
                    break;
                case '(':
                    boolean append = true;
                    if (offset < length - 3) {
                        c2 = s.codePointAt(offset + 1);
                        int c3 = s.codePointAt(offset + 2);
                        int c4 = s.codePointAt(offset + 3);
                        // System.out.println("regex_escape at (" + c2 + c3 + c4 );
                        if (c2 == '?' && c3 == '#') {
                            // comment (?# ... )
                            offset = _regex_skip_comment(offset, s, length);
                            append = false;
                        } else if (c2 == '?' && c3 == '<' &&
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
                    if (append) {
                        sb.append(Character.toChars(c));
                    }
                    break;
                default:    // normal char
                    sb.append(Character.toChars(c));
                    break;
            }
            offset++;
        }
        return sb.toString();
    }

    private static int _regex_character_class_escape(int offset, String s, StringBuilder sb, int length, boolean flag_xx,
                                                     StringBuilder rejected) {
        // inside [ ... ]
        //      space    becomes: "\ " unless the /xx flag is used (flag_xx)
        //      \120     becomes: \0120 - Java requires octal sequences to start with zero
        //      \0       becomes: \00 - Java requires the extra zero
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
                    sb.append("\\[");
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

    private static int _regex_skip_comment(int offset, String s, int length) {
        // [ ... ]
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

}

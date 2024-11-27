package org.perlonjava.runtime;

import com.ibm.icu.lang.UCharacter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexPreprocessor {

    // regex escape rules:
    //
    // \[       as-is
    // \120     becomes: \0120 - Java requires octal sequences to start with zero
    // \0       becomes: \00 - Java requires the extra zero
    // (?#...)  inline comment is removed
    // [xx \b xx]  becomes: (?:[xx xx]|\b) - java doesn't support \b as a character
    // /xx flag:
    //          [xx xx]  becomes: [xx\ xx] - this will make sure space is a token, even when /x modifier is set
    // \N{name}    named Unicode character or character sequence
    // \N{U+263D}  Unicode character
    // \G       \G is removed
    //
    // WIP:
    // named capture (?<one> ... ) replace underscore in name
    //
    static String preProcessRegex(String s, boolean flag_xx) {
        // System.out.println("javaPatternString: " + javaPatternString);
        final int length = s.length();
        int named_capture_count = 0;
        StringBuilder sb = new StringBuilder();
        StringBuilder rejected = new StringBuilder();
        // System.out.println("regex_escape " + s );
        int offset = 0;
        if (s.startsWith("\\G")) {
            // Remove \G from the pattern string for Java compilation
            offset += 2;
        }
        while (offset < length) {
            final int c = s.codePointAt(offset);
            switch (c) {
                case '\\':  // escape - \[ \120
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
                                    // a \000 octal sequence
                                    sb.append('0');
                                }
                            }
                        } else if (c2 == '0') {
                            // rewrite \0 to \00
                            sb.append('0');
                        }
                        sb.append(Character.toChars(c2));
                    }
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
                        int c2 = s.codePointAt(offset + 1);
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

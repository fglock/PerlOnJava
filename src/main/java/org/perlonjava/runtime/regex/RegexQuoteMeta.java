package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.ArrayList;
import java.util.List;

public class RegexQuoteMeta {
    private static final ThreadLocal<List<String>> WARNINGS_ON_USE = ThreadLocal.withInitial(ArrayList::new);

    public static String escapeQ(String s) {
        WARNINGS_ON_USE.get().clear();
        StringBuilder sb = new StringBuilder();
        int len = s.length();
        int offset = 0;
        boolean inCharClass = false;
        boolean charClassFirst = false;
        boolean escaped = false;

        // Predefined set of regex metacharacters
        final String regexMetacharacters = "-.+*?[](){}^$|\\";

        while (offset < len) {
            char c = s.charAt(offset);
            if (escaped) {
                if (inCharClass && (c == 'Q' || c == 'E')) {
                    warnUnrecognizedCharClassEscape(c);
                    sb.append(c);
                    if (charClassFirst && c != '^') {
                        charClassFirst = false;
                    }
                    escaped = false;
                    offset++;
                    continue;
                }
                sb.append('\\');
                sb.append(c);
                escaped = false;
                offset++;
                continue;
            }

            if (c == '\\' && offset + 1 < len && s.charAt(offset + 1) == 'Q') {
                if (inCharClass) {
                    warnUnrecognizedCharClassEscape('Q');
                    sb.append('Q');
                    if (charClassFirst) {
                        charClassFirst = false;
                    }
                    offset += 2;
                    continue;
                }
                // Skip past \Q
                offset += 2;

                // Process characters until \E or end of string
                while (offset < len) {
                    if (s.charAt(offset) == '\\' && offset + 1 < len && s.charAt(offset + 1) == 'E') {
                        // Skip past \E and stop quoting
                        offset += 2;
                        break;
                    }

                    // Escape regex metacharacters
                    char currentChar = s.charAt(offset);
                    if (regexMetacharacters.indexOf(currentChar) != -1) {
                        sb.append('\\'); // Escape the metacharacter
                    }
                    sb.append(currentChar);
                    offset++;
                }
            } else {
                if (c == '\\') {
                    escaped = true;
                    offset++;
                    continue;
                }
                if (c == '[' && !inCharClass) {
                    inCharClass = true;
                    charClassFirst = true;
                } else if (c == ']' && inCharClass && !charClassFirst) {
                    inCharClass = false;
                } else if (inCharClass && charClassFirst && c != '^') {
                    charClassFirst = false;
                }
                sb.append(c);
                offset++;
            }
        }
        if (escaped) {
            sb.append('\\');
        }

        return sb.toString();
    }

    public static List<String> getWarningsOnUse() {
        return new ArrayList<>(WARNINGS_ON_USE.get());
    }

    private static void warnUnrecognizedCharClassEscape(char c) {
        String message = "Unrecognized escape \\" + c + " in character class passed through in regex";
        WARNINGS_ON_USE.get().add(message);
        WarnDie.warnWithCategory(new RuntimeScalar(message), new RuntimeScalar(""), "regexp");
    }
}

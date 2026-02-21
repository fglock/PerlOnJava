package org.perlonjava.runtime.regex;

public class RegexQuoteMeta {
    public static String escapeQ(String s) {
        StringBuilder sb = new StringBuilder();
        int len = s.length();
        int offset = 0;

        // Predefined set of regex metacharacters
        final String regexMetacharacters = "-.+*?[](){}^$|\\";

        while (offset < len) {
            char c = s.charAt(offset);
            if (c == '\\' && offset + 1 < len && s.charAt(offset + 1) == 'Q') {
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
                sb.append(c);
                offset++;
            }
        }

        return sb.toString();
    }
}

package org.perlonjava.runtime.regex;

import java.util.ArrayList;
import java.util.List;

/** Builds Java-group to Perl-group mappings for {@code (?|...)} patterns. */
final class BranchResetCaptureMap {
    private BranchResetCaptureMap() {
    }

    static int[] build(String pattern) {
        if (pattern == null || !pattern.contains("(?|")) return null;
        Parser parser = new Parser(pattern);
        parser.parseAlternatives(false, '\0');
        return parser.mapping.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final class Parser {
        private final String pattern;
        private final List<Integer> mapping = new ArrayList<>();
        private int offset;
        private int nextGroup;

        private Parser(String pattern) {
            this.pattern = pattern;
        }

        private void parseAlternatives(boolean resetNumbers, char terminator) {
            int branchBase = nextGroup;
            int branchMaximum = nextGroup;
            while (offset < pattern.length()) {
                char ch = pattern.charAt(offset);
                if (ch == terminator) {
                    offset++;
                    break;
                }
                if (ch == '|' && terminator != '\0') {
                    branchMaximum = Math.max(branchMaximum, nextGroup);
                    if (resetNumbers) nextGroup = branchBase;
                    offset++;
                    continue;
                }
                if (ch == '\\') {
                    offset += Math.min(2, pattern.length() - offset);
                    continue;
                }
                if (ch == '[') {
                    skipCharacterClass();
                    continue;
                }
                if (ch != '(') {
                    offset++;
                    continue;
                }
                parseGroup();
            }
            if (resetNumbers) nextGroup = Math.max(branchMaximum, nextGroup);
        }

        private void parseGroup() {
            if (pattern.startsWith("(?#", offset)) {
                offset += 3;
                while (offset < pattern.length() && pattern.charAt(offset) != ')') offset++;
                if (offset < pattern.length()) offset++;
                return;
            }

            boolean branchReset = pattern.startsWith("(?|", offset);
            boolean capturing = isCapturingGroup(offset);
            offset += branchReset ? 3 : groupPrefixLength(offset);
            if (capturing) mapping.add(++nextGroup);
            parseAlternatives(branchReset, ')');
        }

        private boolean isCapturingGroup(int start) {
            if (start + 1 >= pattern.length() || pattern.charAt(start + 1) != '?') return true;
            if (pattern.startsWith("(?<", start)) {
                return start + 3 < pattern.length()
                        && pattern.charAt(start + 3) != '='
                        && pattern.charAt(start + 3) != '!';
            }
            return pattern.startsWith("(?P<", start) || pattern.startsWith("(?'", start);
        }

        private int groupPrefixLength(int start) {
            if (start + 1 >= pattern.length() || pattern.charAt(start + 1) != '?') return 1;
            if (pattern.startsWith("(?P<", start)) return namedPrefixEnd(start, start + 4, '>');
            if (pattern.startsWith("(?<", start) && isCapturingGroup(start)) {
                return namedPrefixEnd(start, start + 3, '>');
            }
            if (pattern.startsWith("(?'", start)) return namedPrefixEnd(start, start + 3, '\'');
            int colon = pattern.indexOf(':', start + 2);
            int close = pattern.indexOf(')', start + 2);
            if (colon >= 0 && (close < 0 || colon < close)) return colon - start + 1;
            return 2;
        }

        private int namedPrefixEnd(int groupStart, int nameStart, char delimiter) {
            int end = pattern.indexOf(delimiter, nameStart);
            return end < 0 ? 2 : end - groupStart + 1;
        }

        private void skipCharacterClass() {
            offset++;
            boolean escaped = false;
            while (offset < pattern.length()) {
                char ch = pattern.charAt(offset++);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == ']') {
                    return;
                }
            }
        }
    }
}

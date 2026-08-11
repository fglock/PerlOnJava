package org.perlonjava.runtime.regex;

import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.NameEntry;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.joni.Syntax;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stack-based regex backend for declarative recursive subpatterns.
 */
final class JoniRegexPattern {
    private final Regex regex;
    private final String sourcePattern;
    private final Map<String, Integer> namedGroups;

    JoniRegexPattern(String perlPattern, RegexFlags flags) {
        sourcePattern = translatePattern(perlPattern);
        byte[] bytes = sourcePattern.getBytes(StandardCharsets.UTF_8);
        regex = new Regex(bytes, 0, bytes.length, toJoniOptions(flags),
                UTF8Encoding.INSTANCE, Syntax.RUBY);
        namedGroups = collectNamedGroups(regex);
    }

    RegexMatcher matcher(String input) {
        return new JoniRegexMatcher(regex, sourcePattern, namedGroups, input);
    }

    String patternDescription() {
        return sourcePattern;
    }

    private static int toJoniOptions(RegexFlags flags) {
        int options = Option.NONE;
        if (flags.isCaseInsensitive()) options |= Option.IGNORECASE;
        if (flags.isExtended()) options |= Option.EXTEND;
        // Oniguruma's MULTILINE option controls whether dot matches newline.
        if (flags.isDotAll()) options |= Option.MULTILINE;
        if (flags.isAscii()) options |= Option.ASCII_RANGE;
        // Ruby/Oniguruma syntax implicitly makes unnamed groups non-capturing
        // when a pattern also contains named groups. Perl keeps both kinds of
        // captures numbered. Force that behavior unless /n explicitly disables
        // unnamed captures.
        if (flags.isNonCapturing()) options |= Option.DONT_CAPTURE_GROUP;
        else options |= Option.CAPTURE_GROUP;
        return options;
    }

    static boolean requiresRecursiveBackend(String pattern) {
        if (pattern == null) return false;
        return pattern.matches("(?s).*\\(\\?[+-]?\\d+\\).*" )
                || pattern.contains("(?&")
                || pattern.contains("(?P>");
    }

    static String translatePattern(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        boolean escaped = false;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                out.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '[') {
                inClass = true;
                out.append(ch);
                continue;
            }
            if (ch == ']' && inClass) {
                inClass = false;
                out.append(ch);
                continue;
            }
            if (!inClass && pattern.startsWith("(?^", i)) {
                int colon = pattern.indexOf(':', i + 3);
                if (colon > i) {
                    out.append("(?");
                    for (int p = i + 3; p < colon; p++) {
                        char modifier = pattern.charAt(p);
                        if (modifier == 'i' || modifier == 'm'
                                || modifier == 's' || modifier == 'x'
                                || modifier == '-') {
                            out.append(modifier);
                        }
                    }
                    out.append(':');
                    i = colon;
                    continue;
                }
            }
            if (!inClass && pattern.startsWith("(?&", i)) {
                int end = pattern.indexOf(')', i + 3);
                if (end > i) {
                    out.append("\\g<").append(pattern, i + 3, end).append('>');
                    i = end;
                    continue;
                }
            }
            if (!inClass && pattern.startsWith("(?P>", i)) {
                int end = pattern.indexOf(')', i + 4);
                if (end > i) {
                    out.append("\\g<").append(pattern, i + 4, end).append('>');
                    i = end;
                    continue;
                }
            }
            if (!inClass && ch == '(' && i + 3 < pattern.length() && pattern.charAt(i + 1) == '?') {
                int p = i + 2;
                if (pattern.charAt(p) == '+' || pattern.charAt(p) == '-') p++;
                int digits = p;
                while (p < pattern.length() && Character.isDigit(pattern.charAt(p))) p++;
                if (p > digits && p < pattern.length() && pattern.charAt(p) == ')') {
                    out.append("\\g<").append(pattern, i + 2, p).append('>');
                    i = p;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static Map<String, Integer> collectNamedGroups(Regex regex) {
        Map<String, Integer> names = new LinkedHashMap<>();
        Iterator<NameEntry> iterator = regex.namedBackrefIterator();
        while (iterator.hasNext()) {
            NameEntry entry = iterator.next();
            String name = new String(entry.name, entry.nameP, entry.nameEnd - entry.nameP,
                    StandardCharsets.UTF_8);
            int[] refs = entry.getBackRefs();
            if (refs.length > 0) names.put(name, refs[refs.length - 1]);
        }
        return names;
    }

    private static final class JoniRegexMatcher implements RegexMatcher {
        private final Regex regex;
        private final String sourcePattern;
        private final Map<String, Integer> namedGroups;
        private final String input;
        private final byte[] bytes;
        private final int[] charToByte;
        private final int[] byteToChar;
        private Matcher matcher;
        private Region captures;
        private int regionStart;
        private int regionEnd;
        private int nextStart;
        private boolean matched;

        JoniRegexMatcher(Regex regex, String sourcePattern, Map<String, Integer> namedGroups,
                         String input) {
            this.regex = regex;
            this.sourcePattern = sourcePattern;
            this.namedGroups = namedGroups;
            this.input = input;
            this.bytes = input.getBytes(StandardCharsets.UTF_8);
            this.charToByte = buildCharToByte(input);
            this.byteToChar = buildByteToChar(input, bytes.length, charToByte);
            region(0, input.length());
        }

        @Override
        public boolean find() {
            if (nextStart > regionEnd) {
                matched = false;
                return false;
            }
            matcher = regex.matcher(bytes);
            int result = matcher.search(charToByte[nextStart], charToByte[regionEnd], Option.NONE);
            matched = result >= 0;
            if (!matched) return false;
            captures = matcher.getEagerRegion();
            int start = start();
            int end = end();
            nextStart = end > start ? end : advanceCodePoint(end);
            return true;
        }

        @Override
        public void region(int start, int end) {
            regionStart = Math.max(0, Math.min(start, input.length()));
            regionEnd = Math.max(regionStart, Math.min(end, input.length()));
            nextStart = regionStart;
            matched = false;
        }

        @Override public void useAnchoringBounds(boolean enabled) { }
        @Override public void useTransparentBounds(boolean enabled) { }
        @Override public int start() { return toCharOffset(matcher.getBegin()); }
        @Override public int end() { return toCharOffset(matcher.getEnd()); }
        @Override public int start(int index) { return groupOffset(index, true); }
        @Override public int end(int index) { return groupOffset(index, false); }
        @Override public int start(String name) { return groupOffset(name, true); }
        @Override public int end(String name) { return groupOffset(name, false); }

        @Override
        public String group(int index) {
            requireMatch();
            int begin = index == 0 ? matcher.getBegin() : captures.getBeg(index);
            int end = index == 0 ? matcher.getEnd() : captures.getEnd(index);
            if (begin < 0 || end < 0) return null;
            return input.substring(toCharOffset(begin), toCharOffset(end));
        }

        @Override
        public String group(String name) {
            int group = namedGroupNumber(name);
            return group(group);
        }

        @Override public int groupCount() { return regex.numberOfCaptures(); }
        @Override public Map<String, Integer> namedGroups() { return namedGroups; }
        @Override public String patternDescription() { return sourcePattern; }

        private int groupOffset(String name, boolean begin) {
            requireMatch();
            int group = namedGroupNumber(name);
            return groupOffset(group, begin);
        }

        private int groupOffset(int group, boolean begin) {
            requireMatch();
            if (group == 0) return begin ? start() : end();
            int offset = begin ? captures.getBeg(group) : captures.getEnd(group);
            return offset < 0 ? -1 : toCharOffset(offset);
        }

        private int namedGroupNumber(String name) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            return regex.nameToBackrefNumber(nameBytes, 0, nameBytes.length,
                    UTF8Encoding.INSTANCE, captures);
        }

        private int advanceCodePoint(int offset) {
            return offset >= regionEnd ? regionEnd + 1
                    : offset + Character.charCount(input.codePointAt(offset));
        }

        private int toCharOffset(int byteOffset) {
            if (byteOffset < 0 || byteOffset >= byteToChar.length) return -1;
            return byteToChar[byteOffset];
        }

        private void requireMatch() {
            if (!matched) throw new IllegalStateException("No successful match");
        }

        private static int[] buildCharToByte(String input) {
            int[] offsets = new int[input.length() + 1];
            int byteOffset = 0;
            for (int i = 0; i < input.length();) {
                offsets[i] = byteOffset;
                int cp = input.codePointAt(i);
                int chars = Character.charCount(cp);
                int bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                if (chars == 2) offsets[i + 1] = byteOffset;
                i += chars;
                byteOffset += bytes;
                offsets[i] = byteOffset;
            }
            return offsets;
        }

        private static int[] buildByteToChar(String input, int byteLength, int[] charToByte) {
            int[] offsets = new int[byteLength + 1];
            int charOffset = 0;
            for (int b = 0; b <= byteLength; b++) {
                while (charOffset + 1 < charToByte.length && charToByte[charOffset + 1] <= b) {
                    charOffset++;
                }
                offsets[b] = charOffset;
            }
            return offsets;
        }
    }
}

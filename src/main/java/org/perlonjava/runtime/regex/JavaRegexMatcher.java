package org.perlonjava.runtime.regex;

import java.util.Map;
import java.util.regex.Matcher;

final class JavaRegexMatcher implements RegexMatcher {
    private final Matcher matcher;
    private final int[] javaToPerlGroup;
    private final int perlGroupCount;

    JavaRegexMatcher(Matcher matcher) {
        this(matcher, null);
    }

    JavaRegexMatcher(Matcher matcher, int[] javaToPerlGroup) {
        this.matcher = matcher;
        this.javaToPerlGroup = javaToPerlGroup != null
                && javaToPerlGroup.length == matcher.groupCount()
                ? javaToPerlGroup
                : null;
        int maximum = 0;
        if (this.javaToPerlGroup != null) {
            for (int group : this.javaToPerlGroup) maximum = Math.max(maximum, group);
        }
        this.perlGroupCount = this.javaToPerlGroup == null ? matcher.groupCount() : maximum;
    }

    Matcher unwrap() {
        return matcher;
    }

    @Override public boolean find() { return matcher.find(); }
    @Override public void region(int start, int end) { matcher.region(start, end); }
    @Override public void useAnchoringBounds(boolean enabled) { matcher.useAnchoringBounds(enabled); }
    @Override public void useTransparentBounds(boolean enabled) { matcher.useTransparentBounds(enabled); }
    @Override public int start() { return matcher.start(); }
    @Override public int end() { return matcher.end(); }
    @Override public int start(int index) { return mappedOffset(index, true); }
    @Override public int end(int index) { return mappedOffset(index, false); }
    @Override public int start(String name) { return matcher.start(name); }
    @Override public int end(String name) { return matcher.end(name); }
    @Override public String group(int index) {
        if (javaToPerlGroup == null || index == 0) return matcher.group(index);
        for (int javaGroup = 1; javaGroup <= javaToPerlGroup.length; javaGroup++) {
            if (javaToPerlGroup[javaGroup - 1] == index && matcher.start(javaGroup) >= 0) {
                return matcher.group(javaGroup);
            }
        }
        return null;
    }
    @Override public String group(String name) { return matcher.group(name); }
    @Override public int groupCount() { return perlGroupCount; }
    @Override public Map<String, Integer> namedGroups() { return matcher.pattern().namedGroups(); }
    @Override public String patternDescription() { return matcher.pattern().pattern(); }

    private int mappedOffset(int index, boolean start) {
        if (javaToPerlGroup == null || index == 0) {
            return start ? matcher.start(index) : matcher.end(index);
        }
        for (int javaGroup = 1; javaGroup <= javaToPerlGroup.length; javaGroup++) {
            if (javaToPerlGroup[javaGroup - 1] == index && matcher.start(javaGroup) >= 0) {
                return start ? matcher.start(javaGroup) : matcher.end(javaGroup);
            }
        }
        return -1;
    }
}

package org.perlonjava.runtime.regex;

import java.util.Map;
import java.util.regex.Matcher;

final class JavaRegexMatcher implements RegexMatcher {
    private final Matcher matcher;
    JavaRegexMatcher(Matcher matcher) {
        this.matcher = matcher;
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
    @Override public int start(int index) { return matcher.start(index); }
    @Override public int end(int index) { return matcher.end(index); }
    @Override public int start(String name) { return matcher.start(name); }
    @Override public int end(String name) { return matcher.end(name); }
    @Override public String group(int index) {
        return matcher.group(index);
    }
    @Override public String group(String name) { return matcher.group(name); }
    @Override public int groupCount() { return matcher.groupCount(); }
    @Override public Map<String, Integer> namedGroups() { return matcher.pattern().namedGroups(); }
    @Override public String patternDescription() { return matcher.pattern().pattern(); }
}

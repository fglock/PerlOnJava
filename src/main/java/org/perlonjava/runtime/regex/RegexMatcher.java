package org.perlonjava.runtime.regex;

import java.util.Map;

/**
 * Backend-neutral match cursor used by Perl-visible regex state.
 */
public interface RegexMatcher {
    boolean find();

    void region(int start, int end);

    void useAnchoringBounds(boolean enabled);

    void useTransparentBounds(boolean enabled);

    int start();

    int end();

    int start(int index);

    int end(int index);

    int start(String name);

    int end(String name);

    String group(int index);

    String group(String name);

    int groupCount();

    Map<String, Integer> namedGroups();

    String patternDescription();
}

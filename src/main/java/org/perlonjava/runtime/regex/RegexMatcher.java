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

    /** Most recently closed capture number, or -1 when the backend cannot provide it. */
    default int lastClosedCapture() {
        int selected = -1;
        int selectedEnd = -1;
        for (int group = 1; group <= groupCount(); group++) {
            int end = end(group);
            if (end > selectedEnd || (end == selectedEnd && end >= 0
                    && (selected < 0 || group < selected))) {
                selected = group;
                selectedEnd = end;
            }
        }
        return selected;
    }

    Map<String, Integer> namedGroups();

    String patternDescription();
}

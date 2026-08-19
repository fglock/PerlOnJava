package org.perlonjava.runtime.regex;

import java.util.Map;

/**
 * Backend-neutral match cursor used by Perl-visible regex state.
 */
public interface RegexMatcher {
    boolean find();

    /** Find the next match while rejecting zero-length alternatives. */
    default boolean findNotEmpty() {
        return find() && end() > consumedStart();
    }

    void region(int start, int end);

    void useAnchoringBounds(boolean enabled);

    void useTransparentBounds(boolean enabled);

    /**
     * Set an independent Perl {@code \G} anchor while retaining the current
     * search start. Backends without a separate gpos cursor return false.
     */
    default boolean setGlobalPosition(int position) { return false; }

    int start();

    /**
     * Start of input consumed by the engine before a visible-start reset such
     * as Perl's {@code \K}. Backends without such resets use {@link #start()}.
     */
    default int consumedStart() { return start(); }

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

    default String controlMark() { return null; }

    default String controlError() { return null; }

    String patternDescription();
}

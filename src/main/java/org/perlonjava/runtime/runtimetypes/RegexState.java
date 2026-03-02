package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.regex.Matcher;

/**
 * On-demand snapshot of regex-related global state (Perl's $1, $&amp;, $`, $', etc.).
 *
 * <p>Implements {@link DynamicState} so it integrates with {@link DynamicVariableManager}.
 * Instead of eagerly saving at every block boundary, a snapshot is pushed onto the
 * DynamicVariableManager stack <b>on first regex match</b> within a dynamic scope
 * (subroutine / eval).  {@code popToLocalLevel()} restores it automatically on scope exit.
 *
 * <p>Callers must bracket each dynamic scope with {@link #enterScope()} / {@link #leaveScope()}.
 * The regex runtime calls {@link #saveBeforeMatch()} before modifying globals.
 */
public class RegexState implements DynamicState {
    private static int scopeNestingDepth = 0;
    private static int lastSavedAtDepth = -1;

    private final Matcher globalMatcher;
    private final String globalMatchString;
    private final String lastMatchedString;
    private final int lastMatchStart;
    private final int lastMatchEnd;
    private final String lastSuccessfulMatchedString;
    private final int lastSuccessfulMatchStart;
    private final int lastSuccessfulMatchEnd;
    private final String lastSuccessfulMatchString;
    private final RuntimeRegex lastSuccessfulPattern;
    private final String[] lastCaptureGroups;
    private final int savedDepth;

    private RegexState() {
        this.savedDepth = scopeNestingDepth;
        this.globalMatcher = RuntimeRegex.globalMatcher;
        this.globalMatchString = RuntimeRegex.globalMatchString;
        this.lastMatchedString = RuntimeRegex.lastMatchedString;
        this.lastMatchStart = RuntimeRegex.lastMatchStart;
        this.lastMatchEnd = RuntimeRegex.lastMatchEnd;
        this.lastSuccessfulMatchedString = RuntimeRegex.lastSuccessfulMatchedString;
        this.lastSuccessfulMatchStart = RuntimeRegex.lastSuccessfulMatchStart;
        this.lastSuccessfulMatchEnd = RuntimeRegex.lastSuccessfulMatchEnd;
        this.lastSuccessfulMatchString = RuntimeRegex.lastSuccessfulMatchString;
        this.lastSuccessfulPattern = RuntimeRegex.lastSuccessfulPattern;
        this.lastCaptureGroups = RuntimeRegex.lastCaptureGroups;
    }

    public static void enterScope() {
        scopeNestingDepth++;
    }

    public static void leaveScope() {
        scopeNestingDepth--;
    }

    public static void saveBeforeMatch() {
        if (lastSavedAtDepth < scopeNestingDepth) {
            lastSavedAtDepth = scopeNestingDepth;
            DynamicVariableManager.pushLocalVariable(new RegexState());
        }
    }

    @Override
    public void dynamicSaveState() {
    }

    @Override
    public void dynamicRestoreState() {
        RuntimeRegex.globalMatcher = this.globalMatcher;
        RuntimeRegex.globalMatchString = this.globalMatchString;
        RuntimeRegex.lastMatchedString = this.lastMatchedString;
        RuntimeRegex.lastMatchStart = this.lastMatchStart;
        RuntimeRegex.lastMatchEnd = this.lastMatchEnd;
        RuntimeRegex.lastSuccessfulMatchedString = this.lastSuccessfulMatchedString;
        RuntimeRegex.lastSuccessfulMatchStart = this.lastSuccessfulMatchStart;
        RuntimeRegex.lastSuccessfulMatchEnd = this.lastSuccessfulMatchEnd;
        RuntimeRegex.lastSuccessfulMatchString = this.lastSuccessfulMatchString;
        RuntimeRegex.lastSuccessfulPattern = this.lastSuccessfulPattern;
        RuntimeRegex.lastCaptureGroups = this.lastCaptureGroups;
        if (lastSavedAtDepth >= savedDepth) {
            lastSavedAtDepth = savedDepth - 1;
        }
    }
}

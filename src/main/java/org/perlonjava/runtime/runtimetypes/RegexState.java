package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.regex.Matcher;

/**
 * Snapshot of regex-related global state (Perl's $1, $&amp;, $`, $', etc.).
 *
 * <p>Implements {@link DynamicState} so it integrates with {@link DynamicVariableManager}.
 * A snapshot is pushed onto the DynamicVariableManager stack at subroutine entry via
 * {@link #save()}.  {@code popToLocalLevel()} restores it automatically on scope exit.
 */
public class RegexState implements DynamicState {

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
    private final boolean lastMatchUsedPFlag;
    private final String[] lastCaptureGroups;
    private final boolean lastMatchWasByteString;

    public RegexState() {
        this.globalMatcher = RuntimeRegex.getGlobalMatcher();
        this.globalMatchString = RuntimeRegex.getGlobalMatchString();
        this.lastMatchedString = RuntimeRegex.getLastMatchedString();
        this.lastMatchStart = RuntimeRegex.getLastMatchStart();
        this.lastMatchEnd = RuntimeRegex.getLastMatchEnd();
        this.lastSuccessfulMatchedString = RuntimeRegex.getLastSuccessfulMatchedString();
        this.lastSuccessfulMatchStart = RuntimeRegex.getLastSuccessfulMatchStart();
        this.lastSuccessfulMatchEnd = RuntimeRegex.getLastSuccessfulMatchEnd();
        this.lastSuccessfulMatchString = RuntimeRegex.getLastSuccessfulMatchString();
        this.lastSuccessfulPattern = RuntimeRegex.getLastSuccessfulPattern();
        this.lastMatchUsedPFlag = RuntimeRegex.getLastMatchUsedPFlag();
        this.lastCaptureGroups = RuntimeRegex.getLastCaptureGroups();
        this.lastMatchWasByteString = RuntimeRegex.getLastMatchWasByteString();
    }

    public static void save() {
        DynamicVariableManager.pushLocalVariable(new RegexState());
    }

    @Override
    public void dynamicSaveState() {
    }

    public void restore() {
        dynamicRestoreState();
    }

    @Override
    public void dynamicRestoreState() {
        RuntimeRegex.setGlobalMatcher(this.globalMatcher);
        RuntimeRegex.setGlobalMatchString(this.globalMatchString);
        RuntimeRegex.setLastMatchedString(this.lastMatchedString);
        RuntimeRegex.setLastMatchStart(this.lastMatchStart);
        RuntimeRegex.setLastMatchEnd(this.lastMatchEnd);
        RuntimeRegex.setLastSuccessfulMatchedString(this.lastSuccessfulMatchedString);
        RuntimeRegex.setLastSuccessfulMatchStart(this.lastSuccessfulMatchStart);
        RuntimeRegex.setLastSuccessfulMatchEnd(this.lastSuccessfulMatchEnd);
        RuntimeRegex.setLastSuccessfulMatchString(this.lastSuccessfulMatchString);
        RuntimeRegex.setLastSuccessfulPattern(this.lastSuccessfulPattern);
        RuntimeRegex.setLastMatchUsedPFlag(this.lastMatchUsedPFlag);
        RuntimeRegex.setLastCaptureGroups(this.lastCaptureGroups);
        RuntimeRegex.setLastMatchWasByteString(this.lastMatchWasByteString);
    }
}

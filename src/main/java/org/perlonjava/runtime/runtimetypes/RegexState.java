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
        this.lastMatchUsedPFlag = RuntimeRegex.lastMatchUsedPFlag;
        this.lastCaptureGroups = RuntimeRegex.lastCaptureGroups;
        this.lastMatchWasByteString = RuntimeRegex.lastMatchWasByteString;
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
        RuntimeRegex.lastMatchUsedPFlag = this.lastMatchUsedPFlag;
        RuntimeRegex.lastCaptureGroups = this.lastCaptureGroups;
        RuntimeRegex.lastMatchWasByteString = this.lastMatchWasByteString;
    }
}

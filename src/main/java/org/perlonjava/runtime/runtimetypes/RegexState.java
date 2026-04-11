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
        // Single PerlRuntime.current() lookup instead of 13 separate ones
        PerlRuntime rt = PerlRuntime.current();
        this.globalMatcher = rt.regexGlobalMatcher;
        this.globalMatchString = rt.regexGlobalMatchString;
        this.lastMatchedString = rt.regexLastMatchedString;
        this.lastMatchStart = rt.regexLastMatchStart;
        this.lastMatchEnd = rt.regexLastMatchEnd;
        this.lastSuccessfulMatchedString = rt.regexLastSuccessfulMatchedString;
        this.lastSuccessfulMatchStart = rt.regexLastSuccessfulMatchStart;
        this.lastSuccessfulMatchEnd = rt.regexLastSuccessfulMatchEnd;
        this.lastSuccessfulMatchString = rt.regexLastSuccessfulMatchString;
        this.lastSuccessfulPattern = rt.regexLastSuccessfulPattern;
        this.lastMatchUsedPFlag = rt.regexLastMatchUsedPFlag;
        this.lastCaptureGroups = rt.regexLastCaptureGroups;
        this.lastMatchWasByteString = rt.regexLastMatchWasByteString;
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
        // Single PerlRuntime.current() lookup instead of 13 separate ones
        PerlRuntime rt = PerlRuntime.current();
        rt.regexGlobalMatcher = this.globalMatcher;
        rt.regexGlobalMatchString = this.globalMatchString;
        rt.regexLastMatchedString = this.lastMatchedString;
        rt.regexLastMatchStart = this.lastMatchStart;
        rt.regexLastMatchEnd = this.lastMatchEnd;
        rt.regexLastSuccessfulMatchedString = this.lastSuccessfulMatchedString;
        rt.regexLastSuccessfulMatchStart = this.lastSuccessfulMatchStart;
        rt.regexLastSuccessfulMatchEnd = this.lastSuccessfulMatchEnd;
        rt.regexLastSuccessfulMatchString = this.lastSuccessfulMatchString;
        rt.regexLastSuccessfulPattern = this.lastSuccessfulPattern;
        rt.regexLastMatchUsedPFlag = this.lastMatchUsedPFlag;
        rt.regexLastCaptureGroups = this.lastCaptureGroups;
        rt.regexLastMatchWasByteString = this.lastMatchWasByteString;
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RegexMatcher;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.List;
import java.util.Map;

/** Snapshot of Perl-visible regex state for dynamic-scope restoration. */
public class RegexState implements DynamicState {
    private final PerlRuntime owner;
    private final RegexMatcher globalMatcher;
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
    private final boolean lastMatchUsedBackslashK;
    private final String[] lastCaptureGroups;
    private final String lastClosedCapture;
    private final Map<String, List<String>> lastNamedCaptureGroups;
    private final boolean lastMatchWasByteString;
    private final boolean lastMatchResultsTainted;
    private final int[] manualCaptureStarts;
    private final int[] manualCaptureEnds;

    public RegexState() {
        owner = PerlRuntime.current();
        RuntimeRegexState state = owner.regexState;
        globalMatcher = state.globalMatcher;
        globalMatchString = state.globalMatchString;
        lastMatchedString = state.lastMatchedString;
        lastMatchStart = state.lastMatchStart;
        lastMatchEnd = state.lastMatchEnd;
        lastSuccessfulMatchedString = state.lastSuccessfulMatchedString;
        lastSuccessfulMatchStart = state.lastSuccessfulMatchStart;
        lastSuccessfulMatchEnd = state.lastSuccessfulMatchEnd;
        lastSuccessfulMatchString = state.lastSuccessfulMatchString;
        lastSuccessfulPattern = state.lastSuccessfulPattern;
        lastMatchUsedPFlag = state.lastMatchUsedPFlag;
        lastMatchUsedBackslashK = state.lastMatchUsedBackslashK;
        lastCaptureGroups = state.lastCaptureGroups;
        lastClosedCapture = state.lastClosedCapture;
        lastNamedCaptureGroups = state.lastNamedCaptureGroups;
        lastMatchWasByteString = state.lastMatchWasByteString;
        lastMatchResultsTainted = state.lastMatchResultsTainted;
        manualCaptureStarts = state.manualCaptureStarts;
        manualCaptureEnds = state.manualCaptureEnds;
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
        if (PerlRuntime.current() != owner) {
            throw new IllegalStateException("Regex state must be restored in its owning PerlRuntime");
        }
        RuntimeRegexState state = owner.regexState;
        state.globalMatcher = globalMatcher;
        state.globalMatchString = globalMatchString;
        state.lastMatchedString = lastMatchedString;
        state.lastMatchStart = lastMatchStart;
        state.lastMatchEnd = lastMatchEnd;
        state.lastSuccessfulMatchedString = lastSuccessfulMatchedString;
        state.lastSuccessfulMatchStart = lastSuccessfulMatchStart;
        state.lastSuccessfulMatchEnd = lastSuccessfulMatchEnd;
        state.lastSuccessfulMatchString = lastSuccessfulMatchString;
        state.lastSuccessfulPattern = lastSuccessfulPattern;
        state.lastMatchUsedPFlag = lastMatchUsedPFlag;
        state.lastMatchUsedBackslashK = lastMatchUsedBackslashK;
        state.lastCaptureGroups = lastCaptureGroups;
        state.lastClosedCapture = lastClosedCapture;
        state.lastNamedCaptureGroups = lastNamedCaptureGroups;
        state.lastMatchWasByteString = lastMatchWasByteString;
        state.lastMatchResultsTainted = lastMatchResultsTainted;
        state.manualCaptureStarts = manualCaptureStarts;
        state.manualCaptureEnds = manualCaptureEnds;
    }
}

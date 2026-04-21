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

    /**
     * Shared singleton pushed by {@link #save()} when there is nothing to
     * save — i.e. no regex has matched yet in this process, so every
     * RuntimeRegex static we care about is still at its default
     * {@code null} / {@code -1} / {@code false} value.
     * <p>
     * This is the common case for tight-loop workloads that never touch a
     * regex (bless/DESTROY benchmarks, numeric hot loops, etc.). Allocating
     * a fresh RegexState per sub call was one of the top sources of GC
     * pressure under JFR allocation profiling — roughly 250 sampled
     * allocations in a 13-second bless benchmark that never runs a regex.
     * Once any regex matches successfully, subsequent calls fall back to
     * the per-call allocation path.
     */
    private static final RegexState EMPTY = new RegexState();

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

    /**
     * Returns true iff the shared {@link #EMPTY} snapshot is still an
     * accurate representation of the current regex globals. Checked on the
     * hot path from {@link #save()}.
     */
    private static boolean hasDefaultState() {
        // These checks are cheap reads of static fields — no allocation,
        // no synchronization. If any single match has ever been recorded,
        // at least one field has diverged from its default.
        return RuntimeRegex.globalMatcher == null
                && RuntimeRegex.globalMatchString == null
                && RuntimeRegex.lastMatchedString == null
                && RuntimeRegex.lastMatchStart == -1
                && RuntimeRegex.lastMatchEnd == -1
                && RuntimeRegex.lastSuccessfulMatchedString == null
                && RuntimeRegex.lastSuccessfulPattern == null
                && RuntimeRegex.lastCaptureGroups == null
                && !RuntimeRegex.lastMatchUsedPFlag
                && !RuntimeRegex.lastMatchWasByteString;
    }

    public static void save() {
        RegexState snapshot = hasDefaultState() ? EMPTY : new RegexState();
        DynamicVariableManager.pushLocalVariable(snapshot);
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

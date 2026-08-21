package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RegexMatcher;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable regular-expression state owned by one {@link PerlRuntime}.
 *
 * <p>Compiled patterns and Perl-visible match state live here so two runtimes
 * may match concurrently without sharing captures, {@code pos()}, match-once,
 * or {@code /o} state.</p>
 */
public final class RuntimeRegexState {
    public static final int MAX_REGEX_CACHE_SIZE = 1000;
    static final int MAX_POSITION_CACHE_SIZE = 1000;

    public RegexMatcher globalMatcher;
    public String globalMatchString;
    public String lastMatchedString;
    public int lastMatchStart = -1;
    public int lastMatchEnd = -1;
    public String lastSuccessfulMatchedString;
    public int lastSuccessfulMatchStart = -1;
    public int lastSuccessfulMatchEnd = -1;
    public String lastSuccessfulMatchString;
    public RuntimeRegex lastSuccessfulPattern;
    public boolean lastMatchUsedPFlag;
    public boolean lastMatchUsedBackslashK;
    public String[] lastCaptureGroups;
    public String lastClosedCapture;
    public boolean lastParenMatchOverrideActive;
    public String lastParenMatchOverride;
    public Map<String, List<String>> lastNamedCaptureGroups;
    public boolean lastMatchWasByteString;
    public boolean lastMatchResultsTainted;
    public int[] manualCaptureStarts;
    public int[] manualCaptureEnds;

    /** Per-runtime locale publication used by matcher-time /l resolution. */
    public final RuntimeLocaleState localeState = new RuntimeLocaleState();

    /** Per-runtime callsite state for {@code /o} and {@code m?PAT?}. */
    public final Map<Integer, RuntimeScalar> optimizedRegexCache = new LinkedHashMap<>();
    /** Stable scalar identities for literal regex targets, keyed by compiled call site. */
    public final Map<Integer, RuntimeScalar> literalRegexTargets = new LinkedHashMap<>();
    public final Map<String, String> userUnicodePropertyCache = new LinkedHashMap<>();
    public final Map<String, String> userUnicodePropertyFailureCache = new LinkedHashMap<>();

    /**
     * Per-runtime compiled templates. Some templates support deferred runtime
     * properties, so keeping the cache local also prevents cross-runtime
     * recompilation and invalidation.
     */
    public final Map<String, RuntimeRegex> compiledRegexCache =
            new LinkedHashMap<String, RuntimeRegex>(MAX_REGEX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RuntimeRegex> eldest) {
                    return size() > MAX_REGEX_CACHE_SIZE;
                }
            };

    /** Regex objects whose lexical debug lifecycle is active in this runtime. */
    public final List<RuntimeRegex> activeDebugRegexes = new ArrayList<>();
    /** Compile traces already emitted in this runtime; inherited by ithreads. */
    public final Set<String> reportedDebugCompilations = new LinkedHashSet<>();

    /** Per-runtime {@code pos()} values and zero-length-match bookkeeping. */
    final Map<RuntimeScalar, RuntimePosLvalue.CacheEntry> positionCache =
            new LinkedHashMap<RuntimeScalar, RuntimePosLvalue.CacheEntry>(
                    MAX_POSITION_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<RuntimeScalar, RuntimePosLvalue.CacheEntry> eldest) {
                    return size() > MAX_POSITION_CACHE_SIZE;
                }
            };

    public void clearMatchState() {
        globalMatcher = null;
        globalMatchString = null;
        lastMatchedString = null;
        lastMatchStart = -1;
        lastMatchEnd = -1;
        lastSuccessfulMatchedString = null;
        lastSuccessfulMatchStart = -1;
        lastSuccessfulMatchEnd = -1;
        lastSuccessfulMatchString = null;
        lastSuccessfulPattern = null;
        lastMatchUsedPFlag = false;
        lastMatchUsedBackslashK = false;
        lastCaptureGroups = null;
        lastClosedCapture = null;
        lastParenMatchOverrideActive = false;
        lastParenMatchOverride = null;
        lastNamedCaptureGroups = null;
        lastMatchWasByteString = false;
        lastMatchResultsTainted = false;
        manualCaptureStarts = null;
        manualCaptureEnds = null;
    }

    /**
     * Start a new top-level script in the same managed runtime.
     *
     * <p>User-property results are deliberately retained: they can be seeded
     * before lazy initialization and are the only regex metadata inherited by
     * an ithread snapshot. Callsite identities, match/pos state, compiled
     * programs, and debug lifecycle records belong to one top-level script
     * and must not collide with the next script's reused integer IDs.</p>
     */
    public void resetForTopLevel() {
        compiledRegexCache.clear();
        optimizedRegexCache.clear();
        literalRegexTargets.clear();
        positionCache.clear();
        activeDebugRegexes.clear();
        reportedDebugCompilations.clear();
        clearMatchState();
    }

    /** Copy immutable regex metadata that Perl ithreads inherit at creation. */
    void snapshotInto(RuntimeRegexState target) {
        target.userUnicodePropertyCache.putAll(userUnicodePropertyCache);
        target.reportedDebugCompilations.addAll(reportedDebugCompilations);
        localeState.snapshotInto(target.localeState);
    }
}

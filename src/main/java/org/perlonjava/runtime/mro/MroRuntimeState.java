package org.perlonjava.runtime.mro;

import org.perlonjava.runtime.runtimetypes.OverloadContext;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable inheritance and method-resolution state owned by one Perl runtime.
 *
 * <p>The Perl symbol tables remain process-global until Phase 8. The resolver
 * therefore uses a temporary process-wide mutation epoch to make a runtime
 * discard derived entries after another runtime changes those shared tables.
 * The maps and Perl-visible MRO controls themselves are runtime-local.</p>
 */
public final class MroRuntimeState {
    private final Map<String, List<String>> linearizedClassesCache = new HashMap<>();
    private final Map<String, InheritanceResolver.MROAlgorithm> packageMro = new HashMap<>();
    private final Map<String, RuntimeScalar> methodCache = new HashMap<>();
    private final Map<Integer, OverloadContext> overloadContextCache = new HashMap<>();
    private final Map<String, List<String>> isaStateCache = new HashMap<>();

    private final Map<String, Integer> packageGenerations = new HashMap<>();
    private final Map<String, Set<String>> isaRevCache = new HashMap<>();
    private final Map<String, List<String>> packageGenerationIsaState = new HashMap<>();

    private InheritanceResolver.MROAlgorithm defaultMro = InheritanceResolver.MROAlgorithm.DFS;
    private boolean autoloadEnabled = true;
    private long isaGeneration;
    private long subGeneration = 1;
    private long isaRevGeneration = -1;
    private long observedSymbolMutationEpoch;
    private long observedIsaMutationEpoch;

    public Map<String, List<String>> linearizedClassesCache() {
        return linearizedClassesCache;
    }

    public Map<String, InheritanceResolver.MROAlgorithm> packageMro() {
        return packageMro;
    }

    public Map<String, RuntimeScalar> methodCache() {
        return methodCache;
    }

    public Map<Integer, OverloadContext> overloadContextCache() {
        return overloadContextCache;
    }

    public Map<String, List<String>> isaStateCache() {
        return isaStateCache;
    }

    public Map<String, Integer> packageGenerations() {
        return packageGenerations;
    }

    public Map<String, Set<String>> isaRevCache() {
        return isaRevCache;
    }

    public Map<String, List<String>> packageGenerationIsaState() {
        return packageGenerationIsaState;
    }

    public InheritanceResolver.MROAlgorithm defaultMro() {
        return defaultMro;
    }

    public void setDefaultMro(InheritanceResolver.MROAlgorithm defaultMro) {
        this.defaultMro = defaultMro;
    }

    public boolean autoloadEnabled() {
        return autoloadEnabled;
    }

    public void setAutoloadEnabled(boolean autoloadEnabled) {
        this.autoloadEnabled = autoloadEnabled;
    }

    public long isaGeneration() {
        return isaGeneration;
    }

    public long subGeneration() {
        return subGeneration;
    }

    public void incrementSubGeneration() {
        subGeneration++;
    }

    public long isaRevGeneration() {
        return isaRevGeneration;
    }

    public void setIsaRevGeneration(long isaRevGeneration) {
        this.isaRevGeneration = isaRevGeneration;
    }

    /** Clear only values derived from package symbols, retaining MRO policy. */
    public void clearDerivedCaches() {
        methodCache.clear();
        linearizedClassesCache.clear();
        overloadContextCache.clear();
        isaStateCache.clear();
    }

    /** Clear reverse-inheritance data derived from package {@code @ISA} arrays. */
    public void clearReverseIsaCache() {
        isaRevCache.clear();
        isaRevGeneration = -1;
    }

    /**
     * Observe mutations to the temporarily shared symbol tables.
     *
     * @return true when derived method/MRO caches were discarded
     */
    public boolean observeSharedMutationEpochs(long symbolEpoch, long isaEpoch) {
        boolean changed = observedSymbolMutationEpoch != symbolEpoch;
        if (changed) {
            observedSymbolMutationEpoch = symbolEpoch;
            clearDerivedCaches();
        }
        if (observedIsaMutationEpoch != isaEpoch) {
            observedIsaMutationEpoch = isaEpoch;
            isaGeneration++;
            clearReverseIsaCache();
            changed = true;
        }
        return changed;
    }

    /** Used by deterministic tests and runtime reset without exposing map internals. */
    public Set<String> packagesWithGenerations() {
        return new HashSet<>(packageGenerations.keySet());
    }
}

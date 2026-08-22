package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.frontend.astnode.OperatorNode;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime-owned compilation handoff, eval, and method-dispatch cache state. */
public final class RuntimeCodeRuntimeState {
    static final int METHOD_CALL_CACHE_SIZE = 4096;
    private static final int EVAL_CACHE_SIZE = 100;
    private static final int METHOD_HANDLE_CACHE_SIZE = 100;

    final IdentityHashMap<OperatorNode, Integer> evalBeginIds = new IdentityHashMap<>();
    final Map<String, Class<?>> evalCache = lruMap(EVAL_CACHE_SIZE);
    final Map<Class<?>, MethodHandle> methodHandleCache = lruMap(METHOD_HANDLE_CACHE_SIZE);
    final HashMap<String, Class<?>> anonymousSubs = new HashMap<>();
    final HashMap<String, Object> interpretedSubs = new HashMap<>();
    final HashMap<String, EmitterContext> evalContexts = new HashMap<>();
    final ConcurrentHashMap<String, RuntimeBase[]> padConstantsByClassName =
            new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Set<String>> disabledWarningsByClassName =
            new ConcurrentHashMap<>();

    final int[] inlineCacheBlessId = new int[METHOD_CALL_CACHE_SIZE];
    final int[] inlineCacheMethodHash = new int[METHOD_CALL_CACHE_SIZE];
    final RuntimeCode[] inlineCacheCode = new RuntimeCode[METHOD_CALL_CACHE_SIZE];

    private int nextMethodCallsiteId;
    private int nextRuntimeEvalId = 1;
    boolean disassemble = System.getenv("JPERL_DISASSEMBLE") != null;
    boolean useInterpreter = System.getenv("JPERL_INTERPRETER") != null;
    boolean lexicalAliasSupportEnabled;

    private static <K, V> Map<K, V> lruMap(int maximumSize) {
        return new LinkedHashMap<K, V>(maximumSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maximumSize;
            }
        };
    }

    synchronized int allocateMethodCallsiteId() {
        return nextMethodCallsiteId++ % METHOD_CALL_CACHE_SIZE;
    }

    synchronized String nextEvalFilename() {
        return "(eval " + nextRuntimeEvalId++ + ")";
    }

    /**
     * Copy immutable compile-time descriptors referenced by cloned CODE objects.
     * Runtime eval results, generated-class caches, inline caches, and allocation
     * counters deliberately remain private to the fresh child runtime.
     */
    void snapshotCompiledMetadataInto(RuntimeCodeRuntimeState child) {
        child.evalContexts.putAll(evalContexts);
        disabledWarningsByClassName.forEach((name, categories) ->
                child.disabledWarningsByClassName.put(name, Set.copyOf(categories)));
        // A child inherits the parent's loaded-module/runtime feature surface.
        // PadWalker, Devel::LexAlias, and runtime regex source all depend on
        // live lexical registration after the snapshot; leaving this false in
        // the child silently replaced those captures with undef.
        child.lexicalAliasSupportEnabled = lexicalAliasSupportEnabled;
    }

    RuntimeCode cachedInlineMethod(int callsiteId, int blessId, int methodHash) {
        int index = callsiteId & (METHOD_CALL_CACHE_SIZE - 1);
        return inlineCacheBlessId[index] == blessId
                && inlineCacheMethodHash[index] == methodHash
                ? inlineCacheCode[index]
                : null;
    }

    void cacheInlineMethod(int callsiteId, int blessId, int methodHash, RuntimeCode code) {
        int index = callsiteId & (METHOD_CALL_CACHE_SIZE - 1);
        inlineCacheBlessId[index] = blessId;
        inlineCacheMethodHash[index] = methodHash;
        inlineCacheCode[index] = code;
    }

    void clearInlineMethodCache() {
        Arrays.fill(inlineCacheBlessId, 0);
        Arrays.fill(inlineCacheMethodHash, 0);
        Arrays.fill(inlineCacheCode, null);
    }

    void clearCaches() {
        evalBeginIds.clear();
        evalCache.clear();
        methodHandleCache.clear();
        anonymousSubs.clear();
        interpretedSubs.clear();
        evalContexts.clear();
        padConstantsByClassName.clear();
        disabledWarningsByClassName.clear();
        nextMethodCallsiteId = 0;
        clearInlineMethodCache();
    }
}

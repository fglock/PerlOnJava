package org.perlonjava.runtime;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.FeatureFlags;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime-owned lexical hint and warning state used across compilation and execution. */
public final class CompilationRuntimeState {
    public final Deque<Map<String, RuntimeScalar>> hintCompileTimeStack = new ArrayDeque<>();
    public final Map<Integer, Map<String, String>> hintSnapshots = new ConcurrentHashMap<>();
    public final Map<Integer, Map<String, RuntimeScalar>> hintScalarSnapshots = new ConcurrentHashMap<>();
    public final AtomicInteger nextHintSnapshotId = new AtomicInteger();
    public int callSiteHintHashId;
    public final Deque<Integer> callerHintHashIdStack = new ArrayDeque<>();

    public final ConcurrentHashMap<String, String> warningBitsByClass = new ConcurrentHashMap<>();
    public final Deque<String> currentWarningBitsStack = new ArrayDeque<>();
    public String callSiteWarningBits;
    public String runtimeWarningBits;
    public Set<String> runtimeDisabledWarningCategories;
    public final Deque<String> callerWarningBitsStack = new ArrayDeque<>();
    public final Deque<Set<String>> callerDisabledWarningCategoriesStack = new ArrayDeque<>();
    public int callSiteHints;
    public final Deque<Integer> callerHintsStack = new ArrayDeque<>();
    public Map<String, RuntimeScalar> callSiteHintHash = new HashMap<>();
    public final Deque<Map<String, RuntimeScalar>> callerHintHashStack = new ArrayDeque<>();
    public FeatureFlags featureManager = new FeatureFlags();
    public final Set<String> customWarningCategories = ConcurrentHashMap.newKeySet();
    public boolean globalWarningsEnabled;
    public int commandLineWarningOverride;
    public final AtomicInteger warningScopeIdCounter = new AtomicInteger();
    public final Map<Integer, Set<String>> scopeDisabledWarnings = new HashMap<>();
    public int lastWarningScopeId;
    public final ConcurrentHashMap<String, Integer> userWarningCategoryOffsets =
            new ConcurrentHashMap<>();
    public final AtomicInteger nextUserWarningOffset = new AtomicInteger(128);
    public final Map<String, Deque<RuntimeScalar>> endOfScopeFileCallbacks =
            new ConcurrentHashMap<>();
    public final Deque<String> loadingFileStack = new ArrayDeque<>();
    public static final class EndOfScopeCompileScope {
        public final String ownerFile;
        public final Deque<RuntimeScalar> callbacks = new ArrayDeque<>();

        public EndOfScopeCompileScope(String ownerFile) {
            this.ownerFile = ownerFile;
        }
    }

    public final Deque<EndOfScopeCompileScope> compileScopes = new ArrayDeque<>();
    /** UNITCHECK queue belonging to each parser currently compiling a file/eval. */
    public final ThreadLocal<Deque<RuntimeArray>> unitcheckQueueStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    /** Call-parser handlers keyed by the exact CV on which an XS shim installed them. */
    public final Map<RuntimeCode, RuntimeScalar> callParserHandlers = new ConcurrentHashMap<>();

    public void clear() {
        hintCompileTimeStack.clear();
        hintSnapshots.clear();
        hintScalarSnapshots.clear();
        nextHintSnapshotId.set(0);
        callSiteHintHashId = 0;
        callerHintHashIdStack.clear();
        warningBitsByClass.clear();
        currentWarningBitsStack.clear();
        callSiteWarningBits = null;
        runtimeWarningBits = null;
        runtimeDisabledWarningCategories = null;
        callerWarningBitsStack.clear();
        callerDisabledWarningCategoriesStack.clear();
        callSiteHints = 0;
        callerHintsStack.clear();
        callSiteHintHash.clear();
        callerHintHashStack.clear();
        featureManager = new FeatureFlags();
        customWarningCategories.clear();
        globalWarningsEnabled = false;
        commandLineWarningOverride = 0;
        warningScopeIdCounter.set(0);
        scopeDisabledWarnings.clear();
        lastWarningScopeId = 0;
        userWarningCategoryOffsets.clear();
        nextUserWarningOffset.set(128);
        endOfScopeFileCallbacks.clear();
        loadingFileStack.clear();
        compileScopes.clear();
        unitcheckQueueStack.remove();
        callParserHandlers.clear();
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.bytecode.InterpreterState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Set;

/** Per-interpreter execution and dynamic-scope state migrated in Phase 5. */
public final class ExecutionRuntimeState {
    final List<Object> callerStack = new ArrayList<>();
    final Deque<DynamicState> dynamicVariableStack = new ArrayDeque<>();
    final Deque<DynamicVariableManager.FrameCapture> dynamicFrameCaptures = new ArrayDeque<>();

    final Stack<RuntimeScalar> scalarDynamicStates = new Stack<>();
    final Stack<RuntimeArray> arrayDynamicStates = new Stack<>();
    final Stack<RuntimeHash> hashDynamicStates = new Stack<>();
    final Stack<RuntimeStash> stashDynamicStates = new Stack<>();
    final Stack<Object> globSlotStates = new Stack<>();
    /** Glob ARRAY slots explicitly removed with {@code undef *glob}. */
    final Set<String> explicitlyUndefinedGlobArraySlots = new HashSet<>();
    final Stack<Object> globalScalarStates = new Stack<>();
    final Stack<Object> globalArrayStates = new Stack<>();
    final Stack<Object> globalHashStates = new Stack<>();
    final Stack<RuntimeScalar> hashProxyStates = new Stack<>();
    final Stack<Integer> arrayProxyIndexStates = new Stack<>();
    final Stack<RuntimeScalar> arrayProxyStates = new Stack<>();
    final Stack<Integer> arrayProxySavedSizes = new Stack<>();
    final Stack<RuntimeArray> scalarLocalOwners = new Stack<>();
    final Stack<Integer> scalarLocalOwnerIndices = new Stack<>();
    final Stack<Integer> scalarLocalOwnerSizes = new Stack<>();
    final Stack<Boolean> scalarLocalOwnerExisted = new Stack<>();
    final Stack<Object> tiedHashProxyStates = new Stack<>();
    final Stack<Object> inputLineStates = new Stack<>();
    final Stack<Object> autoFlushStates = new Stack<>();
    final Stack<Object> currentFormatStates = new Stack<>();
    final Stack<Object> outputFormatVariableStates = new Stack<>();
    final Stack<Object> errnoStates = new Stack<>();
    final Stack<String> outputFieldSeparatorStates = new Stack<>();
    final Stack<String> outputRecordSeparatorStates = new Stack<>();
    String outputFieldSeparator = "";
    String outputRecordSeparator = "";

    final RuntimeArray endBlocks = new RuntimeArray();
    final RuntimeArray initBlocks = new RuntimeArray();
    final RuntimeArray checkBlocks = new RuntimeArray();

    public final RuntimeScalar currentPackage = new RuntimeScalar("main");
    public final RuntimeScalar currentCallerPackage = new RuntimeScalar("main");
    public final Deque<InterpreterState.InterpreterFrame> interpreterFrames = new ArrayDeque<>();
    public final ArrayList<int[]> interpreterPcs = new ArrayList<>();

    /** Source strings of eval STRING invocations currently executing on this runtime. */
    public final Deque<RuntimeCode.EvalSourceFrame> activeEvalSources = new ArrayDeque<>();
    public final ArrayDeque<ArrayList<String>> syntheticCallerFrames = new ArrayDeque<>();
    public final Deque<RuntimeArray> argsStack = new ArrayDeque<>();
    public final Deque<RuntimeCode> activeCodeStack = new ArrayDeque<>();
    /** Self references of active JVM-generated Perl methods. */
    public final Deque<RuntimeScalar> activeJvmSelfReferences = new ArrayDeque<>();
    /** Sort pseudo-block state for active JVM-generated Perl methods. */
    public final Deque<Boolean> activeJvmSortComparators = new ArrayDeque<>();
    /** Dynamic extent of a sort comparator invocation, including called subs. */
    public int activeSortComparatorInvocations;
    final Deque<RuntimeCode.JvmClosureFrame> jvmClosureFrames = new ArrayDeque<>();
    /** Match-time callback locations, preserved through builtin wrapper frames. */
    public final Deque<String> activeRegexCallbackLocations = new ArrayDeque<>();
    public final Deque<String> activeRegexCallbackPackages = new ArrayDeque<>();
    public final Deque<Object> activeLexicalFrames = new ArrayDeque<>();
    /** Lexical cells owned by the top-level compilation unit. */
    public final Map<String, RuntimeBase> topLevelLexicals = new LinkedHashMap<>();
    public final Deque<List<RuntimeScalar>> pristineArgsStack = new ArrayDeque<>();
    /** Reusable one-scalar return lists, populated only after scalar extraction. */
    final Deque<RuntimeList> availableScalarResultLists = new ArrayDeque<>();
    final IdentityHashMap<RuntimeBase, Boolean> deferredArgumentAggregateCleanup =
            new IdentityHashMap<>();
    public final Deque<Boolean> hasArgsStack = new ArrayDeque<>();
    public final Deque<Integer> callContextStack = new ArrayDeque<>();
    /** Call-site packages pending association with the CV entered for each call context. */
    public final Deque<String> pendingCallerPackages = new ArrayDeque<>();
    public int evalDepth;
    /** Compact stash entries materialized by an eval-held CODE assignment. */
    public final Deque<LinkedHashMap<String, RuntimeScalar>> evalPseudoConstantScopes =
            new ArrayDeque<>();
    public int tailCallTrampolineDepth;
    public final ArrayDeque<Runnable> futureResumeQueue = new ArrayDeque<>();
    public boolean futureResumeDraining;
    public int overloadStringifyDepth;
    public boolean taintMode;
    public boolean taintWarnings;
    public boolean joinTaint;
    public int moduleInitDepth;
    public final IdentityHashMap<Throwable, Boolean> unhandledDieHandlerSeen =
            new IdentityHashMap<>();
    public boolean insideUnhandledDieHandler;
    /** True while a Perl $SIG{__DIE__} callback is executing. */
    public boolean insideDieHandler;
    /** __WARN__ snapshot retained until an uncaught die reaches the ithread boundary. */
    public RuntimeScalar pendingThreadWarningHandler;
    ControlFlowMarker controlFlowMarker;

    final ArrayList<Object> myVarCleanupStack = new ArrayList<>();
    final IdentityHashMap<Object, Integer> liveMyVarCounts = new IdentityHashMap<>();

    private final IdentityHashMap<RuntimeCode, CallDepthState> callDepths = new IdentityHashMap<>();

    public CallDepthState callDepth(RuntimeCode code) {
        return callDepths.computeIfAbsent(code, ignored -> new CallDepthState());
    }

    public void releaseCallDepth(RuntimeCode code) {
        callDepths.remove(code);
    }

    public static final class CallDepthState {
        public int depth;
        public boolean warned;
    }
}

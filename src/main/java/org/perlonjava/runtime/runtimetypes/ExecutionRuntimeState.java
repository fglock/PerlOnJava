package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.bytecode.InterpreterState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Stack;

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
    final Stack<Object> globalScalarStates = new Stack<>();
    final Stack<Object> globalArrayStates = new Stack<>();
    final Stack<Object> globalHashStates = new Stack<>();
    final Stack<RuntimeScalar> hashProxyStates = new Stack<>();
    final Stack<Integer> arrayProxyIndexStates = new Stack<>();
    final Stack<RuntimeScalar> arrayProxyStates = new Stack<>();
    final Stack<Object> tiedHashProxyStates = new Stack<>();
    final Stack<Object> inputLineStates = new Stack<>();
    final Stack<Object> autoFlushStates = new Stack<>();
    final Stack<Object> errnoStates = new Stack<>();
    final Stack<String> outputFieldSeparatorStates = new Stack<>();
    final Stack<String> outputRecordSeparatorStates = new Stack<>();
    String outputFieldSeparator = "";
    String outputRecordSeparator = "";

    final RuntimeArray endBlocks = new RuntimeArray();
    final RuntimeArray initBlocks = new RuntimeArray();
    final RuntimeArray checkBlocks = new RuntimeArray();

    public final RuntimeScalar currentPackage = new RuntimeScalar("main");
    public final Deque<InterpreterState.InterpreterFrame> interpreterFrames = new ArrayDeque<>();
    public final ArrayList<int[]> interpreterPcs = new ArrayList<>();

    public final ArrayDeque<RuntimeCode.EvalRuntimeContext> evalRuntimeContexts = new ArrayDeque<>();
    public final ArrayDeque<ArrayList<String>> syntheticCallerFrames = new ArrayDeque<>();
    public final Deque<RuntimeArray> argsStack = new ArrayDeque<>();
    public final Deque<RuntimeCode> activeCodeStack = new ArrayDeque<>();
    public final Deque<Object> activeLexicalFrames = new ArrayDeque<>();
    public final Deque<List<RuntimeScalar>> pristineArgsStack = new ArrayDeque<>();
    public final Deque<Boolean> hasArgsStack = new ArrayDeque<>();
    public final Deque<Integer> callContextStack = new ArrayDeque<>();
    public int evalDepth;
    public int tailCallTrampolineDepth;
    public final ArrayDeque<Runnable> futureResumeQueue = new ArrayDeque<>();
    public boolean futureResumeDraining;
    public int overloadStringifyDepth;
    public boolean taintMode;
    public boolean joinTaint;
    public int moduleInitDepth;
    public final IdentityHashMap<Throwable, Boolean> unhandledDieHandlerSeen =
            new IdentityHashMap<>();
    public boolean insideUnhandledDieHandler;
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

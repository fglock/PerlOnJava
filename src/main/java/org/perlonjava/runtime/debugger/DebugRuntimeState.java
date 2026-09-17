package org.perlonjava.runtime.debugger;

import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime-owned debugger controls, locations, breakpoints, and active evaluation context. */
public final class DebugRuntimeState {
    public boolean debugMode;
    public volatile boolean single;
    public volatile boolean trace;
    public volatile boolean signal;
    public volatile String currentFile = "";
    public volatile int currentLine;
    public final Set<String> breakpoints = ConcurrentHashMap.newKeySet();
    public final Map<String, String> breakpointConditions = new ConcurrentHashMap<>();
    public final Map<String, String[]> sourceLines = new ConcurrentHashMap<>();
    public final Map<String, Set<Integer>> breakableLines = new ConcurrentHashMap<>();
    public volatile int stepOverDepth = -1;
    public volatile int stepOutDepth = -1;
    public volatile int callDepth;
    public final Deque<String> subNameStack = new ArrayDeque<>();
    public final Set<String> oneTimeBreakpoints = ConcurrentHashMap.newKeySet();
    public volatile boolean quit;
    public final Map<String, String> subLocations = new ConcurrentHashMap<>();
    public final Deque<RuntimeArray> argsStack = new ArrayDeque<>();

    int commandCounter = 1;
    InterpretedCode currentCode;
    RuntimeBase[] currentRegisters;
    int currentSiteIndex = -1;
    boolean hasCustomDebugger;
    boolean perl5dbExecuted;
    boolean executingPerl5db;
    /** Prevent DB::sub itself from being recursively debugger-dispatched. */
    public boolean dispatchingDbSub;
    /** Lets DB::sub's delegated target enter once without re-dispatching it. */
    public boolean skipNextDbSubDispatch;
    /** Actual callable behind the debugger-visible, string-valued $DB::sub. */
    public RuntimeScalar debuggerTargetCode;
    /** Prevent DEBUG opcodes inside a user DB::DB callback from reentering it. */
    public boolean dispatchingDbDb;
    /** Prevent overloaded debugger-variable access from recursively entering DEBUG. */
    public boolean syncingVariables;

    void reset() {
        debugMode = false;
        single = false;
        trace = false;
        signal = false;
        currentFile = "";
        currentLine = 0;
        breakpoints.clear();
        breakpointConditions.clear();
        sourceLines.clear();
        breakableLines.clear();
        stepOverDepth = -1;
        stepOutDepth = -1;
        callDepth = 0;
        subNameStack.clear();
        oneTimeBreakpoints.clear();
        quit = false;
        subLocations.clear();
        argsStack.clear();
        commandCounter = 1;
        currentCode = null;
        currentRegisters = null;
        currentSiteIndex = -1;
        hasCustomDebugger = false;
        perl5dbExecuted = false;
        executingPerl5db = false;
        dispatchingDbSub = false;
        skipNextDbSubDispatch = false;
        debuggerTargetCode = null;
        dispatchingDbDb = false;
        syncingVariables = false;
    }
}

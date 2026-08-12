package org.perlonjava.runtime.debugger;

import org.perlonjava.backend.bytecode.InterpretedCode;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;

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
    }
}

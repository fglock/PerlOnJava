package org.perlonjava.runtime.debugger;

import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;

import java.util.Deque;

/** Runtime-selecting facade for debugger state. */
public final class DebugState {
    private static volatile boolean anyDebugMode;

    private DebugState() {
    }

    public static DebugRuntimeState current() {
        return PerlRuntime.current().debugState;
    }

    public static boolean isDebugMode() {
        if (!anyDebugMode) return false;
        return current().debugMode;
    }

    public static void setDebugMode(boolean enabled) {
        if (enabled) anyDebugMode = true;
        current().debugMode = enabled;
    }

    public static String getCurrentSubName() {
        Deque<String> stack = current().subNameStack;
        return stack.isEmpty() ? "" : stack.peek();
    }

    public static void pushSubName(String subName) {
        current().subNameStack.push(subName != null ? subName : "");
    }

    public static void popSubName() {
        Deque<String> stack = current().subNameStack;
        if (!stack.isEmpty()) stack.pop();
    }

    public static void reset() {
        current().reset();
    }

    public static boolean shouldStop(String file, int line) {
        DebugRuntimeState state = current();
        String key = file + ":" + line;
        if (state.oneTimeBreakpoints.remove(key)) {
            state.breakpoints.remove(key);
            return true;
        }
        if (!state.single && !state.trace && !state.signal) {
            return state.breakpoints.contains(key);
        }
        if (state.stepOverDepth >= 0 && state.callDepth > state.stepOverDepth) return false;
        if (state.stepOutDepth >= 0 && state.callDepth >= state.stepOutDepth) return false;
        return true;
    }

    public static void storeSourceLines(String filename, String[] lines) {
        current().sourceLines.put(filename, lines);
    }

    public static String getSourceLine(String filename, int line) {
        String[] lines = current().sourceLines.get(filename);
        return lines != null && line > 0 && line < lines.length ? lines[line] : "";
    }

    public static void registerSubroutine(String fullName, String filename, int startLine, int endLine) {
        DebugRuntimeState state = current();
        if (state.debugMode) {
            state.subLocations.put(fullName, filename + ":" + startLine + "-" + endLine);
        }
    }

    public static void pushArgs(RuntimeArray args) {
        if (!current().debugMode) return;
        RuntimeArray copy = new RuntimeArray();
        copy.setFromList(args.getList());
        current().argsStack.push(copy);
    }

    public static void popArgs() {
        Deque<RuntimeArray> stack = current().argsStack;
        if (!stack.isEmpty()) stack.pop();
    }

    public static RuntimeArray getArgsForFrame(int frame) {
        Deque<RuntimeArray> stack = current().argsStack;
        if (frame < 0 || frame >= stack.size()) return null;
        return stack.toArray(new RuntimeArray[0])[frame];
    }
}

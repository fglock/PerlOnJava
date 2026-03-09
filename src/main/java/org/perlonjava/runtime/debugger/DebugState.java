package org.perlonjava.runtime.debugger;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global debug state for the Perl debugger.
 * <p>
 * This class holds all debug-related state:
 * - debugMode: set at startup, controls whether DEBUG opcodes are emitted
 * - single/trace/signal: runtime flags checked by DEBUG opcode
 * - breakpoints: set of "file:line" strings for active breakpoints
 * - sourceLines: stored source code for each file
 */
public class DebugState {

    /**
     * Master debug mode flag - set at startup when -d is used.
     * Controls whether DEBUG opcodes are emitted by BytecodeCompiler.
     * Once set, cannot be changed (compile-time decision).
     */
    public static boolean debugMode = false;

    /**
     * Single-step mode ($DB::single in Perl).
     * When true, DEBUG opcode will call DB::DB() on every statement.
     * Set to true initially when debugging starts.
     */
    public static volatile boolean single = false;

    /**
     * Trace mode ($DB::trace in Perl).
     * When true, prints trace info for each statement.
     */
    public static volatile boolean trace = false;

    /**
     * Signal flag ($DB::signal in Perl).
     * Set when a signal is received that should break into debugger.
     */
    public static volatile boolean signal = false;

    /**
     * Current filename being debugged ($DB::filename in Perl).
     */
    public static volatile String currentFile = "";

    /**
     * Current line number ($DB::line in Perl).
     */
    public static volatile int currentLine = 0;

    /**
     * Breakpoint table: "file:line" -> true.
     * Fast O(1) lookup for checking if current location has a breakpoint.
     */
    public static final Set<String> breakpoints = ConcurrentHashMap.newKeySet();

    /**
     * Conditional breakpoints: "file:line" -> "condition_expr".
     * If a breakpoint has a condition, it's stored here.
     * The condition is eval'd and breakpoint only triggers if true.
     */
    public static final Map<String, String> breakpointConditions = new ConcurrentHashMap<>();

    /**
     * Source lines storage: filename -> String[] of source lines.
     * Line numbers are 1-based, so lines[0] is unused.
     * Populated by the lexer/parser during compilation.
     */
    public static final Map<String, String[]> sourceLines = new ConcurrentHashMap<>();

    /**
     * Breakable lines: filename -> Set<Integer> of line numbers that can have breakpoints.
     * Non-breakable lines include comments, blank lines, and continuation lines.
     */
    public static final Map<String, Set<Integer>> breakableLines = new ConcurrentHashMap<>();

    /**
     * Step-over depth tracking.
     * When > 0, skip DEBUG calls until call depth returns to this level.
     * Used to implement "n" (next) command - step over subroutine calls.
     */
    public static volatile int stepOverDepth = -1;

    /**
     * Current call depth for step-over tracking.
     */
    public static volatile int callDepth = 0;

    /**
     * Flag to indicate debugger should quit.
     */
    public static volatile boolean quit = false;

    /**
     * Reset all debug state (for testing or restart).
     */
    public static void reset() {
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
        callDepth = 0;
        quit = false;
    }

    /**
     * Check if we should stop at the current location.
     * Fast path for common case (no debugging active).
     *
     * @param file Current filename
     * @param line Current line number
     * @return true if debugger should stop here
     */
    public static boolean shouldStop(String file, int line) {
        // Fast path: nothing active
        if (!single && !trace && !signal) {
            String key = file + ":" + line;
            return breakpoints.contains(key);
        }

        // Step-over mode: skip if we're deeper than target depth
        if (stepOverDepth >= 0 && callDepth > stepOverDepth) {
            return false;
        }

        return true;
    }

    /**
     * Store source lines for a file.
     *
     * @param filename The source filename
     * @param lines    Array of source lines (1-based indexing)
     */
    public static void storeSourceLines(String filename, String[] lines) {
        sourceLines.put(filename, lines);
    }

    /**
     * Get source line for display.
     *
     * @param filename The source filename
     * @param line     Line number (1-based)
     * @return The source line, or empty string if not available
     */
    public static String getSourceLine(String filename, int line) {
        String[] lines = sourceLines.get(filename);
        if (lines != null && line > 0 && line < lines.length) {
            return lines[line];
        }
        return "";
    }

    /**
     * Subroutine location registry for %DB::sub.
     * Maps "package::subname" -> "filename:startline-endline"
     */
    public static final Map<String, String> subLocations = new ConcurrentHashMap<>();

    /**
     * Register a subroutine's location for %DB::sub.
     * Only registers if debugMode is enabled.
     *
     * @param fullName  Fully qualified subroutine name (package::subname)
     * @param filename  Source filename
     * @param startLine Starting line number (1-based)
     * @param endLine   Ending line number (1-based)
     */
    public static void registerSubroutine(String fullName, String filename, int startLine, int endLine) {
        if (!debugMode) {
            return;
        }
        String location = filename + ":" + startLine + "-" + endLine;
        subLocations.put(fullName, location);
    }

    /**
     * Thread-local stack of subroutine arguments for @DB::args support.
     * Each frame stores a copy of the @_ array when the subroutine was called.
     */
    public static final ThreadLocal<Deque<RuntimeArray>> argsStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Push subroutine arguments onto the stack (called when entering a sub in debug mode).
     *
     * @param args The @_ array for this call frame
     */
    public static void pushArgs(RuntimeArray args) {
        if (!debugMode) {
            return;
        }
        // Make a shallow copy of the args array
        RuntimeArray copy = new RuntimeArray();
        copy.setFromList(args.getList());
        argsStack.get().push(copy);
    }

    /**
     * Pop subroutine arguments from the stack (called when exiting a sub in debug mode).
     */
    public static void popArgs() {
        if (!debugMode) {
            return;
        }
        Deque<RuntimeArray> stack = argsStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /**
     * Get arguments for a specific frame (0 = current, 1 = caller, etc).
     *
     * @param frame Frame number (0-based)
     * @return The args array for that frame, or null if not available
     */
    public static RuntimeArray getArgsForFrame(int frame) {
        Deque<RuntimeArray> stack = argsStack.get();
        if (frame < 0 || frame >= stack.size()) {
            return null;
        }
        // Convert to array for indexed access
        RuntimeArray[] stackArray = stack.toArray(new RuntimeArray[0]);
        return stackArray[frame];
    }
}

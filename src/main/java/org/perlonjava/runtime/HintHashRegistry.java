package org.perlonjava.runtime;

import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry for compile-time %^H (hints hash) scoping and per-call-site tracking.
 *
 * In Perl 5, %^H is lexically scoped at compile time: entering a block saves
 * a copy, and exiting restores it. Additionally, each statement (COP) captures
 * a snapshot of %^H, and caller()[10] returns that snapshot.
 *
 * This registry provides:
 * 1. Compile-time scoping: enterScope()/exitScope() save/restore the global %^H
 *    at block boundaries during parsing.
 * 2. Per-call-site tracking using snapshot IDs: registerSnapshot() captures %^H
 *    at compile time, and setCallSiteHintHashId()/pushCallerHintHash()/
 *    getCallerHintHashAtFrame() bridge compile-time state to runtime caller()[10].
 */
public class HintHashRegistry {

    // Compile-time scope stack for %^H save/restore.
    // Each entry is a snapshot of %^H taken when entering a scope.
    // On scope exit, the global %^H is restored from this stack.
    private static final Deque<Map<String, RuntimeScalar>> compileTimeStack =
        new ArrayDeque<>();

    // ---- Snapshot registry (compile-time -> runtime bridge) ----

    // Maps snapshot IDs to frozen hint hash maps. ID 0 means empty/no hints.
    private static final Map<Integer, Map<String, String>> snapshotRegistry =
        new ConcurrentHashMap<>();
    private static final AtomicInteger nextSnapshotId = new AtomicInteger(0);

    // ThreadLocal tracking the current call site's snapshot ID.
    // Updated at runtime from emitted bytecode.
    private static final ThreadLocal<Integer> callSiteSnapshotId =
        ThreadLocal.withInitial(() -> 0);

    // ThreadLocal stack saving caller's snapshot ID across subroutine calls.
    private static final ThreadLocal<Deque<Integer>> callerSnapshotIdStack =
        ThreadLocal.withInitial(ArrayDeque::new);

    // ---- Compile-time %^H scoping ----

    /**
     * Saves the current global %^H onto the compile-time scope stack.
     * Called at block entry during parsing.
     */
    public static void enterScope() {
        RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        // Deep copy the current %^H elements
        Map<String, RuntimeScalar> snapshot = new HashMap<>();
        for (Map.Entry<String, RuntimeScalar> entry : hintHash.elements.entrySet()) {
            snapshot.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
        }
        compileTimeStack.push(snapshot);
    }

    /**
     * Restores the global %^H from the compile-time scope stack.
     * Called at block exit during parsing.
     */
    public static void exitScope() {
        if (!compileTimeStack.isEmpty()) {
            Map<String, RuntimeScalar> savedState = compileTimeStack.pop();
            // Restore global %^H to the state saved when we entered this scope
            RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
            hintHash.elements.clear();
            for (Map.Entry<String, RuntimeScalar> entry : savedState.entrySet()) {
                hintHash.elements.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
            }
        }
    }

    // ---- Snapshot registration (compile-time) ----

    /**
     * Takes a snapshot of the current global %^H and registers it.
     * Called at compile time after use/no statements and at block boundaries.
     *
     * @return the snapshot ID (0 if %^H is empty)
     */
    public static int snapshotCurrentHintHash() {
        RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        if (hintHash.elements.isEmpty()) {
            return 0;
        }
        int id = nextSnapshotId.incrementAndGet();
        Map<String, String> snapshot = new HashMap<>();
        for (Map.Entry<String, RuntimeScalar> entry : hintHash.elements.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().toString());
        }
        snapshotRegistry.put(id, snapshot);
        return id;
    }

    // ---- Per-call-site tracking (runtime) ----

    /**
     * Sets the current call site's hint hash snapshot ID.
     * Called at runtime from emitted bytecode after use/no pragmas and at block boundaries.
     *
     * @param id the snapshot ID (0 = empty/no hints)
     */
    public static void setCallSiteHintHashId(int id) {
        callSiteSnapshotId.set(id);
    }

    /**
     * Saves the current call-site hint hash snapshot ID onto the caller stack,
     * then resets the callsite to 0 so the callee starts fresh.
     * The saved ID is used by caller()[10] and restored when the callee returns.
     * Called by RuntimeCode.apply() before entering a subroutine.
     */
    public static void pushCallerHintHash() {
        int currentId = callSiteSnapshotId.get();
        callerSnapshotIdStack.get().push(currentId);
        // Reset callsite for the callee - it should not inherit the caller's hints.
        // The callee's own CompilerFlagNodes will set the correct ID if needed.
        callSiteSnapshotId.set(0);
    }

    /**
     * Pops the caller's hint hash snapshot ID from the caller stack and
     * restores the callsite ID to what it was before the callee was entered.
     * Called by RuntimeCode.apply() after a subroutine returns.
     */
    public static void popCallerHintHash() {
        Deque<Integer> stack = callerSnapshotIdStack.get();
        if (!stack.isEmpty()) {
            int restoredId = stack.pop();
            // Restore the callsite ID so eval STRING and subsequent code
            // see the correct hint hash, not one clobbered by the callee.
            callSiteSnapshotId.set(restoredId);
        }
    }

    /**
     * Gets the caller's hint hash at a given frame depth.
     * Frame 0 = immediate caller, frame 1 = caller's caller, etc.
     *
     * @param frame The frame depth (0 = immediate caller)
     * @return The hint hash map, or null if not available
     */
    public static Map<String, String> getCallerHintHashAtFrame(int frame) {
        Deque<Integer> stack = callerSnapshotIdStack.get();
        if (stack.isEmpty()) {
            return null;
        }
        int index = 0;
        for (int id : stack) {
            if (index == frame) {
                if (id == 0) return null;
                return snapshotRegistry.get(id);
            }
            index++;
        }
        return null;
    }

    /**
     * Gets the hint hash map for the current call site's snapshot ID.
     * Used by eval STRING to restore %^H before compilation.
     *
     * @return the hint hash map, or null if empty/not set
     */
    public static Map<String, String> getCurrentCallSiteHintHash() {
        int id = callSiteSnapshotId.get();
        if (id == 0) return null;
        return snapshotRegistry.get(id);
    }

    /**
     * Clears all state.
     * Called by PerlLanguageProvider.resetAll() during reinitialization.
     */
    public static void clear() {
        compileTimeStack.clear();
        snapshotRegistry.clear();
        nextSnapshotId.set(0);
        callSiteSnapshotId.set(0);
        callerSnapshotIdStack.get().clear();
    }
}

package org.perlonjava.runtime;

import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.MortalList;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.*;

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
    private static CompilationRuntimeState state() {
        return PerlRuntime.current().compilationState;
    }

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
        state().hintCompileTimeStack.push(snapshot);
    }

    /**
     * Restores the global %^H from the compile-time scope stack.
     * Called at block exit during parsing.
     */
    public static void exitScope() {
        Deque<Map<String, RuntimeScalar>> stack = state().hintCompileTimeStack;
        if (!stack.isEmpty()) {
            Map<String, RuntimeScalar> savedState = stack.pop();
            // Restore global %^H to the state saved when we entered this scope
            RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
            restoreHintHash(hintHash, savedState);
        }
    }

    /**
     * Returns a detached copy of one value from the currently active compile-time
     * {@code %^H}. Parser-side consumers use this instead of reaching through the
     * global-variable implementation and accidentally bypassing lexical scoping.
     */
    public static RuntimeScalar getCompileTimeHint(String key) {
        RuntimeHash hintHash = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        RuntimeScalar value = hintHash == null ? null : hintHash.elements.get(key);
        if (value != null && value.type != org.perlonjava.runtime.runtimetypes.RuntimeScalarType.STRING) {
            return new RuntimeScalar(value);
        }

        // eval STRING restores the legacy string snapshot into the global %^H
        // facade. Prefer its parallel scalar snapshot for values such as the
        // charnames CODE reference that cannot survive stringification.
        CompilationRuntimeState state = state();
        Map<String, RuntimeScalar> scalarSnapshot =
                state.hintScalarSnapshots.get(state.callSiteHintHashId);
        RuntimeScalar captured = scalarSnapshot == null ? null : scalarSnapshot.get(key);
        if (captured != null && (value == null
                || value.toString().equals(captured.toString()))) {
            return new RuntimeScalar(captured);
        }

        return value == null ? null : new RuntimeScalar(value);
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
        CompilationRuntimeState state = state();
        int id = state.nextHintSnapshotId.incrementAndGet();
        Map<String, String> snapshot = new HashMap<>();
        Map<String, RuntimeScalar> scalarSnapshot = new HashMap<>();
        for (Map.Entry<String, RuntimeScalar> entry : hintHash.elements.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().toString());
            scalarSnapshot.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
        }
        state.hintSnapshots.put(id, snapshot);
        state.hintScalarSnapshots.put(id, scalarSnapshot);
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
        state().callSiteHintHashId = id;
    }

    /** Returns the active call-site snapshot ID for balanced lexical restoration. */
    public static int getCallSiteHintHashId() {
        return state().callSiteHintHashId;
    }

    /**
     * Saves the current call-site hint hash snapshot ID onto the caller stack,
     * then resets the callsite to 0 so the callee starts fresh.
     * The saved ID is used by caller()[10] and restored when the callee returns.
     * Called by RuntimeCode.apply() before entering a subroutine.
     */
    public static void pushCallerHintHash() {
        pushCallerHintHash(state());
    }

    public static void pushCallerHintHash(CompilationRuntimeState state) {
        int currentId = state.callSiteHintHashId;
        state.callerHintHashIdStack.push(currentId);
        // Reset callsite for the callee - it should not inherit the caller's hints.
        // The callee's own CompilerFlagNodes will set the correct ID if needed.
        state.callSiteHintHashId = 0;
    }

    /**
     * Pops the caller's hint hash snapshot ID from the caller stack and
     * restores the callsite ID to what it was before the callee was entered.
     * Called by RuntimeCode.apply() after a subroutine returns.
     */
    public static void popCallerHintHash() {
        popCallerHintHash(state());
    }

    public static void popCallerHintHash(CompilationRuntimeState state) {
        Deque<Integer> stack = state.callerHintHashIdStack;
        if (!stack.isEmpty()) {
            int restoredId = stack.pop();
            // Restore the callsite ID so eval STRING and subsequent code
            // see the correct hint hash, not one clobbered by the callee.
            state.callSiteHintHashId = restoredId;
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
        CompilationRuntimeState state = state();
        Deque<Integer> stack = state.callerHintHashIdStack;
        if (stack.isEmpty()) {
            return null;
        }
        int index = 0;
        for (int id : stack) {
            if (index == frame) {
                if (id == 0) return null;
                return state.hintSnapshots.get(id);
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
        CompilationRuntimeState state = state();
        int id = state.callSiteHintHashId;
        if (id == 0) return null;
        return state.hintSnapshots.get(id);
    }

    /**
     * Returns a detached typed snapshot for compile-time consumers such as
     * eval STRING. Unlike the legacy string map, this preserves CODE refs.
     */
    public static Map<String, RuntimeScalar> getCurrentCallSiteScalarHintHash() {
        CompilationRuntimeState state = state();
        int id = state.callSiteHintHashId;
        if (id == 0) return null;
        Map<String, RuntimeScalar> snapshot = state.hintScalarSnapshots.get(id);
        if (snapshot == null) return null;
        Map<String, RuntimeScalar> copy = new HashMap<>();
        for (Map.Entry<String, RuntimeScalar> entry : snapshot.entrySet()) {
            copy.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
        }
        return copy;
    }

    /**
     * Restores a saved compile-time hints hash while releasing values created
     * by the nested compilation.  Hint values may be blessed guards whose
     * DESTROY method implements an end-of-scope callback (for example
     * Object::HashBase's deferred Role::Tiny composition).
     */
    public static void restoreHintHash(RuntimeHash active, Map<String, RuntimeScalar> saved) {
        List<RuntimeScalar> discarded = new ArrayList<>();
        for (Map.Entry<String, RuntimeScalar> entry : active.elements.entrySet()) {
            RuntimeScalar retained = saved.get(entry.getKey());
            RuntimeScalar current = entry.getValue();
            if (retained == null || retained.type != current.type || retained.value != current.value) {
                discarded.add(current);
            }
        }
        MortalList.deferDestroyForContainerClear(discarded);
        active.clearForHintHashContextTransfer();
        active.elements.putAll(saved);
    }

    /**
     * Clears all state.
     * Called by PerlLanguageProvider.resetAll() during reinitialization.
     */
    public static void clear() {
        CompilationRuntimeState state = state();
        state.hintCompileTimeStack.clear();
        state.hintSnapshots.clear();
        state.hintScalarSnapshots.clear();
        state.nextHintSnapshotId.set(0);
        state.callSiteHintHashId = 0;
        state.callerHintHashIdStack.clear();
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * Runtime cleanup stack for my-variables during exception unwinding.
 * <p>
 * Parallels the {@code local} mechanism (InterpreterState save/restore):
 * my-variables are registered at creation time, and cleaned up on exception
 * via {@link #unwindTo(int)}. On normal scope exit, existing
 * {@code scopeExitCleanup} bytecodes handle cleanup, and {@link #popMark(int)}
 * discards the registrations without cleanup.
 * <p>
 * This ensures DESTROY fires for blessed objects held in my-variables when
 * {@code die} propagates through a subroutine that lacks an enclosing
 * {@code eval} in the same frame.
 * <p>
 * No {@code blessedObjectExists} guard is used in {@link #pushMark()},
 * {@link #register(Object)}, or {@link #popMark(int)} because a my-variable
 * may be created (and registered) BEFORE the first {@code bless()} call in
 * the same subroutine. The per-call overhead is negligible: O(1) amortized
 * ArrayList operations per my-variable, inlined by HotSpot.
 * <p>
 * Thread model: single-threaded (matches MortalList).
 *
 * @see MortalList#evalExceptionScopeCleanup(Object)
 */
public class MyVarCleanupStack {

    private static ArrayList<Object> stack() {
        return PerlRuntime.current().executionState().myVarCleanupStack;
    }

    // Phase I: parallel identity-counted set for O(1) `isLive(var)` check
    // from the reachability walker. Maps var -> registration count
    // (a single var can be registered multiple times if declared in
    // nested scopes with the same slot reuse).
    private static IdentityHashMap<Object, Integer> liveCounts() {
        return PerlRuntime.current().executionState().liveMyVarCounts;
    }

    private static IdentityHashMap<Object, Integer> registeredCounts() {
        return PerlRuntime.current().executionState().registeredMyVarCounts;
    }

    /**
     * Phase I: O(1) check whether the given object is currently registered
     * (its declaration scope hasn't exited). Used by the reachability
     * walker to filter out stale ScalarRefRegistry entries — scalars
     * whose scopes have exited but whose Java-level lifetime persists
     * (e.g. via MortalList.deferredCaptures) were falsely marking
     * their referents as reachable.
     */
    public static boolean isLive(Object var) {
        if (var == null) return false;
        return liveCounts().containsKey(var);
    }

    /**
     * True when {@code var} is currently registered as a lexical, regardless
     * of whether weak-ref tracking has populated the live-count map. Used by
     * first-bless paths that can run before the first {@code weaken()} call.
     * The identity-counted registration map keeps this check O(1).
     */
    public static boolean isRegistered(Object var) {
        if (var == null) return false;
        return registeredCounts().containsKey(var);
    }

    /**
     * Snapshot the currently-live my-variables. Used by the
     * reachability walker's per-object query
     * ({@link ReachabilityWalker#isReachableFromRoots}) to seed from
     * still-in-scope lexical containers (e.g. {@code my %METAS}
     * declared at file scope of a still-loading module). The
     * live-counts map keys are stable identity references to
     * RuntimeScalar / RuntimeArray / RuntimeHash instances.
     */
    public static java.util.List<Object> snapshotLiveVars() {
        return new java.util.ArrayList<>(liveCounts().keySet());
    }

    /** Snapshot registered lexical slots without requiring weak-ref tracking. */
    public static java.util.List<Object> snapshotRegisteredVars() {
        return new java.util.ArrayList<>(stack());
    }

    /**
     * Called at subroutine entry (in {@code RuntimeCode.apply()}).
     * Returns a mark position for later {@link #popMark(int)} or
     * {@link #unwindTo(int)}.
     *
     * @return mark position (always >= 0)
     */
    public static int pushMark() {
        return stack().size();
    }

    static int pushMark(ExecutionRuntimeState executionState) {
        return executionState.myVarCleanupStack.size();
    }

    /**
     * Called by emitted bytecode when a my-variable is created.
     * Registers the variable for potential exception cleanup.
     * <p>
     * Always registers unconditionally — the variable may later hold a
     * blessed reference even if no bless() has happened yet at the point
     * of the {@code my} declaration. The {@code scopeExitCleanup} methods
     * are idempotent, so double-cleanup (normal exit + exception) is safe.
     *
     * @param var the RuntimeScalar, RuntimeHash, or RuntimeArray object
     */
    public static void register(Object var) {
        stack().add(var);
        if (var != null) registeredCounts().merge(var, 1, Integer::sum);
        // liveCounts is consulted by ReachabilityWalker.sweepWeakRefs and
        // .isReachableFromRoots. We populate it lazily — only after the
        // first weaken() call (which sets WeakRefRegistry.weakRefsExist).
        // Tests that never weaken pay zero per-`my` cost; tests that do
        // weaken trigger a one-time backfill via
        // {@link #snapshotStackToLiveCounts()} from WeakRefRegistry,
        // which seeds liveCounts with all already-registered my-vars.
        if (var != null && WeakRefRegistry.weakRefsExist()) {
            liveCounts().merge(var, 1, Integer::sum);
            MortalList.invalidateLiveRootSnapshot();
        }
    }

    /**
     * Phase D-W2b (perf): one-time backfill of the live-count map with
     * all currently registered my-vars. Called by
     * {@link WeakRefRegistry#registerWeakRef} the first time
     * {@code weakRefsExist} flips to true. Without this, my-vars
     * declared before the first {@code weaken()} would never be
     * inserted into {@code liveCounts} and the walker would miss them.
     */
    public static synchronized void snapshotStackToLiveCounts() {
        IdentityHashMap<Object, Integer> liveCounts = liveCounts();
        for (Object var : stack()) {
            if (var != null) {
                liveCounts.merge(var, 1, Integer::sum);
            }
        }
        MortalList.invalidateLiveRootSnapshot();
    }

    /**
     * Called by emitted bytecode at normal block scope exit AFTER
     * {@code scopeExitCleanup} has run. Removes the most recent entry
     * matching {@code var} (by object identity) so the static stack no
     * longer holds the scalar alive. Without this, block-scoped
     * my-variables stayed registered until the enclosing subroutine
     * returned, keeping their RuntimeBase targets alive past their
     * Perl-level scope and causing leaks visible through the
     * reachability walker.
     * <p>
     * Paired with {@link #register(Object)} — every register has a
     * matching unregister on normal exit, and a matching
     * {@link #unwindTo(int)} walk on exception exit.
     *
     * @param var the RuntimeScalar/Array/Hash previously registered
     */
    public static void unregister(Object var) {
        if (var == null) return;
        // Block-scoped my-vars pop in reverse declaration order, so
        // scan from the top of the stack for a fast amortized match.
        ArrayList<Object> stack = stack();
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) == var) {
                stack.remove(i);
                decRegisteredCount(var);
                decLiveCount(var);
                if (var instanceof RuntimeBase base
                        && (base instanceof RuntimeArray || base instanceof RuntimeHash)
                        && !isRegistered(var)) {
                    // The scope cleanup normally clears this marker before
                    // unregistering. Keep the stack authoritative as well for
                    // callers that release a lexical slot directly.
                    base.localBindingExists = false;
                }
                noteVarLeftScope(var);
                return;
            }
        }
    }

    /**
     * Replace the Java object backing a live lexical slot without ending the
     * lexical's Perl scope. The interpreter uses replace-on-assignment for
     * ordinary scalars, so leaving the old object on this stack would make
     * reachability analysis overlook references stored in the new slot.
     */
    public static void replace(Object oldVar, Object newVar) {
        if (oldVar == null || newVar == null || oldVar == newVar) return;
        ArrayList<Object> stack = stack();
        IdentityHashMap<Object, Integer> registeredCounts = registeredCounts();
        IdentityHashMap<Object, Integer> liveCounts = liveCounts();
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) == oldVar) {
                stack.set(i, newVar);
                decCount(registeredCounts, oldVar);
                registeredCounts.merge(newVar, 1, Integer::sum);
                Integer count = liveCounts.get(oldVar);
                if (count != null) {
                    if (count <= 1) liveCounts.remove(oldVar);
                    else liveCounts.put(oldVar, count - 1);
                    liveCounts.merge(newVar, 1, Integer::sum);
                    MortalList.invalidateLiveRootSnapshot();
                }
                return;
            }
        }
    }

    private static void decRegisteredCount(Object var) {
        decCount(registeredCounts(), var);
    }

    private static void decCount(IdentityHashMap<Object, Integer> counts, Object var) {
        Integer count = counts.get(var);
        if (count == null) return;
        if (count <= 1) counts.remove(var);
        else counts.put(var, count - 1);
    }

    private static void decLiveCount(Object var) {
        IdentityHashMap<Object, Integer> liveCounts = liveCounts();
        Integer c = liveCounts.get(var);
        if (c == null) return;
        if (c <= 1) liveCounts.remove(var);
        else liveCounts.put(var, c - 1);
        MortalList.invalidateLiveRootSnapshot();
    }

    private static void noteVarLeftScope(Object var) {
        if (var instanceof RuntimeBase base
                && (base instanceof RuntimeArray || base instanceof RuntimeHash)
                && WeakRefRegistry.weakRefsExist()) {
            MortalList.requestWeakSweepsForDestroyedContainer(base);
        }
        if (var instanceof RuntimeBase base && WeakRefRegistry.hasWeakRefsTo(base)) {
            requestWeakCleanupAfterScopeExit(base);
        } else if (var instanceof RuntimeScalar scalar
                && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base
                && WeakRefRegistry.hasWeakRefsTo(base)) {
            requestWeakCleanupAfterScopeExit(base);
        }
    }

    private static void requestWeakCleanupAfterScopeExit(RuntimeBase base) {
        MortalList.finalizeClearedAggregateOwnerAfterScopeExit(base);
        // Selective owner records can outlive the Perl lexical that created
        // them when a closure graph is being torn down. Recheck weak refs at
        // the scope boundary so the reachability walker can reconcile those
        // records with the remaining Perl-visible roots.
        MortalList.requestImmediateWeakSweep();
    }

    /**
     * Called on exception in {@code RuntimeCode.apply()}.
     * Runs {@link MortalList#evalExceptionScopeCleanup(Object)} for all
     * registered-but-not-yet-cleaned variables since the mark, in LIFO order.
     * <p>
     * Variables that were already cleaned up by normal scope exit have their
     * cleanup methods as no-ops (idempotent).
     *
     * @param mark the mark position from {@link #pushMark()}
     */
    public static void unwindTo(int mark) {
        ArrayList<Object> stack = stack();
        unwindTo(mark, stack);
    }

    static void unwindTo(int mark, ExecutionRuntimeState executionState) {
        unwindTo(mark, executionState.myVarCleanupStack);
    }

    private static void unwindTo(int mark, ArrayList<Object> stack) {
        for (int i = stack.size() - 1; i >= mark; i--) {
            Object var = stack.removeLast();
            if (var != null) {
                decRegisteredCount(var);
                decLiveCount(var);
                MortalList.evalExceptionScopeCleanup(var);
                noteVarLeftScope(var);
            }
        }
    }

    /**
     * Called on normal exit in {@code RuntimeCode.apply()}.
     * Discards registrations without running cleanup (normal scope-exit
     * bytecodes already handled it).
     *
     * @param mark the mark position from {@link #pushMark()}
     */
    public static void popMark(int mark) {
        ArrayList<Object> stack = stack();
        popMark(mark, stack);
    }

    static void popMark(int mark, ExecutionRuntimeState executionState) {
        popMark(mark, executionState.myVarCleanupStack);
    }

    private static void popMark(int mark, ArrayList<Object> stack) {
        while (stack.size() > mark) {
            Object var = stack.removeLast();
            if (var != null) {
                decRegisteredCount(var);
                decLiveCount(var);
                noteVarLeftScope(var);
            }
        }
    }

    /**
     * Finalize stream-socket ownership for all lexicals registered by the
     * current function before its normal-return registrations are discarded.
     */
    public static void releaseOrTransferSocketOwnersOnReturn(int mark, RuntimeBase returned) {
        ArrayList<Object> stack = stack();
        for (int i = stack.size() - 1; i >= mark; i--) {
            Object var = stack.get(i);
            if (var instanceof RuntimeScalar scalar) {
                RuntimeScalar.releaseOrTransferSocketIoOwnerOnReturn(scalar, returned);
            }
        }
    }

    /** Acquire IO ownership for every currently-live lexical alias of a glob. */
    public static void retainLiveIoGlobOwners(RuntimeGlob glob) {
        if (glob == null) return;
        IdentityHashMap<RuntimeScalar, Boolean> seen = new IdentityHashMap<>();
        for (Object var : stack()) {
            if (var instanceof RuntimeScalar scalar
                    && scalar.type == RuntimeScalarType.GLOBREFERENCE
                    && scalar.value == glob
                    && seen.put(scalar, Boolean.TRUE) == null) {
                RuntimeScalar.retainUnstashedIoForDurableSlot(scalar);
            }
        }
    }

    /**
     * Count every currently-live lexical alias when an open glob is blessed.
     * Open handles can enter as {@code GLOB} scalars, which ordinary reference
     * assignment tracking does not count. Blessing makes their shared glob a
     * DESTROY-bearing referent, so each lexical alias must keep that referent
     * alive until its own scope cleanup.
     */
    public static void retainBlessedGlobAliases(RuntimeGlob glob, RuntimeBase referent) {
        if (glob == null || referent == null) return;
        IdentityHashMap<RuntimeScalar, Boolean> seen = new IdentityHashMap<>();
        for (Object var : stack()) {
            if (var instanceof RuntimeScalar scalar
                    && (scalar.type == RuntimeScalarType.GLOB
                        || scalar.type == RuntimeScalarType.GLOBREFERENCE)
                    && scalar.value == glob
                    && !scalar.refCountOwned
                    && seen.put(scalar, Boolean.TRUE) == null) {
                scalar.refCountOwned = true;
                referent.recordOwner(scalar, "blessed glob lexical alias");
                referent.recordActiveOwner(scalar);
                referent.traceRefCount(+1, "MyVarCleanupStack.retainBlessedGlobAliases");
                referent.refCount++;
                referent.hadCountedReference = true;
            }
        }
        for (RuntimeScalar scalar : GlobalVariable.scalarSlots().values()) {
            if (scalar != null
                    && (scalar.type == RuntimeScalarType.GLOB
                    || scalar.type == RuntimeScalarType.GLOBREFERENCE)
                    && scalar.value == glob
                    && !scalar.refCountOwned
                    && seen.put(scalar, Boolean.TRUE) == null) {
                scalar.refCountOwned = true;
                referent.recordOwner(scalar, "blessed glob package alias");
                referent.recordActiveOwner(scalar);
                referent.traceRefCount(+1, "MyVarCleanupStack.retainBlessedGlobAliases");
                referent.refCount++;
                referent.hadCountedReference = true;
            }
        }
    }
}

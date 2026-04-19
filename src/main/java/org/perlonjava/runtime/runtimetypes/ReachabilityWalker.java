package org.perlonjava.runtime.runtimetypes;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

/**
 * Phase 4 (refcount_alignment_plan.md): On-demand reachability walker.
 * <p>
 * Walks the live object graph from Perl-visible roots and identifies which
 * objects in the weak-ref registry are unreachable. Clears weak refs for
 * those objects, simulating Perl 5's refcount-based collection when
 * PerlOnJava's cooperative refCount has drifted due to JVM temporaries.
 * <p>
 * Roots:
 * <ul>
 *   <li>{@link GlobalVariable#globalVariables} — package scalars ($pkg::name)</li>
 *   <li>{@link GlobalVariable#globalArrays} — package arrays (@pkg::name)</li>
 *   <li>{@link GlobalVariable#globalHashes} — package hashes (%pkg::name)</li>
 *   <li>{@link GlobalVariable#globalCodeRefs} — package subs</li>
 *   <li>Rescued objects from {@link DestroyDispatch}</li>
 * </ul>
 * <p>
 * Not yet walked (TODO):
 * <ul>
 *   <li>Live lexicals in the call stack — JVM doesn't easily expose these.
 *       Mitigated by assuming a short-lived sweep runs during/after Perl code
 *       completes a unit of work (e.g., every N flushes).</li>
 *   <li>Closures that capture lexicals — we walk them via their CODE refs but
 *       not into the captured variables directly.</li>
 * </ul>
 */
public class ReachabilityWalker {

    // Re-use the weak-ref registry's internal map (we add a getter)
    private final Set<RuntimeBase> reachable =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Walk from Perl-visible roots and mark reachable objects.
     *
     * @return the set of reachable RuntimeBase instances
     */
    public Set<RuntimeBase> walk() {
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();

        // Roots: globals
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalVariables.entrySet()) {
            visitScalar(e.getValue(), todo);
        }
        for (Map.Entry<String, RuntimeArray> e : GlobalVariable.globalArrays.entrySet()) {
            addReachable(e.getValue(), todo);
        }
        for (Map.Entry<String, RuntimeHash> e : GlobalVariable.globalHashes.entrySet()) {
            addReachable(e.getValue(), todo);
        }
        for (Map.Entry<String, RuntimeScalar> e : GlobalVariable.globalCodeRefs.entrySet()) {
            visitScalar(e.getValue(), todo);
        }

        // Roots: rescued objects (destroyed but pinned for DBIC phantom chain)
        for (RuntimeBase rescued : DestroyDispatch.snapshotRescuedForWalk()) {
            addReachable(rescued, todo);
        }

        // BFS
        while (!todo.isEmpty()) {
            RuntimeBase cur = todo.removeFirst();
            if (cur instanceof RuntimeHash h) {
                for (RuntimeScalar v : h.elements.values()) {
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeArray a) {
                for (RuntimeScalar v : a.elements) {
                    visitScalar(v, todo);
                }
            } else if (cur instanceof RuntimeCode code) {
                // Walk closure captures
                if (code.capturedScalars != null) {
                    for (RuntimeScalar cap : code.capturedScalars) {
                        visitScalar(cap, todo);
                    }
                }
            } else if (cur instanceof RuntimeScalar s) {
                visitScalar(s, todo);
            }
        }

        return reachable;
    }

    private void visitScalar(RuntimeScalar s, java.util.ArrayDeque<RuntimeBase> todo) {
        if (s == null) return;
        // Weak refs are not counted as strong edges in reachability
        if (WeakRefRegistry.isweak(s)) return;
        if ((s.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && s.value instanceof RuntimeBase b) {
            addReachable(b, todo);
        }
    }

    private void addReachable(RuntimeBase b, java.util.ArrayDeque<RuntimeBase> todo) {
        if (b == null) return;
        if (reachable.add(b)) {
            todo.addLast(b);
        }
    }

    /**
     * Run a reachability sweep and clear weak refs for unreachable objects.
     * Called from {@link MortalList#flush} periodically and from
     * {@code Internals::jperl_gc()} explicitly.
     *
     * @return number of weak-ref entries cleared
     */
    public static int sweepWeakRefs() {
        if (!WeakRefRegistry.weakRefsExist) return 0;
        ReachabilityWalker w = new ReachabilityWalker();
        Set<RuntimeBase> live = w.walk();
        ArrayList<RuntimeBase> toClear = new ArrayList<>();
        for (RuntimeBase referent : WeakRefRegistry.snapshotWeakRefReferents()) {
            if (!live.contains(referent)) {
                toClear.add(referent);
            }
        }
        int cleared = 0;
        for (RuntimeBase referent : toClear) {
            // Fire DESTROY if the object is blessed and hasn't destroyed yet.
            // This matches Perl's behavior of collecting orphan circular
            // structures: when they become unreachable, DESTROY fires and
            // weak refs clear.
            if (referent.blessId != 0 && !referent.destroyFired
                    && referent.refCount != Integer.MIN_VALUE) {
                referent.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(referent);
            } else {
                WeakRefRegistry.clearWeakRefsTo(referent);
                if (referent.refCount != Integer.MIN_VALUE) {
                    referent.refCount = Integer.MIN_VALUE;
                }
            }
            cleared++;
        }
        return cleared;
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase B1 of {@code dev/design/refcount_alignment_52leaks_plan.md}:
 * tracks all {@link RuntimeScalar} instances currently holding a
 * reference, keyed weakly so JVM GC can collect entries when the scalar
 * itself becomes unreachable.
 * <p>
 * Purpose: the {@link ReachabilityWalker} can't enumerate live
 * JVM-call-stack lexicals directly. By tracking ref-holding scalars
 * here with weak keys, a {@code System.gc()} followed by iteration of
 * surviving entries gives the walker a Perl-compatible view of "what
 * scalars are still alive in the call stack".
 * <p>
 * The map is only populated when {@link WeakRefRegistry#weakRefsExist}
 * is {@code true}, so non-{@code weaken()} programs pay zero cost.
 * <p>
 * Thread-safety: not thread-safe. Matches PerlOnJava's single-threaded
 * execution model (see {@code weaken-destroy.md} §5).
 */
public class ScalarRefRegistry {

    /**
     * Weak identity keys with batched ReferenceQueue draining. Java 24's
     * WeakHashMap polls its queue on every put, contending with the JVM
     * reference-handler thread in allocation-heavy weaken() workloads. This
     * map drains every 1024 registrations and before snapshots instead.
     */
    static final class WeakIdentityMap<V> {
        private final Map<IdentityWeakReference, V> entries = new HashMap<>();
        private final ReferenceQueue<RuntimeScalar> clearedKeys = new ReferenceQueue<>();
        private int registrations;

        void put(RuntimeScalar scalar, V value) {
            entries.put(new IdentityWeakReference(scalar, clearedKeys), value);
            // Amortize ReferenceQueue's monitor acquisition. WeakHashMap polls
            // on every put; this workload creates enough references for that
            // to contend heavily with the JVM reference-handler thread.
            if ((++registrations & 1023) == 0) drainClearedKeys();
        }

        V get(RuntimeScalar scalar) {
            return entries.get(new IdentityWeakReference(scalar, null));
        }

        List<RuntimeScalar> snapshotKeys() {
            drainClearedKeys();
            List<RuntimeScalar> live = new ArrayList<>(entries.size());
            var iterator = entries.keySet().iterator();
            while (iterator.hasNext()) {
                RuntimeScalar scalar = iterator.next().get();
                if (scalar == null) {
                    iterator.remove();
                } else {
                    live.add(scalar);
                }
            }
            return live;
        }

        int liveSize() {
            return snapshotKeys().size();
        }

        void clear() {
            entries.clear();
            while (clearedKeys.poll() != null) {
                // Drain references whose map entries were just cleared.
            }
            registrations = 0;
        }

        private void drainClearedKeys() {
            IdentityWeakReference reference;
            while ((reference = (IdentityWeakReference) clearedKeys.poll()) != null) {
                entries.remove(reference);
            }
        }
    }

    private static final class IdentityWeakReference extends WeakReference<RuntimeScalar> {
        private final int identityHash;

        IdentityWeakReference(
                RuntimeScalar scalar, ReferenceQueue<RuntimeScalar> clearedKeys) {
            super(scalar, clearedKeys);
            identityHash = System.identityHashCode(scalar);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference reference)) return false;
            RuntimeScalar scalar = get();
            return scalar != null && scalar == reference.get();
        }
    }

    // IdentityWeakReference preserves Object identity while allowing scalar
    // wrappers to be reclaimed by JVM GC.
    // Phase E: optional per-scalar registerRef call-site stacks.
    // Populated only when JPERL_REGISTER_STACKS=1 is set. Uses a
    // WeakHashMap with the same scalar as key, so entries are pruned
    // automatically when the scalar is JVM-GC'd. Lookup via
    // stackFor() is O(1).
    private static LifecycleRuntimeState state() {
        return PerlRuntime.current().lifecycleState;
    }

    // Phase B1 performance toggle: when set, skip all registry
    // maintenance. Useful for benchmarks; does NOT affect correctness
    // for programs that don't use weaken() (no weak-ref registry =
    // no sweep triggers = unused registry).
    private static final boolean OPT_OUT =
            System.getenv("JPERL_NO_SCALAR_REGISTRY") != null;
    private static final boolean DEBUG =
            System.getenv("JPERL_GC_DEBUG") != null;
    private static final boolean RECORD_STACKS =
            System.getenv("JPERL_REGISTER_STACKS") != null;
    // Opt back to unconditional registration for scripts that weaken()
    // after a long warm-up phase where many scalars were assigned.
    private static final boolean UNGATED =
            System.getenv("JPERL_UNGATED_SCALAR_REGISTRY") != null;

    /**
     * Register a scalar that now holds a reference. Called from
     * {@link RuntimeScalar#setLarge} paths that assign a ref value.
     * <p>
     * Gated on {@link WeakRefRegistry#weakRefsExist}: this registry
     * exists solely to feed {@link ReachabilityWalker#sweepWeakRefs}
     * live-lexical seeds. If no weaken() has ever been called, no
     * sweep will ever examine the registry, so registering is pure
     * overhead — and it's a {@code synchronized(WeakHashMap).put}
     * which is expensive per call. Life_bitpacked.pl profile showed
     * this put path as the single largest post-compile hotspot.
     * <p>
     * Trade-off: if a script holds many scalars-with-refs PRIOR to
     * the first weaken(), those scalars won't be in the registry
     * when the walker first runs. However, any subsequent
     * {@code setLarge} on those scalars will register them, and the
     * walker's primary seeds (globals, code refs, DESTROY rescued
     * set) still find reachable structures via the normal BFS.
     * <p>
     * Opt back to unconditional registration via
     * {@code JPERL_UNGATED_SCALAR_REGISTRY=1} if needed.
     */
    public static void registerRef(RuntimeScalar scalar) {
        if (OPT_OUT || scalar == null) return;
        if (!WeakRefRegistry.weakRefsExist() && !UNGATED) return;
        state().scalarRegistry.put(scalar, Boolean.TRUE);
        if (RECORD_STACKS) {
            state().scalarRegisterStacks.put(scalar, new Throwable("registerRef"));
        }
        if (DEBUG) {
            System.err.println("DBG registerRef scalar=" + System.identityHashCode(scalar)
                    + " type=" + scalar.type + " size=" + state().scalarRegistry.liveSize());
        }
    }

    /**
     * Phase E: return the call-site stack recorded at the time
     * {@link #registerRef} was called for the given scalar. Returns
     * {@code null} if no stack was recorded (either RECORD_STACKS is
     * off, the scalar was never registered, or its entry was pruned
     * by JVM GC).
     */
    public static Throwable stackFor(RuntimeScalar sc) {
        if (!RECORD_STACKS || sc == null) return null;
        return state().scalarRegisterStacks.get(sc);
    }

    /**
     * Snapshot the current live set. Caller should invoke
     * {@code System.gc()} beforehand if they want JVM GC to prune
     * unreachable entries first (e.g., freshly-exited lexical scopes).
     */
    public static java.util.List<RuntimeScalar> snapshot() {
        return state().scalarRegistry.snapshotKeys();
    }

    /**
     * Force JVM GC, wait briefly for finalization, then return a
     * snapshot of still-live ref-holding scalars. Used by
     * {@link ReachabilityWalker#sweepWeakRefs} to seed its walk with
     * live call-stack lexicals. Idempotent but not cheap — bounded to
     * a few hundred ms at most.
     */
    public static java.util.List<RuntimeScalar> forceGcAndSnapshot() {
        // Multiple GC cycles are sometimes needed: the first cycle may
        // only clear one level of unreachable objects, exposing more
        // for a subsequent pass. A WeakReference sentinel tells us
        // when weak-ref processing has completed for a cycle.
        for (int pass = 0; pass < 3; pass++) {
            Object sentinel = new Object();
            WeakReference<Object> probe = new WeakReference<>(sentinel);
            sentinel = null;  // drop the only strong ref
            for (int i = 0; i < 5; i++) {
                System.gc();
                if (probe.get() == null) break;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return snapshot();
    }

    /**
     * Test-only hook: how many entries does the registry currently
     * hold? (Subject to JVM GC between calls.)
     */
    public static int approximateSize() {
        return state().scalarRegistry.liveSize();
    }

    /** Drop weak-key bookkeeping belonging to the previous top-level script. */
    public static void resetState() {
        state().scalarRegistry.clear();
        state().scalarRegisterStacks.clear();
    }
}

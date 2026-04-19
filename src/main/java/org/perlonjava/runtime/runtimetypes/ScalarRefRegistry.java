package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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

    // WeakHashMap uses identity-based hashing when keys don't override
    // hashCode/equals — RuntimeScalar uses Object's defaults, so this
    // is effectively IdentityHashMap-with-weak-keys.
    private static final Map<RuntimeScalar, Boolean> scalarRegistry =
            Collections.synchronizedMap(new WeakHashMap<>());

    // Phase E: optional per-scalar registerRef call-site stacks.
    // Populated only when JPERL_REGISTER_STACKS=1 is set. Uses a
    // WeakHashMap with the same scalar as key, so entries are pruned
    // automatically when the scalar is JVM-GC'd. Lookup via
    // stackFor() is O(1).
    private static final Map<RuntimeScalar, Throwable> registerStacks =
            Collections.synchronizedMap(new WeakHashMap<>());

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

    /**
     * Register a scalar that now holds a reference. Called from
     * {@link RuntimeScalar#setLarge} paths that assign a ref value.
     * <p>
     * NOTE: we do NOT gate on {@link WeakRefRegistry#weakRefsExist}
     * because that flag only flips to true the first time
     * {@code weaken()} is called. Scripts that assign refs BEFORE the
     * first {@code weaken()} would otherwise miss those scalars, and
     * the walker couldn't see them as live-lexical roots when it runs.
     * The cost of the unconditional {@code WeakHashMap.put} is
     * amortized by JVM hashing — small but present. Opt out via
     * {@code JPERL_NO_SCALAR_REGISTRY=1} for benchmarking.
     */
    public static void registerRef(RuntimeScalar scalar) {
        if (OPT_OUT || scalar == null) return;
        scalarRegistry.put(scalar, Boolean.TRUE);
        if (RECORD_STACKS) {
            registerStacks.put(scalar, new Throwable("registerRef"));
        }
        if (DEBUG) {
            System.err.println("DBG registerRef scalar=" + System.identityHashCode(scalar)
                    + " type=" + scalar.type + " size=" + scalarRegistry.size());
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
        return registerStacks.get(sc);
    }

    /**
     * Snapshot the current live set. Caller should invoke
     * {@code System.gc()} beforehand if they want JVM GC to prune
     * unreachable entries first (e.g., freshly-exited lexical scopes).
     */
    public static java.util.List<RuntimeScalar> snapshot() {
        synchronized (scalarRegistry) {
            return new java.util.ArrayList<>(scalarRegistry.keySet());
        }
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
        synchronized (scalarRegistry) {
            return scalarRegistry.size();
        }
    }
}

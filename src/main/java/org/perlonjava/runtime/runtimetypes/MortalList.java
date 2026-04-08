package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;

/**
 * Lightweight mortal-like defer-decrement mechanism.
 * <p>
 * Perl 5 uses "mortals" to keep values alive until the end of the current
 * statement (FREETMPS). Without this, hash delete would trigger DESTROY before
 * the caller can capture the returned value.
 * <p>
 * This is critical for POE compatibility: {@code delete $heap->{wheel}} must
 * trigger DESTROY at statement end, not immediately during delete.
 */
public class MortalList {

    // Global gate: false until the first bless() into a class with DESTROY.
    // When false, both deferDecrementIfTracked() and flush() are no-ops
    // (a single branch, trivially predicted). This means zero effective cost
    // for programs that never use DESTROY.
    public static boolean active = false;

    // List of RuntimeBase references awaiting decrement.
    // Populated by delete() when removing tracked elements.
    // Drained at statement boundaries (FREETMPS equivalent).
    private static final ArrayList<RuntimeBase> pending = new ArrayList<>();

    /**
     * Schedule a deferred refCount decrement for a tracked referent.
     * Called from delete() when removing a tracked blessed reference
     * from a container.
     */
    public static void deferDecrement(RuntimeBase base) {
        pending.add(base);
    }

    /**
     * Convenience: check if a RuntimeScalar holds a tracked reference
     * and schedule a deferred decrement if so.
     */
    public static void deferDecrementIfTracked(RuntimeScalar scalar) {
        if (!active || scalar == null) return;
        if ((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base
                && base.refCount > 0) {
            pending.add(base);
        }
    }

    /**
     * Process all pending decrements. Called at statement boundaries.
     * Equivalent to Perl 5's FREETMPS.
     */
    public static void flush() {
        if (!active || pending.isEmpty()) return;
        // Process list — DESTROY may add new entries, so use index-based loop
        for (int i = 0; i < pending.size(); i++) {
            RuntimeBase base = pending.get(i);
            if (base.refCount > 0 && --base.refCount == 0) {
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            }
        }
        pending.clear();
    }
}

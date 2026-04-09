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
     * and schedule a deferred decrement if so. Only fires if the scalar
     * owns a refCount increment (refCountOwned == true), preventing
     * spurious decrements from copies that never incremented.
     */
    public static void deferDecrementIfTracked(RuntimeScalar scalar) {
        if (!active || scalar == null) return;
        if (!scalar.refCountOwned) return;
        if ((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base) {
            if (base.refCount > 0) {
                scalar.refCountOwned = false;
                pending.add(base);
            }
            // Note: WEAKLY_TRACKED (-2) objects are NOT scheduled for destruction
            // on scope exit. We can't count strong refs for non-DESTROY objects
            // (refs created before weaken() weren't tracked), so scope exit of
            // ONE reference doesn't mean there are no other strong refs (e.g.,
            // symbol table entries). Weak refs for these objects are cleared only
            // via explicit undefine() of the referent's last known reference.
        }
    }

    /**
     * Like {@link #deferDecrementIfTracked}, but skips the decrement if the
     * scalar is captured by a closure ({@code captureCount > 0}).
     * Used by the explicit {@code return} bytecode path which bypasses
     * {@link RuntimeScalar#scopeExitCleanup}.
     */
    public static void deferDecrementIfNotCaptured(RuntimeScalar scalar) {
        if (!active || scalar == null) return;
        if (scalar.captureCount > 0) return;
        deferDecrementIfTracked(scalar);
    }

    /**
     * Defer DESTROY for tracked blessed refs in a collection being cleared.
     * <p>
     * Only decrements elements that own a refCount (refCountOwned == true).
     * Elements stored via copy constructor (no setLarge) are skipped.
     * Never-stored blessed objects (refCount == 0) are bumped to ensure DESTROY fires.
     */
    public static void deferDestroyForContainerClear(Iterable<RuntimeScalar> elements) {
        if (!active) return;
        for (RuntimeScalar scalar : elements) {
            if (scalar != null && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && scalar.value instanceof RuntimeBase base) {
                if (scalar.refCountOwned && base.refCount > 0) {
                    // Tracked object with owned refCount: defer decrement
                    scalar.refCountOwned = false;
                    pending.add(base);
                } else if (base.blessId != 0 && base.refCount == 0) {
                    // Never-stored blessed object: bump to 1 so flush triggers DESTROY
                    base.refCount = 1;
                    pending.add(base);
                }
                // Note: WEAKLY_TRACKED (-2) objects are not scheduled here.
                // See deferDecrementIfTracked() for rationale.
            }
        }
    }

    /**
     * Recursively walk a RuntimeHash's values and defer refCount decrements
     * for any tracked blessed references found (including inside nested
     * arrays/hashes). Called at scope exit for {@code my %hash} variables.
     */
    public static void scopeExitCleanupHash(RuntimeHash hash) {
        if (!active || hash == null) return;
        for (RuntimeScalar val : hash.elements.values()) {
            deferDecrementRecursive(val);
        }
    }

    /**
     * Recursively walk a RuntimeArray's elements and defer refCount decrements
     * for any tracked blessed references found (including inside nested
     * arrays/hashes). Called at scope exit for {@code my @array} variables.
     */
    public static void scopeExitCleanupArray(RuntimeArray arr) {
        if (!active || arr == null) return;
        for (RuntimeScalar elem : arr.elements) {
            deferDecrementRecursive(elem);
        }
    }

    /**
     * Recursively process a scalar value: if it holds a reference to a
     * tracked blessed object and owns a refCount, defer a decrement.
     * If it holds a reference to an unblessed container, recurse into
     * its elements.
     */
    private static void deferDecrementRecursive(RuntimeScalar scalar) {
        if (scalar == null || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0) return;
        if (!(scalar.value instanceof RuntimeBase base)) return;

        if (base.blessId != 0) {
            if (scalar.refCountOwned && base.refCount > 0) {
                // Blessed, tracked, and this scalar owns the refCount: defer decrement
                scalar.refCountOwned = false;
                pending.add(base);
            } else if (base.refCount == 0) {
                // Blessed but refCount=0: container didn't increment (e.g., anonymous
                // array constructor). Bump to 1 so flush triggers DESTROY.
                base.refCount = 1;
                pending.add(base);
            }
        } else {
            // Unblessed reference: check if this scalar owns a refCount
            if (scalar.refCountOwned && base.refCount > 0) {
                scalar.refCountOwned = false;
                pending.add(base);
            }
            // Also recurse into unblessed containers to find nested blessed refs
            if (base instanceof RuntimeArray arr) {
                for (RuntimeScalar elem : arr.elements) {
                    deferDecrementRecursive(elem);
                }
            } else if (base instanceof RuntimeHash hash) {
                for (RuntimeScalar val : hash.elements.values()) {
                    deferDecrementRecursive(val);
                }
            }
        }
    }

    /**
     * Mortal-ize blessed refs with refCount==0 in a RuntimeList that will be
     * discarded (void-context call result). Without this, objects that were
     * blessed but never stored in a named variable would leak.
     * Only processes elements with refCount==0 (never-stored objects).
     */
    public static void mortalizeForVoidDiscard(RuntimeList result) {
        if (!active || result == null) return;
        for (RuntimeBase elem : result.elements) {
            if (elem instanceof RuntimeScalar scalar
                    && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && scalar.value instanceof RuntimeBase base
                    && base.blessId != 0 && base.refCount == 0) {
                base.refCount = 1;
                pending.add(base);
            }
        }
    }

    // Mark stack for scoped flushing (analogous to Perl 5's SAVETMPS).
    // Each mark records the pending list size at scope entry, so that
    // popAndFlush() only processes entries added within that scope.
    private static final ArrayList<Integer> marks = new ArrayList<>();

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
        marks.clear(); // All entries drained; marks are meaningless now
    }

    /**
     * Push a mark recording the current pending list size.
     * Called before scope-exit cleanup so that popAndFlush() only
     * processes entries added by the cleanup (not earlier entries
     * from outer scopes or prior operations).
     * Analogous to Perl 5's SAVETMPS.
     */
    public static void pushMark() {
        if (!active) return;
        marks.add(pending.size());
    }

    /**
     * Pop the most recent mark and flush only entries added since it.
     * Called after scope-exit cleanup. Entries before the mark are left
     * for the next full flush() (at apply/setLarge).
     * Analogous to Perl 5's FREETMPS after LEAVE.
     */
    public static void popAndFlush() {
        if (!active || marks.isEmpty()) return;
        int mark = marks.removeLast();
        if (pending.size() <= mark) return;
        // Process entries from mark onwards (DESTROY may add new entries)
        for (int i = mark; i < pending.size(); i++) {
            RuntimeBase base = pending.get(i);
            if (base.refCount > 0 && --base.refCount == 0) {
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            }
        }
        // Remove only the entries we processed (keep entries before mark)
        while (pending.size() > mark) {
            pending.removeLast();
        }
    }
}

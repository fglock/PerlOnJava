package org.perlonjava.runtime.runtimetypes;

import java.util.Objects;

/**
 * Identity for one Perl strong-owner slot.
 *
 * <p>The current runtime still uses selective {@link RuntimeBase#refCount}
 * transitions as its lifecycle bridge. This object records the slot identity
 * independently of the scalar wrapper that happens to implement it, so a
 * migrated owner can be transferred or released exactly once while that
 * bridge is retired incrementally.</p>
 */
public final class PerlOwnerSlot {
    public enum Kind {
        CLOSURE_PAD
    }

    private final Kind kind;
    private final RuntimeScalar padCell;
    private RuntimeBase referent;

    public PerlOwnerSlot(Kind kind) {
        this(kind, null);
    }

    PerlOwnerSlot(Kind kind, RuntimeScalar padCell) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.padCell = padCell;
    }

    public Kind kind() {
        return kind;
    }

    RuntimeScalar padCell() {
        return padCell;
    }

    public synchronized RuntimeBase referent() {
        return referent;
    }

    public synchronized boolean isActive() {
        return referent != null;
    }

    /** Acquire this slot for a referent, or transfer its identity to another. */
    public synchronized void transferTo(RuntimeBase nextReferent) {
        if (referent == nextReferent) return;
        RuntimeBase previous = referent;
        referent = nextReferent;
        if (previous != null) previous.removeOwnerSlot(this);
        if (nextReferent != null) nextReferent.addOwnerSlot(this);
    }

    /** Release this slot. Repeated releases are harmless. */
    public void release() {
        transferTo(null);
    }
}

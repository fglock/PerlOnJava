package org.perlonjava.runtime.runtimetypes;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final AtomicLong NEXT_ID = new AtomicLong();

    public enum Kind {
        CLOSURE_PAD
    }

    private final Kind kind;
    private final RuntimeScalar padCell;
    private final long identity = NEXT_ID.incrementAndGet();
    private RuntimeBase referent;
    private int legacyCaptureCount;
    private long nextReleaseSequence;

    /** Provenance token carried with one queued legacy decrement. */
    public static final class PendingRelease {
        private final long ownerSlotIdentity;
        private final long sequence;
        private final Kind kind;
        private final AtomicBoolean completed = new AtomicBoolean();

        private PendingRelease(long ownerSlotIdentity, long sequence, Kind kind) {
            this.ownerSlotIdentity = ownerSlotIdentity;
            this.sequence = sequence;
            this.kind = kind;
        }

        public long ownerSlotIdentity() {
            return ownerSlotIdentity;
        }

        public long sequence() {
            return sequence;
        }

        public Kind kind() {
            return kind;
        }

        public boolean isCompleted() {
            return completed.get();
        }

        void complete() {
            completed.set(true);
        }
    }

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

    public long identity() {
        return identity;
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

    public synchronized int legacyCaptureCount() {
        return legacyCaptureCount;
    }

    /** Acquire one closure-pad referent count through the legacy bridge. */
    public synchronized void acquireLegacyCapture(RuntimeBase target) {
        Objects.requireNonNull(target, "target");
        if (referent != target) {
            if (legacyCaptureCount != 0) {
                throw new IllegalStateException("cannot transfer a closure pad with live legacy counts");
            }
            transferTo(target);
        }
        if (target.refCount < 0) {
            throw new IllegalStateException("closure capture bridge requires a tracked referent");
        }
        target.traceRefCount(+1, "RuntimeScalar.retainClosureCaptureReferent");
        target.refCount++;
        target.hadCountedReference = true;
        target.acquireTransientTraceOwner("closure capture", "RuntimeScalar.closureCapture");
        legacyCaptureCount++;
    }

    /** Queue one closure-pad count release and retain its slot provenance. */
    public synchronized PendingRelease deferLegacyCaptureRelease(RuntimeBase target) {
        if (legacyCaptureCount <= 0) return null;
        legacyCaptureCount--;
        if (target == null || target.refCount <= 0) {
            if (target != null) {
                target.releaseTransientTraceOwner("closure capture", "PerlOwnerSlot.deferLegacyCaptureRelease");
            }
            return null;
        }
        PendingRelease release = new PendingRelease(identity, ++nextReleaseSequence, kind);
        MortalList.deferOwnerSlotDecrement(target, "closure capture", release);
        return release;
    }

    /** Acquire this slot for a referent, or transfer its identity to another. */
    public synchronized void transferTo(RuntimeBase nextReferent) {
        if (referent == nextReferent) return;
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("cannot transfer an owner slot with live legacy counts");
        }
        RuntimeBase previous = referent;
        referent = nextReferent;
        if (previous != null) previous.removeOwnerSlot(this);
        if (nextReferent != null) nextReferent.addOwnerSlot(this);
    }

    /** Release this slot. Repeated releases are harmless. */
    public synchronized void release() {
        RuntimeBase previous = referent;
        referent = null;
        if (previous != null) previous.removeOwnerSlot(this);
    }
}

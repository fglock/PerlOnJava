package org.perlonjava.runtime.runtimetypes;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.ref.WeakReference;

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
        CLOSURE_PAD,
        HASH_ENTRY,
        ARRAY_ELEMENT
    }

    private final Kind kind;
    private final RuntimeScalar padCell;
    private final WeakReference<RuntimeBase> ownerContainer;
    private volatile RuntimeCode closureOwner;
    private final long identity = NEXT_ID.incrementAndGet();
    private RuntimeBase referent;
    private PerlRuntime ownerRuntime;
    private LifecycleRuntimeState ownerRuntimeState;
    private int legacyCaptureCount;
    private volatile boolean nativeCaptureOwner;
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
        this(kind, null, null);
    }

    PerlOwnerSlot(Kind kind, RuntimeScalar padCell) {
        this(kind, padCell, null);
    }

    PerlOwnerSlot(Kind kind, RuntimeScalar padCell, RuntimeBase ownerContainer) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.padCell = padCell;
        this.ownerContainer = ownerContainer == null ? null : new WeakReference<>(ownerContainer);
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

    RuntimeBase ownerContainer() {
        return ownerContainer == null ? null : ownerContainer.get();
    }

    boolean isNativeCaptureOwner() {
        return nativeCaptureOwner;
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

    RuntimeCode closureOwner() {
        return closureOwner;
    }

    /** Acquire one closure-pad referent count through the legacy bridge. */
    public synchronized void acquireLegacyCapture(RuntimeBase target) {
        Objects.requireNonNull(target, "target");
        if (target.refCount < 0) {
            throw new IllegalStateException("closure capture bridge requires a tracked referent");
        }
        if (target.refCount == Integer.MAX_VALUE
                || legacyCaptureCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("closure capture bridge count overflow");
        }
        if (referent != target) {
            if (legacyCaptureCount != 0) {
                throw new IllegalStateException("cannot transfer a closure pad with live legacy counts");
            }
            transferTo(target);
        }
        target.traceRefCount(+1, "RuntimeScalar.retainClosureCaptureReferent");
        target.refCount++;
        target.hadCountedReference = true;
        target.acquireTransientTraceOwner("closure capture", "RuntimeScalar.closureCapture");
        legacyCaptureCount++;
    }

    /** Acquire a captured aggregate pad as an explicit owner slot. */
    public synchronized void acquireCapture(RuntimeBase target) {
        acquireCapture(target, null);
    }

    /** Acquire a captured aggregate pad as an edge from its owning closure. */
    public synchronized void acquireCapture(RuntimeBase target, RuntimeCode owner) {
        Objects.requireNonNull(target, "target");
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot capture a destroyed referent");
        }
        nativeCaptureOwner = true;
        closureOwner = owner;
        transferTo(target);
    }

    /** Attach this stable hash-entry slot to its current direct referent. */
    public synchronized void acquireHashEntry(RuntimeBase target) {
        if (kind != Kind.HASH_ENTRY) {
            throw new IllegalStateException("hash-entry acquisition requires a HASH_ENTRY slot");
        }
        transferTo(target);
    }

    /** Attach this stable array-element slot to its current direct referent. */
    public synchronized void acquireArrayElement(RuntimeBase target) {
        if (kind != Kind.ARRAY_ELEMENT) {
            throw new IllegalStateException("array-element acquisition requires an ARRAY_ELEMENT slot");
        }
        transferTo(target);
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
        PerlRuntime previousRuntime = ownerRuntime;
        LifecycleRuntimeState previousState = ownerRuntimeState;
        // Hash entry slots are owned by their hash, so retaining their
        // referent from the runtime would turn an unrooted hash into a root.
        // Closure-pad slots are runtime roots while their Perl closure lives.
        PerlRuntime nextRuntime = kind != Kind.CLOSURE_PAD || nextReferent == null
                ? null : PerlRuntime.currentOrNull();
        if (kind == Kind.CLOSURE_PAD && nextReferent != null && nextRuntime == null) {
            nextRuntime = previousRuntime;
        }

        referent = nextReferent;
        ownerRuntime = nextRuntime;
        ownerRuntimeState = nextRuntime == null || nextReferent == null
                ? null : nextRuntime.retainOwnerSlotReferent(nextReferent);
        if (previous != null) previous.removeOwnerSlot(this);
        if (previous != null) {
            PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
        }
        if (nextReferent != null) nextReferent.addOwnerSlot(this);
    }

    /** Release this slot. Repeated releases are harmless. */
    public synchronized void release() {
        RuntimeBase previous = referent;
        LifecycleRuntimeState previousState = ownerRuntimeState;
        referent = null;
        ownerRuntime = null;
        ownerRuntimeState = null;
        if (previous != null) previous.removeOwnerSlot(this);
        if (previous != null) PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
    }

}

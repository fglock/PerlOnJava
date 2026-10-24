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
        ARRAY_ELEMENT,
        SCALAR_REFERENCE_CONTENTS,
        SCALAR_VALUE,
        TIE_WRAPPER
    }

    private final Kind kind;
    private final RuntimeScalar padCell;
    private final WeakReference<RuntimeBase> ownerContainer;
    private volatile WeakReference<RuntimeScalar> ownerCell;
    private volatile WeakReference<RuntimeScalar> scalarReferenceCell;
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
        private final PerlOwnerSlot detachOnCompletion;
        private final AtomicBoolean completed = new AtomicBoolean();

        private PendingRelease(long ownerSlotIdentity, long sequence, Kind kind) {
            this(ownerSlotIdentity, sequence, kind, null);
        }

        private PendingRelease(long ownerSlotIdentity, long sequence, Kind kind,
                               PerlOwnerSlot detachOnCompletion) {
            this.ownerSlotIdentity = ownerSlotIdentity;
            this.sequence = sequence;
            this.kind = kind;
            this.detachOnCompletion = detachOnCompletion;
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
            if (!completed.compareAndSet(false, true)) return;
            if (detachOnCompletion != null) detachOnCompletion.detachAfterDeferredRelease();
        }
    }

    public PerlOwnerSlot(Kind kind) {
        this(kind, null, null);
    }

    PerlOwnerSlot(Kind kind, RuntimeScalar padCell) {
        this(kind, padCell, null);
    }

    PerlOwnerSlot(Kind kind, RuntimeScalar padCell, RuntimeBase ownerContainer) {
        this(kind, padCell, ownerContainer, null);
    }

    PerlOwnerSlot(Kind kind, RuntimeScalar padCell, RuntimeBase ownerContainer,
                  RuntimeScalar ownerCell) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.padCell = padCell;
        this.ownerContainer = ownerContainer == null ? null : new WeakReference<>(ownerContainer);
        this.ownerCell = ownerCell == null ? null : new WeakReference<>(ownerCell);
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

    RuntimeScalar ownerCell() {
        WeakReference<RuntimeScalar> reference = ownerCell;
        return reference == null ? null : reference.get();
    }

    RuntimeScalar scalarReferenceCell() {
        WeakReference<RuntimeScalar> reference = scalarReferenceCell;
        return reference == null ? null : reference.get();
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
        acquireHashEntry(target, null);
    }

    /** Attach this stable hash-entry slot to its current scalar cell and referent. */
    public synchronized void acquireHashEntry(RuntimeBase target, RuntimeScalar cell) {
        if (kind != Kind.HASH_ENTRY) {
            throw new IllegalStateException("hash-entry acquisition requires a HASH_ENTRY slot");
        }
        ownerCell = cell == null ? null : new WeakReference<>(cell);
        transferTo(target);
    }

    /** Attach this stable array-element slot to its current direct referent. */
    public synchronized void acquireArrayElement(RuntimeBase target) {
        acquireArrayElement(target, null);
    }

    /** Attach this stable array-element slot to its current scalar cell and referent. */
    public synchronized void acquireArrayElement(RuntimeBase target, RuntimeScalar cell) {
        if (kind != Kind.ARRAY_ELEMENT) {
            throw new IllegalStateException("array-element acquisition requires an ARRAY_ELEMENT slot");
        }
        ownerCell = cell == null ? null : new WeakReference<>(cell);
        transferTo(target);
    }

    /**
     * Attach one scalar-reference alias slot to its cell's contents.
     * Distinct aliases keep distinct reachability identities, while aliases
     * to the same scalar cell share one referent count.
     */
    public synchronized void acquireScalarReferenceContents(RuntimeBase target) {
        acquireScalarReferenceContents(target, null);
    }

    public synchronized void acquireScalarReferenceContents(
            RuntimeBase target, RuntimeScalar referenceCell) {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference acquisition requires a SCALAR_REFERENCE_CONTENTS slot");
        }
        Objects.requireNonNull(target, "target");
        if (target.refCount < 0) {
            throw new IllegalStateException(
                    "scalar-reference contents require a tracked referent");
        }
        if (referent != null && referent != target) {
            throw new IllegalStateException("cannot retarget an active scalar-reference owner");
        }
        if (referent == target) {
            RuntimeScalar currentCell = scalarReferenceCell();
            if (currentCell == referenceCell) return;
            throw new IllegalStateException(
                    "an active scalar-reference owner cannot change cells during acquisition");
        }
        boolean firstOwnerForCell = !hasSiblingScalarReferenceContentsOwner(
                referenceCell, target);
        if (firstOwnerForCell && target.refCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("scalar-reference contents count overflow");
        }
        transferTo(target);
        if (firstOwnerForCell) {
            target.traceRefCount(+1, "PerlOwnerSlot.acquireScalarReferenceContents");
            target.refCount++;
            target.hadCountedReference = true;
            target.acquireTransientTraceOwner(
                    "scalar-reference contents", "PerlOwnerSlot.scalarReferenceContents");
        }
        scalarReferenceCell = referenceCell == null ? null : new WeakReference<>(referenceCell);
        if (referenceCell != null) referenceCell.registerScalarReferenceContentsOwner(this);
    }

    /** Move the scalar cell that represents this contents owner. */
    public synchronized void transferScalarReferenceContentsCell(RuntimeScalar cell) {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference cell transfer requires a SCALAR_REFERENCE_CONTENTS slot");
        }
        if (referent == null) {
            throw new IllegalStateException("cannot transfer an inactive scalar-reference owner");
        }
        ownerCell = cell == null ? null : new WeakReference<>(cell);
    }

    /** Release this scalar-reference contents owner and dispatch on final release. */
    public synchronized void releaseScalarReferenceContents() {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference release requires a SCALAR_REFERENCE_CONTENTS slot");
        }
        RuntimeBase target = referent;
        if (target == null) return;
        RuntimeScalar referenceCell = scalarReferenceCell == null ? null : scalarReferenceCell.get();
        scalarReferenceCell = null;
        if (referenceCell != null) referenceCell.unregisterScalarReferenceContentsOwner(this);
        transferTo(null);
        if (hasSiblingScalarReferenceContentsOwner(referenceCell, target)) return;
        if (target.refCount <= 0) return;
        target.traceRefCount(-1, "PerlOwnerSlot.releaseScalarReferenceContents");
        target.releaseTransientTraceOwner(
                "scalar-reference contents", "PerlOwnerSlot.scalarReferenceContents");
        if (--target.refCount == 0) {
            target.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(target);
        }
    }

    /** Queue a scalar-reference contents decrement with stable slot provenance. */
    public synchronized PendingRelease deferScalarReferenceContentsRelease() {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference release requires a SCALAR_REFERENCE_CONTENTS slot");
        }
        RuntimeBase target = referent;
        if (target == null) return null;
        if (target.refCount <= 0) {
            releaseScalarReferenceContents();
            target.releaseTransientTraceOwner(
                    "scalar-reference contents", "PerlOwnerSlot.deferScalarReferenceContentsRelease");
            return null;
        }
        RuntimeScalar referenceCell = scalarReferenceCell == null ? null : scalarReferenceCell.get();
        scalarReferenceCell = null;
        if (referenceCell != null) referenceCell.unregisterScalarReferenceContentsOwner(this);
        if (hasSiblingScalarReferenceContentsOwner(referenceCell, target)) {
            transferTo(null);
            return null;
        }
        PendingRelease release = new PendingRelease(identity, ++nextReleaseSequence, kind, this);
        MortalList.deferOwnerSlotDecrement(target, "scalar-reference contents", release);
        return release;
    }

    /** Transfer the contents edge when its referenced scalar cell is overwritten. */
    public synchronized void retargetScalarReferenceContents(RuntimeBase nextTarget) {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference retargeting requires a SCALAR_REFERENCE_CONTENTS slot");
        }
        RuntimeBase previous = referent;
        if (previous == nextTarget) return;
        RuntimeScalar referenceCell = scalarReferenceCell == null ? null : scalarReferenceCell.get();
        boolean firstOwnerForNextTarget = nextTarget != null
                && !hasSiblingScalarReferenceContentsOwner(referenceCell, nextTarget);
        if (nextTarget != null) {
            if (nextTarget.refCount < 0) {
                nextTarget = null;
            } else if (firstOwnerForNextTarget) {
                if (nextTarget.refCount == Integer.MAX_VALUE) {
                    throw new IllegalStateException("scalar-reference contents count overflow");
                }
                nextTarget.traceRefCount(+1, "PerlOwnerSlot.retargetScalarReferenceContents");
                nextTarget.refCount++;
                nextTarget.hadCountedReference = true;
                nextTarget.acquireTransientTraceOwner(
                        "scalar-reference contents", "PerlOwnerSlot.scalarReferenceContents");
            }
        }
        transferTo(nextTarget);
        if (nextTarget == null && scalarReferenceCell != null) {
            scalarReferenceCell = null;
            if (referenceCell != null) referenceCell.unregisterScalarReferenceContentsOwner(this);
        }
        if (previous == null
                || hasSiblingScalarReferenceContentsOwner(referenceCell, previous)
                || previous.refCount <= 0) return;
        previous.traceRefCount(-1, "PerlOwnerSlot.retargetScalarReferenceContents");
        previous.releaseTransientTraceOwner(
                "scalar-reference contents", "PerlOwnerSlot.scalarReferenceContents");
        if (--previous.refCount == 0) {
            previous.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(previous);
        }
    }

    /** Acquire one tracked direct-scalar owner through this slot. */
    synchronized boolean acquireScalarValueCount(RuntimeBase target, String acquireSite) {
        if (kind != Kind.SCALAR_VALUE) {
            throw new IllegalStateException(
                    "scalar-value acquisition requires a SCALAR_VALUE slot");
        }
        Objects.requireNonNull(target, "target");
        if (referent == target) return true;
        if (referent != null) {
            throw new IllegalStateException("cannot retarget an active scalar-value owner");
        }
        if (target.refCount < 0) return false;
        if (target.refCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("scalar-value owner count overflow");
        }
        target.traceRefCount(+1, acquireSite);
        target.refCount++;
        target.hadCountedReference = true;
        RuntimeScalar scalar = ownerCell();
        if (scalar != null) {
            scalar.refCountOwned = true;
            target.recordOwner(scalar, acquireSite);
            target.recordActiveOwner(scalar);
        }
        transferTo(target);
        return true;
    }

    /**
     * Release the legacy counted edge represented by a direct scalar-value
     * slot. The caller retains lifecycle policy (for example, whether a
     * lexical binding suppresses DESTROY) after receiving the decremented
     * count. This makes explicit slot identity the authority for the scalar
     * owner's release while the referent counter remains the dispatch bridge.
     */
    synchronized boolean releaseScalarValueCount(RuntimeBase expectedTarget, String releaseSite) {
        if (kind != Kind.SCALAR_VALUE) {
            throw new IllegalStateException(
                    "scalar-value release requires a SCALAR_VALUE slot");
        }
        if (referent != expectedTarget || expectedTarget == null
                || expectedTarget.refCount <= 0) {
            return false;
        }
        RuntimeScalar scalar = ownerCell();
        transferTo(null);
        if (scalar != null) scalar.refCountOwned = false;
        expectedTarget.traceRefCount(-1, releaseSite);
        if (scalar != null) {
            expectedTarget.releaseOwner(scalar, releaseSite);
            expectedTarget.releaseActiveOwner(scalar);
        }
        expectedTarget.refCount--;
        return true;
    }

    /** Acquire the tie wrapper's independent strong reference to its handler. */
    synchronized boolean acquireTieWrapperCount(RuntimeBase target, String acquireSite) {
        if (kind != Kind.TIE_WRAPPER) {
            throw new IllegalStateException("tie-wrapper acquisition requires a TIE_WRAPPER slot");
        }
        Objects.requireNonNull(target, "target");
        if (referent == target) return true;
        if (referent != null) {
            throw new IllegalStateException("cannot retarget an active tie-wrapper owner");
        }
        if (target.refCount < 0) return false;
        if (target.refCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("tie-wrapper owner count overflow");
        }
        target.traceRefCount(+1, acquireSite);
        target.refCount++;
        target.hadCountedReference = true;
        transferTo(target);
        return true;
    }

    /** Retire a tie-wrapper owner identity and decrement its legacy bridge count. */
    synchronized boolean releaseTieWrapperCount(RuntimeBase expectedTarget, String releaseSite) {
        if (kind != Kind.TIE_WRAPPER) {
            throw new IllegalStateException("tie-wrapper release requires a TIE_WRAPPER slot");
        }
        if (referent != expectedTarget || expectedTarget == null) return false;
        transferTo(null);
        if (expectedTarget.refCount <= 0) return true;
        expectedTarget.traceRefCount(-1, releaseSite);
        expectedTarget.refCount--;
        return true;
    }

    /** Detach a scalar-value owner and preserve its identity for deferred release. */
    synchronized PendingRelease deferScalarValueCountRelease(RuntimeBase expectedTarget) {
        if (kind != Kind.SCALAR_VALUE) {
            throw new IllegalStateException(
                    "scalar-value release requires a SCALAR_VALUE slot");
        }
        if (referent != expectedTarget || expectedTarget == null) return null;
        PendingRelease release = new PendingRelease(identity, ++nextReleaseSequence, kind);
        RuntimeScalar scalar = ownerCell();
        if (scalar != null) scalar.refCountOwned = false;
        release();
        return release;
    }

    private synchronized void detachAfterDeferredRelease() {
        transferTo(null);
    }

    private boolean hasSiblingScalarReferenceContentsOwner(
            RuntimeScalar referenceCell, RuntimeBase target) {
        if (referenceCell == null || target == null) return false;
        for (PerlOwnerSlot slot : referenceCell.scalarReferenceContentsOwnersSnapshot()) {
            if (slot != this && slot.referent() == target) return true;
        }
        return false;
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
        ownerCell = null;
        if (previous != null) previous.removeOwnerSlot(this);
        if (previous != null) PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
    }

}

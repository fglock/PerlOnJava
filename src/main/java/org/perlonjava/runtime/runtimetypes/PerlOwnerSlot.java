package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.WeakReference;
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
        CLOSURE_PAD,
        SCALAR_CAPTURE,
        SCALAR_CELL,
        CODE_SLOT,
        ARRAY_ELEMENT,
        HASH_ELEMENT,
        ARGUMENT_VALUE,
        ARGUMENT_ALIAS,
        RETURN_VALUE,
        ACTIVE_CODE,
        TIE_HANDLER
    }

    private Kind kind;
    private final RuntimeScalar padCell;
    private final long identity = NEXT_ID.incrementAndGet();
    private RuntimeBase referent;
    /** Weak back-reference to the scalar implementing a counted cell owner. */
    private WeakReference<RuntimeScalar> countedScalarOwner;
    /** CODE object that owns a closure-pad identity; weak to avoid a metadata cycle. */
    private WeakReference<RuntimeCode> captureOwner;
    private PerlRuntime ownerRuntime;
    private LifecycleRuntimeState ownerRuntimeState;
    private boolean registeredInOwnerLedger;
    private int legacyCaptureCount;
    private volatile boolean nativeOwner;
    private long nextReleaseSequence;

    /** Provenance token carried with one queued legacy decrement. */
    public static final class PendingRelease {
        private final long ownerSlotIdentity;
        private final long sequence;
        private final Kind kind;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final LifecycleRuntimeState retainedState;
        private final RuntimeBase retainedReferent;

        private PendingRelease(long ownerSlotIdentity, long sequence, Kind kind) {
            this(ownerSlotIdentity, sequence, kind, null);
        }

        private PendingRelease(long ownerSlotIdentity, long sequence, Kind kind,
                               RuntimeBase referent) {
            this.ownerSlotIdentity = ownerSlotIdentity;
            this.sequence = sequence;
            this.kind = kind;
            PerlRuntime runtime = PerlRuntime.currentOrNull();
            boolean transfer = kind == Kind.ARGUMENT_VALUE || kind == Kind.RETURN_VALUE
                    || kind == Kind.CODE_SLOT;
            retainedReferent = transfer ? referent : null;
            retainedState = retainedReferent == null || runtime == null
                    ? null : runtime.retainOwnerSlotReferent(retainedReferent);
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
            if (completed.compareAndSet(false, true)) {
                PerlRuntime.releaseOwnerSlotReferent(retainedState, retainedReferent);
            }
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

    boolean isNativeCaptureOwner() {
        return kind == Kind.CLOSURE_PAD && nativeOwner;
    }

    /** True while this active slot is counted directly by the semantic owner ledger. */
    public boolean isNativeOwner() {
        return nativeOwner && referent != null;
    }

    /** True if this slot uses native-ledger release semantics, including after release. */
    public boolean usesNativeOwnerLedger() {
        return nativeOwner;
    }

    public synchronized RuntimeBase referent() {
        return referent;
    }

    /** Scalar cell implementing this counted edge, when it has one. */
    public synchronized RuntimeScalar countedScalarOwner() {
        return countedScalarOwner == null ? null : countedScalarOwner.get();
    }

    synchronized void associateCountedScalarOwner(RuntimeScalar scalar) {
        if (!isCountedKind(kind)) {
            throw new IllegalStateException("scalar owner association requires a counted slot");
        }
        countedScalarOwner = scalar == null ? null : new WeakReference<>(scalar);
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

    /** Acquire an aggregate pad capture and record the CODE owner of this slot. */
    public synchronized void acquireCapture(RuntimeBase target, RuntimeCode owner) {
        if (kind != Kind.CLOSURE_PAD) {
            throw new IllegalStateException("capture acquisition requires a closure-pad slot");
        }
        acquireNativeOwner(target);
        captureOwner = owner == null ? null : new WeakReference<>(owner);
    }

    /** Acquire this slot as one semantic Perl owner, without touching refCount. */
    public synchronized void acquireNativeOwner(RuntimeBase target) {
        Objects.requireNonNull(target, "target");
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot own a destroyed referent");
        }
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("cannot migrate a slot with pending legacy releases");
        }
        nativeOwner = true;
        transferTo(target);
    }

    /**
     * Record a scalar-capture identity held by its RuntimeCode owner. The
     * closure already strongly owns the pad cell through capturedScalars, so
     * registering a second runtime root would let an unreachable closure's
     * slot outlive the closure and retain request resources.
     */
    public synchronized void acquireCaptureIdentity(RuntimeBase target) {
        acquireCaptureIdentity(target, null);
    }

    /** Record a scalar-pad identity and the CODE object that owns the capture. */
    public synchronized void acquireCaptureIdentity(RuntimeBase target, RuntimeCode owner) {
        if (kind != Kind.SCALAR_CAPTURE) {
            throw new IllegalStateException("capture identity requires a scalar-capture slot");
        }
        Objects.requireNonNull(target, "target");
        if (referent != null || nativeOwner) {
            throw new IllegalStateException("capture identity is already active");
        }
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot capture a destroyed pad cell");
        }
        referent = target;
        captureOwner = owner == null ? null : new WeakReference<>(owner);
        registeredInOwnerLedger = true;
        target.addOwnerSlot(this);
    }

    synchronized RuntimeCode captureOwner() {
        return captureOwner == null ? null : captureOwner.get();
    }

    synchronized void rebindCaptureOwner(RuntimeCode owner) {
        if (kind != Kind.SCALAR_CAPTURE && kind != Kind.CLOSURE_PAD) {
            throw new IllegalStateException("only closure-pad slots have a CODE owner");
        }
        if (referent == null) return;
        captureOwner = owner == null ? null : new WeakReference<>(owner);
    }

    /** The active frame already retains the CV; no second global root is needed. */
    synchronized void acquireActiveCodeIdentity(RuntimeCode target) {
        if (kind != Kind.ACTIVE_CODE || referent != null) {
            throw new IllegalStateException("active CODE slot is not available");
        }
        referent = Objects.requireNonNull(target, "target");
        nativeOwner = true;
        registeredInOwnerLedger = true;
        target.addOwnerSlot(this);
    }

    /** Identify a counted scalar or element edge without adding a global root. */
    public synchronized void acquireCountedIdentity(RuntimeBase target) {
        if (!isCountedKind(kind)) {
            throw new IllegalStateException("counted acquisition requires a scalar or element slot");
        }
        Objects.requireNonNull(target, "target");
        if (nativeOwner || legacyCaptureCount != 0) {
            throw new IllegalStateException("counted identity cannot replace native or capture ownership");
        }
        if (referent == target) return;
        release();
        referent = target;
        registeredInOwnerLedger = true;
        target.addOwnerSlot(this);
        if (kind == Kind.ARGUMENT_VALUE) {
            ownerRuntime = PerlRuntime.currentOrNull();
            ownerRuntimeState = ownerRuntime == null
                    ? null : ownerRuntime.retainOwnerSlotReferent(target);
        }
    }

    /**
     * Record an active {@code @_} alias to a scalar cell without adding a
     * second Perl-visible reference count for the aliased value.
     */
    synchronized void acquireArgumentAliasIdentity(RuntimeBase target) {
        if (kind != Kind.ARGUMENT_ALIAS) {
            throw new IllegalStateException("argument-alias acquisition requires an alias slot");
        }
        Objects.requireNonNull(target, "target");
        if (target.refCount == Integer.MIN_VALUE) {
            release();
            return;
        }
        if (nativeOwner || legacyCaptureCount != 0) {
            throw new IllegalStateException("argument alias cannot replace native ownership");
        }
        if (referent == target) return;

        // The call frame already strongly owns the scalar cell, which in turn
        // owns its current value. Record the slot identity on the referent for
        // cleanup decisions without adding a second runtime root.
        RuntimeBase previous = referent;
        LifecycleRuntimeState previousState = ownerRuntimeState;
        referent = target;
        ownerRuntime = null;
        ownerRuntimeState = null;
        if (previous != null && registeredInOwnerLedger) {
            previous.removeOwnerSlot(this);
            PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
        }
        registeredInOwnerLedger = true;
        target.addOwnerSlot(this);
    }

    synchronized void reclassifyCountedIdentity(Kind nextKind) {
        if (nativeOwner || !isCountedKind(kind) || !isCountedKind(nextKind)) {
            throw new IllegalStateException("only counted scalar and element slots can move storage");
        }
        if (kind != nextKind && referent != null) {
            if (nextKind == Kind.ARGUMENT_VALUE) {
                ownerRuntime = PerlRuntime.currentOrNull();
                ownerRuntimeState = ownerRuntime == null
                        ? null : ownerRuntime.retainOwnerSlotReferent(referent);
            } else if (kind == Kind.ARGUMENT_VALUE) {
                PerlRuntime.releaseOwnerSlotReferent(ownerRuntimeState, referent);
                ownerRuntime = null;
                ownerRuntimeState = null;
            }
        }
        kind = nextKind;
    }

    static boolean isCountedKind(Kind kind) {
        return kind == Kind.SCALAR_CELL || kind == Kind.CODE_SLOT || kind == Kind.ARRAY_ELEMENT
                || kind == Kind.HASH_ELEMENT || kind == Kind.ARGUMENT_VALUE
                || kind == Kind.RETURN_VALUE || kind == Kind.TIE_HANDLER;
    }

    /** Retire this edge and carry its identity through one deferred decrement. */
    public synchronized PendingRelease releaseCountedIdentity() {
        if (referent == null) return null;
        if (nativeOwner || legacyCaptureCount != 0) {
            throw new IllegalStateException("counted release requires a counted identity");
        }
        PendingRelease release = new PendingRelease(identity, ++nextReleaseSequence, kind, referent);
        release();
        return release;
    }

    /** Replace this native slot's referent while retaining the slot identity. */
    public synchronized void replaceNativeOwner(RuntimeBase nextReferent) {
        if (!nativeOwner || referent == null) {
            throw new IllegalStateException("cannot replace a slot that is not a native owner");
        }
        Objects.requireNonNull(nextReferent, "nextReferent");
        if (nextReferent.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot own a destroyed referent");
        }
        transferTo(nextReferent);
    }

    /** Read the current referent as a borrow; this does not acquire ownership. */
    public synchronized RuntimeBase borrowReferent() {
        return referent;
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
        PerlRuntime nextRuntime = nextReferent == null
                ? null : PerlRuntime.currentOrNull();
        if (nextReferent != null && nextRuntime == null) nextRuntime = previousRuntime;

        referent = nextReferent;
        ownerRuntime = nextRuntime;
        ownerRuntimeState = nextRuntime == null || nextReferent == null
                ? null : nextRuntime.retainOwnerSlotReferent(nextReferent);
        if (previous != null && registeredInOwnerLedger) previous.removeOwnerSlot(this);
        if (previous != null && registeredInOwnerLedger) {
            PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
        }
        registeredInOwnerLedger = nextReferent != null;
        if (registeredInOwnerLedger) nextReferent.addOwnerSlot(this);
    }

    /** Release this slot. Repeated releases are harmless. */
    public synchronized void release() {
        RuntimeBase previous = referent;
        LifecycleRuntimeState previousState = ownerRuntimeState;
        referent = null;
        countedScalarOwner = null;
        captureOwner = null;
        ownerRuntime = null;
        ownerRuntimeState = null;
        if (previous != null && registeredInOwnerLedger) previous.removeOwnerSlot(this);
        if (previous != null && registeredInOwnerLedger) {
            PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
        }
        registeredInOwnerLedger = false;
    }


}

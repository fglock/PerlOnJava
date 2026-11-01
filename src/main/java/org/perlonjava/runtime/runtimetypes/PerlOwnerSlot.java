package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identity for one Perl strong-owner slot or explicitly tracked runtime
 * compatibility hold.
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
        /** Compiled CODE optree retaining one backslash-addressable pad constant. */
        CODE_PAD_CONSTANT,
        /** Executable regex callback retaining its closure CODE object. */
        REGEX_CALLBACK,
        ARRAY_ELEMENT,
        HASH_ELEMENT,
        ARGUMENT_VALUE,
        ARGUMENT_ALIAS,
        RETURN_VALUE,
        ACTIVE_CODE,
        TIE_HANDLER,
        /** Package scalar table entry owning one scalar cell. */
        PACKAGE_SCALAR_SLOT,
        /** Package array or hash table entry owning one aggregate. */
        PACKAGE_AGGREGATE_SLOT,
        /** Named IO stash entry owning one typeglob. */
        PACKAGE_GLOB_SLOT,
        /** Active lexical frame or top-level pad entry owning an aggregate. */
        ACTIVE_LEXICAL,
        /** Strong value held by a lexical register in a suspended interpreter frame. */
        SUSPENDED_FRAME,
        /** Strong container edge whose referent is not on the legacy refcount bridge. */
        UNTRACKED_REFERENCE,
        /** Scalar slot retaining an anonymous IO glob's live resource. */
        IO_RESOURCE_HOLDER,
        /** Temporary legacy count pin for a package-rooted weak aggregate. */
        WEAKLY_TRACKED_COMPATIBILITY_PIN,
        /** Runtime-owned temporary value retained across a cleanup drain. */
        TEMPORARY_ROOT,
        /** Scalar wrapper retaining the current contents of a referenced scalar cell. */
        SCALAR_REFERENCE_CONTENTS
    }

    private Kind kind;
    private final RuntimeScalar padCell;
    private final long identity = NEXT_ID.incrementAndGet();
    private RuntimeBase referent;
    /** Scalar cell that owns an untracked value edge; weak to avoid retaining its source. */
    private WeakReference<RuntimeScalar> untrackedScalarOwner;
    /** Weak back-reference to the scalar implementing a counted cell owner. */
    private WeakReference<RuntimeScalar> countedScalarOwner;
    /** CODE object that owns a closure-pad identity; weak to avoid a metadata cycle. */
    private WeakReference<RuntimeCode> captureOwner;
    /** Referenced scalar cell whose contents this wrapper keeps alive. */
    private WeakReference<RuntimeScalar> scalarReferenceCell;
    /** Scalar wrapper that currently carries this scalar-reference identity. */
    private WeakReference<RuntimeScalar> scalarContentsOwner;
    /** Lexical cell whose current value is retained by a suspended frame. */
    private WeakReference<RuntimeScalar> suspendedScalarOwner;
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

    synchronized void associateUntrackedScalarOwner(RuntimeScalar scalar) {
        if (kind != Kind.UNTRACKED_REFERENCE) {
            throw new IllegalStateException("scalar owner association requires an untracked-reference slot");
        }
        untrackedScalarOwner = scalar == null ? null : new WeakReference<>(scalar);
    }

    synchronized RuntimeScalar untrackedScalarOwner() {
        return untrackedScalarOwner == null ? null : untrackedScalarOwner.get();
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

    /** Source cell for a suspended-frame owner, if that register is scalar-backed. */
    public synchronized RuntimeScalar suspendedScalarOwner() {
        return suspendedScalarOwner == null ? null : suspendedScalarOwner.get();
    }

    synchronized void associateSuspendedScalarOwner(RuntimeScalar owner) {
        if (kind != Kind.SUSPENDED_FRAME) {
            throw new IllegalStateException("scalar association requires a suspended-frame slot");
        }
        suspendedScalarOwner = owner == null ? null : new WeakReference<>(owner);
    }

    /** Scalar cell implementing this counted edge, when it has one. */
    public synchronized RuntimeScalar countedScalarOwner() {
        return countedScalarOwner == null ? null : countedScalarOwner.get();
    }

    synchronized RuntimeScalar scalarReferenceCell() {
        return scalarReferenceCell == null ? null : scalarReferenceCell.get();
    }

    synchronized RuntimeScalar scalarContentsOwner() {
        return scalarContentsOwner == null ? null : scalarContentsOwner.get();
    }

    synchronized void transferScalarReferenceContentsOwner(RuntimeScalar owner) {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference owner transfer requires a scalar-contents slot");
        }
        if (referent == null) {
            throw new IllegalStateException("cannot transfer an inactive scalar-contents owner");
        }
        scalarContentsOwner = owner == null ? null : new WeakReference<>(owner);
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

    /** Record one package scalar table entry as an identity-owned scalar cell. */
    synchronized void acquirePackageScalarIdentity(RuntimeScalar target) {
        if (kind != Kind.PACKAGE_SCALAR_SLOT) {
            throw new IllegalStateException("package scalar acquisition requires a package-slot identity");
        }
        Objects.requireNonNull(target, "target");
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("package scalar slot has pending legacy releases");
        }
        transferWithoutRuntimeRoot(target, true);
    }

    /** Record one compiled CODE optree's strong reference to a pad constant. */
    synchronized void acquireCodePadConstantIdentity(RuntimeBase target) {
        if (kind != Kind.CODE_PAD_CONSTANT) {
            throw new IllegalStateException("pad-constant acquisition requires a CODE-pad slot");
        }
        Objects.requireNonNull(target, "target");
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot own a destroyed pad constant");
        }
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("pad-constant slot has pending legacy releases");
        }
        transferWithoutRuntimeRoot(target, false);
    }

    /** Record one package array/hash table entry as an identity-owned aggregate root. */
    synchronized void acquirePackageAggregateIdentity(RuntimeBase target) {
        if (kind != Kind.PACKAGE_AGGREGATE_SLOT) {
            throw new IllegalStateException("package aggregate acquisition requires an aggregate-slot identity");
        }
        Objects.requireNonNull(target, "target");
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("package aggregate slot has pending legacy releases");
        }
        transferWithoutRuntimeRoot(target, true);
    }

    /** Record one named IO stash entry as an identity-owned typeglob root. */
    synchronized void acquirePackageGlobIdentity(RuntimeGlob target) {
        if (kind != Kind.PACKAGE_GLOB_SLOT) {
            throw new IllegalStateException("package glob acquisition requires a glob-slot identity");
        }
        Objects.requireNonNull(target, "target");
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("package glob slot has pending legacy releases");
        }
        transferWithoutRuntimeRoot(target, true);
    }

    /** Record one active lexical pad entry that owns an aggregate. */
    synchronized void acquireActiveLexicalIdentity(RuntimeBase target) {
        if (kind != Kind.ACTIVE_LEXICAL) {
            throw new IllegalStateException("active lexical acquisition requires an active-lexical slot");
        }
        Objects.requireNonNull(target, "target");
        if (!(target instanceof RuntimeArray || target instanceof RuntimeHash)) {
            throw new IllegalArgumentException("active lexical owner slots are for aggregate pads");
        }
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("active lexical slot has pending legacy releases");
        }
        transferWithoutRuntimeRoot(target, true);
    }

    /** Record one strong lexical value retained while its interpreter frame is suspended. */
    public synchronized void acquireSuspendedFrameIdentity(RuntimeBase target) {
        if (kind != Kind.SUSPENDED_FRAME) {
            throw new IllegalStateException("suspended-frame acquisition requires a suspended-frame slot");
        }
        Objects.requireNonNull(target, "target");
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot own a destroyed suspended-frame value");
        }
        if (legacyCaptureCount != 0) {
            throw new IllegalStateException("suspended-frame slot has pending legacy releases");
        }
        transferWithoutRuntimeRoot(target, true);
    }

    /** Keep a suspended lexical owner synchronized with its mutable scalar cell. */
    synchronized void updateSuspendedFrameIdentity(RuntimeBase target) {
        if (kind != Kind.SUSPENDED_FRAME) {
            throw new IllegalStateException("suspended-frame update requires a suspended-frame slot");
        }
        if (target != null && target.refCount == Integer.MIN_VALUE) target = null;
        transferWithoutRuntimeRoot(target, target != null);
    }

    /** Record a strong edge without promoting an untracked referent's legacy count. */
    synchronized void acquireUntrackedIdentity(RuntimeBase target) {
        if (kind != Kind.UNTRACKED_REFERENCE) {
            throw new IllegalStateException("untracked acquisition requires an untracked-reference slot");
        }
        Objects.requireNonNull(target, "target");
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot own a destroyed referent");
        }
        if (nativeOwner || legacyCaptureCount != 0) {
            throw new IllegalStateException("untracked identity cannot replace native or capture ownership");
        }
        transferWithoutRuntimeRoot(target, false);
    }

    /** Attach one scalar wrapper's identity to a referenced cell's contents. */
    synchronized void acquireScalarReferenceContents(
            RuntimeBase target, RuntimeScalar referenceCell, RuntimeScalar owner) {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference acquisition requires a scalar-contents slot");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(referenceCell, "referenceCell");
        Objects.requireNonNull(owner, "owner");
        if (target.refCount < 0) {
            throw new IllegalStateException("scalar-reference contents must be tracked");
        }
        if (referent != null) {
            if (referent == target && scalarReferenceCell() == referenceCell
                    && scalarContentsOwner() == owner) return;
            throw new IllegalStateException("active scalar-contents owner cannot be reacquired");
        }

        boolean firstOwnerForCell = !referenceCell.hasScalarReferenceContentsOwner(this, target);
        if (firstOwnerForCell && target.refCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("scalar-reference contents count overflow");
        }
        scalarReferenceCell = new WeakReference<>(referenceCell);
        scalarContentsOwner = new WeakReference<>(owner);
        referenceCell.registerScalarReferenceContentsOwner(this);
        transferWithoutRuntimeRoot(target, false);
        if (firstOwnerForCell) {
            target.traceRefCount(+1, "PerlOwnerSlot.acquireScalarReferenceContents");
            target.refCount++;
            target.hadCountedReference = true;
            target.acquireTransientTraceOwner(
                    "scalar-reference contents", "PerlOwnerSlot.scalarReferenceContents");
        }
    }

    /** Retarget this alias when its scalar cell receives new contents. */
    synchronized void retargetScalarReferenceContents(RuntimeBase nextTarget) {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference retargeting requires a scalar-contents slot");
        }
        RuntimeBase previous = referent;
        if (previous == nextTarget) return;
        RuntimeScalar referenceCell = scalarReferenceCell();
        if (referenceCell == null) {
            throw new IllegalStateException("active scalar-contents owner lost its reference cell");
        }
        if (nextTarget != null && nextTarget.refCount < 0) nextTarget = null;
        boolean firstOwnerForNextTarget = nextTarget != null
                && !referenceCell.hasScalarReferenceContentsOwner(this, nextTarget);
        if (firstOwnerForNextTarget && nextTarget.refCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("scalar-reference contents count overflow");
        }
        if (firstOwnerForNextTarget) {
            nextTarget.traceRefCount(+1, "PerlOwnerSlot.retargetScalarReferenceContents");
            nextTarget.refCount++;
            nextTarget.hadCountedReference = true;
            nextTarget.acquireTransientTraceOwner(
                    "scalar-reference contents", "PerlOwnerSlot.scalarReferenceContents");
        }

        transferWithoutRuntimeRoot(nextTarget, false);
        if (nextTarget == null) {
            referenceCell.unregisterScalarReferenceContentsOwner(this);
            scalarReferenceCell = null;
            scalarContentsOwner = null;
        }
        if (previous != null
                && !referenceCell.hasScalarReferenceContentsOwner(this, previous)) {
            releaseScalarReferenceContentsCount(previous,
                    "PerlOwnerSlot.retargetScalarReferenceContents");
        }
    }

    /** Release this alias and decrement the shared contents count only once. */
    synchronized void releaseScalarReferenceContents() {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference release requires a scalar-contents slot");
        }
        RuntimeBase target = referent;
        RuntimeScalar referenceCell = scalarReferenceCell();
        if (referenceCell != null) referenceCell.unregisterScalarReferenceContentsOwner(this);
        transferWithoutRuntimeRoot(null, false);
        scalarReferenceCell = null;
        scalarContentsOwner = null;
        if (target != null && (referenceCell == null
                || !referenceCell.hasScalarReferenceContentsOwner(this, target))) {
            releaseScalarReferenceContentsCount(target,
                    "PerlOwnerSlot.releaseScalarReferenceContents");
        }
    }

    /** Defer the last alias's shared contents decrement with its slot identity. */
    synchronized PendingRelease deferScalarReferenceContentsRelease() {
        if (kind != Kind.SCALAR_REFERENCE_CONTENTS) {
            throw new IllegalStateException(
                    "scalar-reference release requires a scalar-contents slot");
        }
        RuntimeBase target = referent;
        RuntimeScalar referenceCell = scalarReferenceCell();
        if (referenceCell != null) referenceCell.unregisterScalarReferenceContentsOwner(this);
        boolean finalCellOwner = target != null && (referenceCell == null
                || !referenceCell.hasScalarReferenceContentsOwner(this, target));
        transferWithoutRuntimeRoot(null, false);
        scalarReferenceCell = null;
        scalarContentsOwner = null;
        if (!finalCellOwner || target == null) return null;
        if (target.refCount <= 0) {
            target.releaseTransientTraceOwner(
                    "scalar-reference contents", "PerlOwnerSlot.deferScalarReferenceContentsRelease");
            return null;
        }

        PendingRelease release = new PendingRelease(
                identity, ++nextReleaseSequence, kind);
        MortalList.deferOwnerSlotDecrement(target, "scalar-reference contents", release);
        return release;
    }

    private static void releaseScalarReferenceContentsCount(RuntimeBase target, String site) {
        target.releaseTransientTraceOwner(
                "scalar-reference contents", "PerlOwnerSlot.scalarReferenceContents");
        if (target.refCount <= 0) return;
        target.traceRefCount(-1, site);
        if (--target.refCount == 0) {
            target.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroyFromCountedOwner(target);
        }
    }

    /** Identify one temporary compatibility count installed for weak tracking. */
    synchronized void acquireWeaklyTrackedCompatibilityPin(RuntimeBase target) {
        if (kind != Kind.WEAKLY_TRACKED_COMPATIBILITY_PIN) {
            throw new IllegalStateException("compatibility pin requires its dedicated owner-slot kind");
        }
        Objects.requireNonNull(target, "target");
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot pin a destroyed referent");
        }
        if (referent != null || nativeOwner || legacyCaptureCount != 0) {
            throw new IllegalStateException("compatibility pin is already active");
        }
        transferWithoutRuntimeRoot(target, false);
    }

    /** Record one direct value retained by a runtime temporary root. */
    synchronized void acquireTemporaryRootIdentity(RuntimeBase target) {
        if (kind != Kind.TEMPORARY_ROOT) {
            throw new IllegalStateException("temporary-root acquisition requires a temporary-root slot");
        }
        Objects.requireNonNull(target, "target");
        if (referent != null || nativeOwner || legacyCaptureCount != 0) {
            throw new IllegalStateException("temporary-root slot is already active");
        }
        if (target.refCount == Integer.MIN_VALUE) {
            throw new IllegalStateException("cannot retain a destroyed temporary root");
        }
        transferWithoutRuntimeRoot(target, false);
    }

    private void transferWithoutRuntimeRoot(RuntimeBase target, boolean nextNativeOwner) {
        if (referent == target) {
            if (ownerRuntimeState != null) {
                PerlRuntime.releaseOwnerSlotReferent(ownerRuntimeState, referent);
            }
            ownerRuntime = null;
            ownerRuntimeState = null;
            nativeOwner = nextNativeOwner;
            return;
        }
        RuntimeBase previous = referent;
        LifecycleRuntimeState previousState = ownerRuntimeState;
        referent = target;
        ownerRuntime = null;
        ownerRuntimeState = null;
        nativeOwner = nextNativeOwner;
        if (previous != null && registeredInOwnerLedger) previous.removeOwnerSlot(this);
        if (previous != null && registeredInOwnerLedger) {
            PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
        }
        registeredInOwnerLedger = target != null;
        if (registeredInOwnerLedger) target.addOwnerSlot(this);
        finishDeferredCodeRelease(previous);
        finishDeferredScalarCellRelease(previous);
        WeakRefRegistry.onStrongOwnerSlotReleased(previous);
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
        finishDeferredScalarCellRelease(previous);
        WeakRefRegistry.onStrongOwnerSlotReleased(previous);
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
        finishDeferredCodeRelease(previous);
        finishDeferredScalarCellRelease(previous);
        WeakRefRegistry.onStrongOwnerSlotReleased(previous);
    }

    /** Release this slot. Repeated releases are harmless. */
    public synchronized void release() {
        if (kind == Kind.SCALAR_REFERENCE_CONTENTS) {
            releaseScalarReferenceContents();
            return;
        }
        RuntimeBase previous = referent;
        LifecycleRuntimeState previousState = ownerRuntimeState;
        referent = null;
        countedScalarOwner = null;
        captureOwner = null;
        scalarReferenceCell = null;
        scalarContentsOwner = null;
        suspendedScalarOwner = null;
        ownerRuntime = null;
        ownerRuntimeState = null;
        if (previous != null && registeredInOwnerLedger) previous.removeOwnerSlot(this);
        if (previous != null && registeredInOwnerLedger) {
            PerlRuntime.releaseOwnerSlotReferent(previousState, previous);
        }
        registeredInOwnerLedger = false;
        finishDeferredCodeRelease(previous);
        finishDeferredScalarCellRelease(previous);
        WeakRefRegistry.onStrongOwnerSlotReleased(previous);
    }

    private static void finishDeferredCodeRelease(RuntimeBase previous) {
        if (previous instanceof RuntimeCode code) {
            code.finishActiveCodeOwnerRelease();
        }
    }

    /** Retry escaped scalar-cell cleanup after its final Perl owner leaves. */
    static void finishDeferredScalarCellRelease(RuntimeBase previous) {
        if (!(previous instanceof RuntimeScalar scalar)
                || !scalar.referencedByScalarReference
                || scalar.perlLifecycleState() == RuntimeBase.PerlLifecycleState.DESTROYED
                || scalar.refCount > 0
                || scalar.captureCount != 0
                || scalar.localBindingExists
                || MyVarCleanupStack.isRegistered(scalar)
                || scalar.hasScalarCellOwner()
                || PerlRuntime.currentOrNull() == null) {
            return;
        }
        RuntimeScalar.scopeExitCleanup(scalar);
    }


}

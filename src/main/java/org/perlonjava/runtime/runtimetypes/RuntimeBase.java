package org.perlonjava.runtime.runtimetypes;

import java.util.Iterator;
import java.util.Objects;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * The RuntimeBase class serves as an abstract base class for scalar, hash,
 * and array variables in the runtime environment. It provides common functionality
 * and interfaces for these entities.
 */
public abstract class RuntimeBase implements DynamicState, Iterable<RuntimeScalar> {
    /** Lifecycle is independent of the selective-count compatibility bridge. */
    public enum PerlLifecycleState {
        LIVE,
        DESTROYING,
        RESURRECTED,
        DESTROYED
    }

    private volatile PerlLifecycleState perlLifecycleState = PerlLifecycleState.LIVE;

    public PerlLifecycleState perlLifecycleState() {
        return perlLifecycleState;
    }

    void beginPerlDestruction() {
        perlLifecycleState = PerlLifecycleState.DESTROYING;
    }

    void markPerlResurrected() {
        perlLifecycleState = PerlLifecycleState.RESURRECTED;
    }

    void markPerlDestroyed() {
        perlLifecycleState = PerlLifecycleState.DESTROYED;
    }

    /** Storage identity retained across ithread graph clones. */
    public volatile boolean threadShared;
    /**
     * Synchronization identity for {@code threads::shared}. Plain shared
     * containers are retained by identity, while tied variables are cloned as
     * runtime-local callback views and keep this token in common.
     */
    public volatile Object threadSharedIdentity;
    /** Canonical class name published with shared aggregate storage. */
    public volatile String threadSharedBlessName;
    /**
     * Cross-runtime lifetime shared by a canonical threads::shared aggregate
     * and the runtime-local proxy views fetched from aggregate slots.
     */
    public volatile SharedLifecycle threadSharedLifecycle;
    /** True only for a fetch-time proxy wrapper, never for canonical storage. */
    public boolean threadSharedFetchedView;
    /** True for one aggregate view cloned into another ithread runtime. */
    public boolean threadSharedRuntimeView;
    /** True after this wrapper has registered one live view with the lifecycle. */
    private boolean threadSharedViewRegistered;

    public static final class SharedLifecycle {
        final java.util.concurrent.atomic.AtomicInteger liveViews =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean destroyPending =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean destroyFired =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean active;
        volatile String publishedBlessName;
    }

    enum SharedDestroyDisposition {
        CONTINUE,
        DEFER,
        CLEAR_LOCAL_VIEW
    }

    /**
     * Refresh an unchanged local shared view after another runtime explicitly
     * publishes a class through shared scalar/aggregate storage. A view that
     * has been reblessed locally keeps that local override until it is stored.
     */
    public void synchronizePublishedSharedBlessing() {
        SharedLifecycle lifecycle = threadSharedLifecycle;
        if (!threadShared || lifecycle == null) return;
        String published = lifecycle.publishedBlessName;

        // Canonical shared storage is one Java object across ithreads, while
        // bless IDs belong to a particular PerlRuntime. Re-intern the shared
        // class name in the runtime that is currently observing the value.
        if (!threadSharedFetchedView && !threadSharedRuntimeView) {
            threadSharedBlessName = published;
            blessId = published == null ? 0 : NameNormalizer.getBlessId(published);
            return;
        }

        // A runtime-local proxy can be privately reblessed. Refresh only a
        // view whose local class still matches its last published class.
        String localClass = blessId == 0 ? null : NameNormalizer.getBlessStr(blessId);
        if (!Objects.equals(localClass, threadSharedBlessName)) return;
        threadSharedBlessName = published;
        blessId = published == null ? 0 : NameNormalizer.getBlessId(published);
    }

    /** True while shared canonical storage has not published an initial class. */
    public boolean sharedBlessingUnpublished() {
        return threadShared && threadSharedLifecycle != null
                && threadSharedLifecycle.publishedBlessName == null;
    }

    void registerSharedFetchedView() {
        SharedLifecycle lifecycle = threadSharedLifecycle;
        if ((!threadSharedFetchedView && !threadSharedRuntimeView)
                || lifecycle == null || threadSharedViewRegistered) return;
        synchronized (this) {
            if (!threadSharedViewRegistered) {
                lifecycle.active = true;
                lifecycle.liveViews.incrementAndGet();
                threadSharedViewRegistered = true;
            }
        }
    }

    /** Decide whether this release owns canonical destruction or only a local view. */
    SharedDestroyDisposition sharedDestroyDisposition() {
        SharedLifecycle lifecycle = threadSharedLifecycle;
        if (!threadShared || lifecycle == null || !lifecycle.active) {
            return SharedDestroyDisposition.CONTINUE;
        }

        if (threadSharedFetchedView || threadSharedRuntimeView) {
            int remaining = lifecycle.liveViews.get();
            synchronized (this) {
                if (threadSharedViewRegistered) {
                    threadSharedViewRegistered = false;
                    remaining = lifecycle.liveViews.decrementAndGet();
                }
            }
            if (remaining == 0 && lifecycle.destroyPending.get()
                    && lifecycle.destroyFired.compareAndSet(false, true)) {
                return SharedDestroyDisposition.CONTINUE;
            }
            // A fetch proxy has a Perl-local identity even though its storage
            // is shared. Retire only its local weak observers; the canonical
            // object's DESTROY and element cleanup still belong to the
            // shared lifecycle. Runtime views retain the existing deferred
            // destruction behavior until their lifecycle is separated too.
            return threadSharedFetchedView
                    ? SharedDestroyDisposition.CLEAR_LOCAL_VIEW
                    : SharedDestroyDisposition.DEFER;
        }

        lifecycle.destroyPending.set(true);
        if (lifecycle.liveViews.get() > 0) return SharedDestroyDisposition.DEFER;
        return lifecycle.destroyFired.compareAndSet(false, true)
                ? SharedDestroyDisposition.CONTINUE
                : SharedDestroyDisposition.DEFER;
    }
    // Index to the class that this reference belongs
    public int blessId;

    // Reference count for blessed objects with DESTROY.
    // Four-state lifecycle counter:
    //   -1                = Not tracked (unblessed, or blessed without DESTROY)
    //    0                = Tracked, zero counted containers (fresh from bless)
    //   >0                = Being tracked; N named-variable containers exist
    //   Integer.MIN_VALUE = DESTROY already called (or in progress)
    // MUST be explicitly initialized to -1 (Java defaults int to 0, which would
    // mean "tracked, zero containers" — silently breaking all unblessed objects).
    public int refCount = -1;

    // ─────────────────────────────────────────────────────────────────────
    // Phase D-W6.10: targeted refCount transition tracing.
    // Activate at bless time for classes matching `classNeedsWalkerGate`
    // when env-flag PJ_REFCOUNT_TRACE is set. Writes a stderr line for
    // every increment/decrement, including a stack snippet, so we can
    // pinpoint which path causes the metaclass refCount underflow on
    // Class::MOP bootstrap (see dev/modules/moose_support.md D-W6.7).
    // ─────────────────────────────────────────────────────────────────────
    public boolean refCountTrace = false;

    // Temporary aliases installed by foreach loop variables such as $_.
    // They are visible to B::SV::REFCNT but are not persistent container owners.
    public int foreachAliasCount = 0;

    /**
     * D-W6.18: marks objects whose lifetime is "module-global metadata":
     * stored as the value of a package-global hash element (`$Foo::META{x}
     * = $meta`). Such objects' selective refCount may transient-zero
     * during the program run (mid-statement decrements before paired
     * increments fire), but they should NOT be destroyed because their
     * "real" lifetime is the duration of the package's existence.
     * <p>
     * Set in {@code RuntimeScalar.setLargeRefCounted} when the
     * destination scalar is detected to be an element of a hash that
     * appears in {@code GlobalVariable.globalHashes}. Once set, never
     * cleared (the object remains a module-global anchor for the
     * program's lifetime).
     * <p>
     * Replaces the class-name heuristic in
     * {@code DestroyDispatch.classNeedsWalkerGate}: instead of
     * approximating "module-global metadata" by hard-coded class names
     * (Class::MOP, Moose, Moo), we capture the underlying property
     * directly at store time.
     */
    public boolean storedInPackageGlobal = false;

    /** Marks a counted referent kept alive only until its uncounted owner slots leave. */
    boolean deferredFinalReleaseForUncountedOwners = false;

    /**
     * Number of live hash-element slots that directly own this referent.
     * This is separate from selective refCount because a returned method
     * value can be detached from its slot while the hash remains an owner.
     */
    public int hashSlotOwnerCount = 0;


    /**
     * Marks a scalar/container whose contents are reachable from package
     * globals. Mutating such an object can change cached root-reachability
     * answers, so mutation paths invalidate MortalList's external-root
     * snapshot when this flag is set.
     */
    public boolean isPackageGlobalRoot = false;

    /**
     * An owned, non-empty aggregate stored in this object's hash was explicitly
     * replaced with an empty aggregate. Callback APIs use this operation to break
     * closure cycles before returning. Once observed, nested Perl call depth
     * must not by itself keep a weakened probe alive; real roots still do.
     */
    public boolean clearedOwnedAggregateElement = false;

    /**
     * Number of closures that capture this aggregate lexical directly
     * (currently RuntimeArray / RuntimeHash pads). While positive, scope-exit
     * cleanup of the aggregate's contents is deferred until the last closure
     * releases it.
     */
    public int captureCount = 0;
    public boolean scopeExited = false;
    private int legacyCaptureOwnerCount;
    private int pendingClosureCaptureReleases;

    /**
     * A lexical array whose scope has ended without an escaped {@code \@array}
     * reference.  A reference to its {@code $#array} lvalue must not keep the
     * array alive: Perl turns that lvalue into an orphaned undef scalar.
     */
    public boolean arrayLengthLvalueOrphaned = false;

    public synchronized void retainClosureCapture() {
        PerlOwnerSlot.Kind ownerKind = this instanceof RuntimeScalar
                ? PerlOwnerSlot.Kind.SCALAR_CAPTURE : PerlOwnerSlot.Kind.CLOSURE_PAD;
        verifyClosureCaptureOwnerCount(ownerKind);
        retainClosureCaptureCount(true);
        verifyClosureCaptureOwnerCount(ownerKind);
    }

    public synchronized int legacyCaptureOwnerCount() {
        return legacyCaptureOwnerCount;
    }

    final synchronized void beginPendingClosureCaptureRelease() {
        if (pendingClosureCaptureReleases == Integer.MAX_VALUE) {
            throw new IllegalStateException("pending closure capture release overflow");
        }
        pendingClosureCaptureReleases++;
    }

    final synchronized void endPendingClosureCaptureRelease() {
        if (pendingClosureCaptureReleases <= 0) {
            throw new IllegalStateException("unbalanced pending closure capture release");
        }
        pendingClosureCaptureReleases--;
    }

    protected final synchronized void retainClosureCaptureCount(boolean legacyOwner) {
        if (captureCount == Integer.MAX_VALUE
                || (legacyOwner && legacyCaptureOwnerCount == Integer.MAX_VALUE)) {
            throw new IllegalStateException("closure capture count overflow");
        }
        captureCount++;
        if (legacyOwner) legacyCaptureOwnerCount++;
    }

    protected final synchronized void releaseClosureCaptureCount(boolean legacyOwner) {
        if (legacyOwner) {
            if (legacyCaptureOwnerCount <= 0) {
                throw new IllegalStateException("release has no legacy closure capture owner");
            }
            legacyCaptureOwnerCount--;
        }
        if (captureCount <= 0) {
            throw new IllegalStateException("closure capture count underflow");
        }
        captureCount--;
    }

    final synchronized void verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind ownerKind) {
        if (pendingClosureCaptureReleases != 0) {
            throw new IllegalStateException("closure capture release is still pending");
        }
        int slotCount = ownerSlotCount(ownerKind);
        int legacyCount = legacyCaptureOwnerCount();
        if (captureCount != slotCount + legacyCount) {
            throw new IllegalStateException("closure capture owner mismatch: count="
                    + captureCount + ", slots=" + slotCount + ", legacy=" + legacyCount
                    + ", kind=" + ownerKind);
        }
    }

    /** Whether an explicit, compatibility, or in-flight capture owner remains. */
    public final synchronized boolean hasClosureCaptureOwner() {
        PerlOwnerSlot.Kind ownerKind = this instanceof RuntimeScalar
                ? PerlOwnerSlot.Kind.SCALAR_CAPTURE : PerlOwnerSlot.Kind.CLOSURE_PAD;
        return ownerSlotCount(ownerKind) != 0 || legacyCaptureOwnerCount() != 0
                || pendingClosureCaptureReleases != 0;
    }

    /** Retain one aggregate pad capture through an explicit owner slot. */
    public synchronized PerlOwnerSlot retainClosureCaptureOwner() {
        return retainClosureCaptureOwner(null);
    }

    /** Retain one aggregate pad capture and identify its owning CODE object. */
    public synchronized PerlOwnerSlot retainClosureCaptureOwner(RuntimeCode captureOwner) {
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.CLOSURE_PAD);
        // A closure-pad slot is a semantic Perl owner even though it does not
        // contribute to the legacy selective refcount. A targeted weak sweep
        // can therefore leave an unblessed aggregate's legacy count at the
        // destroyed sentinel while other native capture slots still own it.
        // Reconcile that stale bridge state before adding another owner; a
        // blessed or already-destroyed object must never be revived here.
        if (refCount == Integer.MIN_VALUE && blessId == 0 && !destroyFired
                && hasClosureCaptureOwner() && hasSemanticCaptureOwner()) {
            refCount = 0;
        }
        PerlOwnerSlot ownerSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CLOSURE_PAD);
        ownerSlot.acquireCapture(this, captureOwner);
        retainClosureCaptureCount(false);
        if (this instanceof RuntimeArray || this instanceof RuntimeHash) {
            verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.CLOSURE_PAD);
        }
        return ownerSlot;
    }

    /** Release one aggregate pad capture, balancing its bridged count. */
    public synchronized void releaseClosureCaptureOwner(PerlOwnerSlot ownerSlot) {
        if (ownerSlot == null) {
            releaseClosureCapture();
            return;
        }
        if (ownerSlot.kind() != PerlOwnerSlot.Kind.CLOSURE_PAD) {
            throw new IllegalStateException("capture release requires a closure-pad slot");
        }
        if (!ownerSlot.isActive()) return;
        if (ownerSlot.referent() != this) {
            throw new IllegalStateException("closure-pad slot belongs to a different aggregate");
        }
        if (ownerSlot.isNativeCaptureOwner()) {
            // Captured aggregate pads are native owner slots. Remove the slot
            // before releasing the capture so a final release can dispatch
            // from the owner count without manufacturing a legacy decrement.
            beginPendingClosureCaptureRelease();
            try {
                ownerSlot.release();
            } finally {
                endPendingClosureCaptureRelease();
            }
            releaseClosureCapture(false);
            // The owner-slot removal above runs while this capture release is
            // marked pending, so its lifecycle callback correctly waits. Retry
            // now that the capture count and pending marker have both retired.
            WeakRefRegistry.onStrongOwnerSlotReleased(this);
            verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.CLOSURE_PAD);
            return;
        }
        ownerSlot.deferLegacyCaptureRelease(this);
        try {
            releaseClosureCapture(false);
        } finally {
            ownerSlot.release();
        }
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.CLOSURE_PAD);
    }

    /** Retain one persistent scalar or aggregate state binding owned by a CV. */
    synchronized PerlOwnerSlot retainCodeStateOwner(RuntimeCode owner) {
        PerlOwnerSlot ownerSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.CODE_STATE);
        ownerSlot.acquireCodeStateIdentity(this, owner);
        return ownerSlot;
    }

    /** Release one persistent CODE state binding identity. */
    synchronized void releaseCodeStateOwner(PerlOwnerSlot ownerSlot) {
        if (ownerSlot == null || !ownerSlot.isActive()) return;
        if (ownerSlot.kind() != PerlOwnerSlot.Kind.CODE_STATE || ownerSlot.referent() != this) {
            throw new IllegalArgumentException("CODE state release does not match this referent");
        }
        ownerSlot.release();
    }

    public synchronized void releaseClosureCapture() {
        if (!hasClosureCaptureOwner()) {
            if (captureCount != 0) {
                throw new IllegalStateException("closure capture has no recorded owner");
            }
            return;
        }
        releaseClosureCapture(true);
        verifyClosureCaptureOwnerCount(PerlOwnerSlot.Kind.CLOSURE_PAD);
    }

    private void releaseClosureCapture(boolean legacyOwner) {
        releaseClosureCaptureCount(legacyOwner);
        if (!hasClosureCaptureOwner() && scopeExited) {
            scopeExited = false;
            if (this instanceof RuntimeHash hash) {
                MortalList.scopeExitCleanupHash(hash);
            } else if (this instanceof RuntimeArray array) {
                MortalList.scopeExitCleanupArray(array);
            }
            // A closure pad capture is not a counted RV edge. Any positive
            // count belongs to scalar or container slots, which retire their
            // own count when those slots are released.
            if (blessId != 0 && refCount <= 0) {
                refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(this);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // D-W6.13: counted scalar owners are derived from the slot ledger below.
    // Keep the legacy activation method as a no-op for callers that still use it.
    // ─────────────────────────────────────────────────────────────────────
    /** Identity ledger for migrated slots; selective refCount remains the
     * lifecycle bridge while owner families move to this API. */
    private java.util.Set<PerlOwnerSlot> ownerSlots = null;
    private int argumentAliasOwnerSlots;
    private int countedOwnerSlots;
    /** Active semantic Perl owner identities; excludes temporary roots and IO leases. */
    private volatile int perlStrongOwnerSlots;
    private int pendingReturnOwners;
    private int pendingOwnerReleases;

    synchronized boolean hasPendingReturnOwner() {
        return pendingReturnOwners != 0;
    }

    /** A queued Perl owner release has retired its live slot but not its count yet. */
    synchronized boolean hasPendingOwnerRelease() {
        return pendingOwnerReleases != 0;
    }

    /** Explicit call-transfer owners remain authoritative during the legacy bridge. */
    synchronized boolean hasCallTransferOwner() {
        return pendingReturnOwners != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE) != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_CONTAINER) != 0;
    }

    /** A referenced scalar cell is owned by its lexical binding or capture slots. */
    boolean hasScalarCellOwner() {
        return this instanceof RuntimeScalar scalar && scalar.referencedByScalarReference
                && (scalar.localBindingExists
                    || hasOwnerSlots() || hasPendingReturnOwner());
    }

    /** Storage identities that own a scalar cell itself, excluding its value edges. */
    boolean hasScalarCellStorageOwnerSlotIdentity() {
        return ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL) != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT) != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE) != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL) != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME) != 0
                || ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS) != 0;
    }

    private synchronized void registerPendingOwnerRelease(
            PerlOwnerSlot.PendingRelease release) {
        pendingOwnerReleases++;
        if (release != null && release.kind() == PerlOwnerSlot.Kind.RETURN_VALUE) {
            pendingReturnOwners++;
        }
    }

    synchronized void addOwnerSlot(PerlOwnerSlot slot) {
        if (ownerSlots == null) {
            ownerSlots = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());
        }
        if (ownerSlots.add(slot)) {
            if (slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_ALIAS) argumentAliasOwnerSlots++;
            if (PerlOwnerSlot.isCountedKind(slot.kind())) countedOwnerSlots++;
            if (PerlOwnerSlot.isPerlOwnerKind(slot.kind())) perlStrongOwnerSlots++;
        }
    }

    synchronized void removeOwnerSlot(PerlOwnerSlot slot) {
        if (ownerSlots != null && ownerSlots.remove(slot)) {
            if (slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_ALIAS) argumentAliasOwnerSlots--;
            if (PerlOwnerSlot.isCountedKind(slot.kind())) countedOwnerSlots--;
            if (PerlOwnerSlot.isPerlOwnerKind(slot.kind())) perlStrongOwnerSlots--;
        }
    }

    boolean hasOwnerSlots() {
        return perlStrongOwnerSlots > 0;
    }

    /**
     * Whether the legacy count or a direct Perl owner slot proves this value
     * is still owned. Runtime temporary roots and IO resource leases are
     * runtime bookkeeping, not Perl references, and are deliberately excluded.
     */
    public boolean hasPerlStrongOwner() {
        return refCount > 0 || hasPerlStrongOwnerIdentity();
    }

    /**
     * Count current {@code B::SV->{ref}} wrapper slots for this referent.
     * Native Perl's B objects contain an SV pointer and do not increment the
     * referent's refcount. PerlOnJava stores that pointer in a hash slot to
     * keep the Java referent available while the wrapper is in use, so
     * {@code B::SV::REFCNT} must discount overlapping wrapper slots beyond
     * the one used for the active query.
     */
    public int currentBObjectReferentOwnerCount() {
        int count = 0;
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (!slot.isActive() || slot.kind() != PerlOwnerSlot.Kind.HASH_ELEMENT) continue;
            RuntimeScalar owner = slot.countedScalarOwner();
            if (owner == null || owner.value != this
                    || !(owner.containerOwner instanceof RuntimeHash hash)) continue;
            String ownerClass = hash.blessId == 0
                    ? null : NameNormalizer.getBlessStr(hash.blessId);
            if (hash.elements.get("ref") != owner
                    || ownerClass == null || !ownerClass.startsWith("B::")) continue;
            count++;
        }
        return count;
    }

    /** Count temporary referent slots held only by active Perl argument frames. */
    public int currentArgumentFrameReferentOwnerCount() {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime == null) return 0;
        java.util.Deque<RuntimeArray> activeArgs = runtime.executionState().argsStack;
        int count = 0;
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (!slot.isActive()) continue;
            if (slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_CONTAINER) {
                count++;
                continue;
            }
            RuntimeScalar owner = slot.countedScalarOwner();
            if (owner != null && owner.value == this
                    && owner.containerOwner instanceof RuntimeArray array
                    && activeArgs.contains(array)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether current Perl owner identities, apart from the legacy count,
     * prove that this value is still owned. Temporary roots, IO resource leases,
     * and compatibility count pins are not Perl owners.
     */
    boolean hasPerlStrongOwnerIdentity() {
        if (localBindingExists || hasClosureCaptureOwner() || hasPendingReturnOwner()) {
            return true;
        }
        return perlStrongOwnerSlots > 0;
    }

    /** Whether a current Perl owner identity other than one slot remains. */
    boolean hasCurrentPerlOwnerIdentityOtherThan(PerlOwnerSlot excluded) {
        if (localBindingExists || hasClosureCaptureOwner()
                || hasPendingReturnOwner() || hasPendingOwnerRelease()) {
            return true;
        }
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot == excluded || !slot.isActive()
                    || !PerlOwnerSlot.isPerlOwnerKind(slot.kind())) {
                continue;
            }
            if (slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_ALIAS
                    || slot.isMethodInvocantOwner(this)
                    || slot.isCountedNativeOwner()) {
                return true;
            }
            RuntimeScalar scalarOwner = slot.countedScalarOwner();
            if (scalarOwner != null) {
                PerlOwnerSlot.Kind kind = slot.kind();
                boolean currentCountedOwner =
                        (kind == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                                || kind == PerlOwnerSlot.Kind.HASH_ELEMENT)
                        ? scalarOwner.isCurrentAggregateElementOwner(this, slot)
                        : scalarOwner.isCurrentCountedScalarOwner(this, slot);
                if (currentCountedOwner) return true;
                continue;
            }
            if (DestroyDispatch.isCurrentPerlOwnerSlot(this, slot)) return true;
        }
        return false;
    }

    /** Whether a non-counted owner slot proves Perl ownership of this value. */
    boolean hasUncountedPerlOwnerSlotIdentity() {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot.isActive()
                    && !PerlOwnerSlot.isCountedKind(slot.kind())
                    && PerlOwnerSlot.isPerlOwnerKind(slot.kind())) {
                return true;
            }
        }
        return false;
    }

    /** A snapshot lease delays lifecycle cleanup without becoming a Perl owner. */
    boolean hasTemporaryRootOwnerSlotIdentity() {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot.isActive() && slot.kind() == PerlOwnerSlot.Kind.TEMPORARY_ROOT) {
                return true;
            }
        }
        return false;
    }

    /** Whether a current uncounted slot proves Perl ownership of this value. */
    boolean hasCurrentUncountedPerlOwnerSlotIdentity() {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (!PerlOwnerSlot.isCountedKind(slot.kind())
                    && PerlOwnerSlot.isPerlOwnerKind(slot.kind())
                    && DestroyDispatch.isCurrentPerlOwnerSlot(this, slot)) {
                return true;
            }
        }
        return false;
    }

    boolean hasDirectOwnerSlotProofForReleasedWeakReferent() {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (!slot.isActive() || !PerlOwnerSlot.isPerlOwnerKind(slot.kind())) continue;
            if (slot.kind() == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                    || slot.kind() == PerlOwnerSlot.Kind.HASH_ELEMENT) {
                RuntimeScalar scalar = slot.countedScalarOwner();
                if (scalar != null && scalar.getContainerOwner() == null) continue;
            }
            if (slot.kind() == PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) {
                RuntimeScalar scalar = slot.untrackedScalarOwner();
                if (scalar != null && scalar.hasPackageAggregateUntrackedReferenceOwner()) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Whether a durable scalar value owns this referent outside its count.
     * Refresh each exact owner first so a stale scalar cell cannot pin a
     * referent after that cell has lost its Perl-visible slot.
     */
    boolean hasUntrackedReferenceOwnerSlotIdentity() {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot.kind() != PerlOwnerSlot.Kind.UNTRACKED_REFERENCE || !slot.isActive()) continue;
            RuntimeScalar owner = slot.untrackedScalarOwner();
            if (owner != null) {
                if (owner.hasLiveDurableStrongValueSlot()) {
                    owner.refreshUntrackedReferenceOwner();
                } else {
                    owner.releaseUntrackedReferenceOwner(this);
                }
            } else {
                slot.release();
                WeakRefRegistry.onStrongOwnerSlotReleased(this);
            }
        }
        return ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) != 0;
    }

    /** Promote existing scalar-cell owners when this referent enters counted tracking. */
    public void promoteUntrackedScalarOwners() {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot.kind() != PerlOwnerSlot.Kind.UNTRACKED_REFERENCE || !slot.isActive()) continue;
            RuntimeScalar owner = slot.untrackedScalarOwner();
            if (owner == null || WeakRefRegistry.isweak(owner)
                    || !owner.hasLiveDurableStrongValueSlot()) continue;
            RuntimeScalar.incrementRefCountForContainerStore(owner, owner.countedOwnerKind());
        }
    }

    boolean hasUntrackedReferenceOwnerSlotIdentity(int scalarType) {
        if (!hasUntrackedReferenceOwnerSlotIdentity()) return false;
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot.kind() != PerlOwnerSlot.Kind.UNTRACKED_REFERENCE || !slot.isActive()) continue;
            RuntimeScalar owner = slot.untrackedScalarOwner();
            if (owner != null && owner.type == scalarType
                    && owner.hasLiveDurableStrongValueSlot()
                    && !owner.hasPackageAggregateUntrackedReferenceOwner()) {
                return true;
            }
        }
        return false;
    }

    /** Whether an aggregate has a surviving owner outside the legacy count. */
    boolean hasUncountedAggregateOwnerSlotIdentity() {
        return (this instanceof RuntimeArray || this instanceof RuntimeHash)
                && hasUncountedPerlOwnerSlotIdentity();
    }

    boolean hasNonPackageAggregateUncountedOwnerSlotIdentity() {
        if (!(this instanceof RuntimeArray || this instanceof RuntimeHash)) return false;
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (!slot.isActive()
                    || PerlOwnerSlot.isCountedKind(slot.kind())
                    || !PerlOwnerSlot.isPerlOwnerKind(slot.kind())) {
                continue;
            }
            if (slot.kind() == PerlOwnerSlot.Kind.UNTRACKED_REFERENCE) {
                RuntimeScalar scalar = slot.untrackedScalarOwner();
                if (scalar == null && blessId == 0
                        && WeakRefRegistry.hasWeakRefsTo(this)) {
                    slot.release();
                    WeakRefRegistry.onStrongOwnerSlotReleased(this);
                    continue;
                }
                if (scalar != null && blessId == 0
                        && WeakRefRegistry.hasWeakRefsTo(this)
                        && !scalar.hasLiveDurableStrongValueSlot()) {
                    scalar.releaseUntrackedReferenceOwner(this);
                    continue;
                }
                if (scalar != null && scalar.hasPackageAggregateUntrackedReferenceOwner()) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    void deferFinalReleaseToUncountedOwnerSlots() {
        if (!hasUncountedPerlOwnerSlotIdentity()) return;
        refCount = WeakRefRegistry.WEAKLY_TRACKED;
        deferredFinalReleaseForUncountedOwners = true;
    }

    /** Defer a zero-count lifecycle transition without encoding it as a count. */
    void deferZeroCountFinalReleaseToUncountedOwnerSlots() {
        if (refCount != 0) {
            throw new IllegalStateException("zero-count owner release requires refCount=0");
        }
        if (!hasUncountedPerlOwnerSlotIdentity()) return;
        deferredFinalReleaseForUncountedOwners = true;
    }

    /** Hold weak cleanup until an explicit runtime snapshot lease is released. */
    void deferFinalReleaseToTemporaryRootSlots() {
        if (refCount != 0) {
            throw new IllegalStateException("temporary-root release requires refCount=0");
        }
        if (!hasTemporaryRootOwnerSlotIdentity()) return;
        refCount = WeakRefRegistry.WEAKLY_TRACKED;
        deferredFinalReleaseForUncountedOwners = true;
    }

    /** Check whether a live owner identity besides the slot being replaced remains. */
    boolean hasOwnerSlotsOtherThan(PerlOwnerSlot excluded) {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (slot != excluded && slot.isActive()) return true;
        }
        return false;
    }

    public boolean hasSemanticCaptureOwner() {
        return ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD) != 0;
    }

    /** Number of active slots of the requested kind that own this referent. */
    public synchronized int ownerSlotCount(PerlOwnerSlot.Kind kind) {
        if (kind == PerlOwnerSlot.Kind.ARGUMENT_ALIAS) return argumentAliasOwnerSlots;
        if (ownerSlots == null) return 0;
        int count = 0;
        for (PerlOwnerSlot slot : ownerSlots) {
            if (slot.kind() == kind) count++;
        }
        return count;
    }

    /** Number of active slot identities whose releases balance refCount. */
    synchronized int countedOwnerSlotCount() {
        return countedOwnerSlots;
    }

    /** Whether a current Perl storage identity already owns this value. */
    boolean hasIndependentPerlOwnerSlotForArgument() {
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (DestroyDispatch.isCurrentPerlOwnerSlot(this, slot)) return true;
        }
        return false;
    }

    /** Count surviving counted slots after verifying their current storage cells. */
    int verifiedCurrentCountedOwnerSlotCount() {
        int count = 0;
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (!slot.isActive()) continue;
            if (slot.isMethodInvocantOwner(this)) {
                // A method call directly owns its invocant for the frame's
                // lifetime. It has no scalar storage cell, so the slot's
                // producer identity is its current-storage proof.
                count++;
                continue;
            }
            if (slot.isCountedNativeOwner()) {
                // Native wrappers such as PerlIO::via layers, regex callbacks,
                // and tied-hash iterators pair their direct count with this slot.
                count++;
                continue;
            }
            RuntimeScalar owner = slot.countedScalarOwner();
            if (owner == null) continue;
            PerlOwnerSlot.Kind kind = slot.kind();
            if ((kind == PerlOwnerSlot.Kind.ARRAY_ELEMENT
                    || kind == PerlOwnerSlot.Kind.HASH_ELEMENT)
                    && owner.isCurrentAggregateElementOwner(this, slot)) {
                count++;
            } else if ((kind == PerlOwnerSlot.Kind.SCALAR_CELL
                    || kind == PerlOwnerSlot.Kind.CODE_SLOT
                    || kind == PerlOwnerSlot.Kind.ARGUMENT_VALUE
                    || kind == PerlOwnerSlot.Kind.ARGUMENT_CONTAINER
                    || kind == PerlOwnerSlot.Kind.RETURN_VALUE
                    || kind == PerlOwnerSlot.Kind.TIE_HANDLER
                    || kind == PerlOwnerSlot.Kind.ARGUMENT_TIE_HANDLER)
                    && owner.isCurrentCountedScalarOwner(this, slot)) {
                count++;
            }
        }
        // Some blessed referents can retain a live counted scalar-cell slot
        // before the scalar has acquired lexical, package, or aggregate
        // storage metadata. Preserve the legacy fallback's precedence: use
        // these exact standalone-cell proofs only when no ordinary verified
        // counted owner was found.
        if (count == 0 && blessId != 0) {
            for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
                if (!slot.isActive() || slot.kind() != PerlOwnerSlot.Kind.SCALAR_CELL) continue;
                RuntimeScalar owner = slot.countedScalarOwner();
                if (owner != null && owner.isUnregisteredCountedScalarCellOwner(this, slot)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Restore from verified counted identities while the compatibility bridge remains. */
    boolean restoreVerifiedCurrentCountedOwnerSlotRefCount() {
        int count = verifiedCurrentCountedOwnerSlotCount();
        if (count == 0) return false;
        refCount = count;
        return true;
    }

    /** Number of distinct captured pad slots that own this referent. */
    public int semanticCaptureOwnerCount() {
        return ownerSlotCount(PerlOwnerSlot.Kind.CLOSURE_PAD);
    }

    /** Count captured aggregate pads whose lifetime is owned natively by slots. */
    public synchronized int nativeCaptureOwnerCount() {
        if (ownerSlots == null) return 0;
        int count = 0;
        for (PerlOwnerSlot slot : ownerSlots) {
            if (slot.kind() == PerlOwnerSlot.Kind.CLOSURE_PAD
                    && slot.isNativeCaptureOwner()) {
                count++;
            }
        }
        return count;
    }

    /** Count all slots whose owners are represented directly by the owner ledger. */
    public synchronized int nativeOwnerSlotCount() {
        if (ownerSlots == null) return 0;
        int count = 0;
        for (PerlOwnerSlot slot : ownerSlots) {
            if (slot.isNativeOwner()) count++;
        }
        return count;
    }

    // Conservative gate for the tied-handler reachability fallback. Once a
    // base has appeared in a tie handler's strong object graph it remains
    // marked; a stale true only permits the exact walker to run, while false
    // means the expensive tied-root query cannot rescue this object.
    boolean possiblyStoredInTiedHandler;

    void markPossiblyStoredInTiedHandler() {
        java.util.Set<RuntimeBase> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<RuntimeBase> todo = new java.util.ArrayDeque<>();
        todo.add(this);
        while (!todo.isEmpty()) {
            RuntimeBase current = todo.removeFirst();
            if (!seen.add(current)) continue;
            current.possiblyStoredInTiedHandler = true;
            if (current instanceof RuntimeHash hash) {
                if (hash.elements instanceof TieHash tieHash) {
                    addTiedHandlerReferent(tieHash.getSelf(), todo);
                }
                for (RuntimeScalar value : hash.elements.values()) {
                    addTiedHandlerReferent(value, todo);
                }
            } else if (current instanceof RuntimeArray array) {
                for (RuntimeScalar value : array.elements) {
                    addTiedHandlerReferent(value, todo);
                }
            } else if (current instanceof RuntimeScalar scalar) {
                addTiedHandlerReferent(scalar, todo);
            }
        }
    }

    private static void addTiedHandlerReferent(
            RuntimeScalar scalar, java.util.ArrayDeque<RuntimeBase> todo) {
        if (scalar != null
                && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base) {
            todo.addLast(base);
        }
    }

    /** Compatibility no-op: counted owner identities are recorded eagerly. */
    public void activateOwnerTracking() {
    }

    public void recordActiveOwner(RuntimeScalar scalar) {
        scalar.recordCountedOwner(this);
    }

    public void releaseActiveOwner(RuntimeScalar scalar) {
        retireCountedOwnerIdentity(scalar, "RuntimeBase.releaseActiveOwner");
    }

    /**
     * Retire a stale scalar owner identity after a nested-call guard preserved
     * the referent at selective count zero. The count was already consumed by
     * the guard, so this transition clears only the scalar's mirrored ownership
     * flag, trace entry, and exact Perl owner slot.
     */
    final void retireZeroCountOwnerIdentity(RuntimeScalar scalar, String releaseSite) {
        Objects.requireNonNull(scalar, "scalar");
        Objects.requireNonNull(releaseSite, "releaseSite");
        if (refCount != 0) {
            throw new IllegalStateException(
                    "zero-count owner identity retirement requires refCount=0 at " + releaseSite);
        }
        retireCountedOwnerIdentity(scalar, releaseSite);
    }

    private void retireCountedOwnerIdentity(RuntimeScalar scalar, String releaseSite) {
        if (scalar.releaseCountedOwnerIdentity(this, releaseSite, true) == null) {
            throw new IllegalStateException(
                    "counted owner identity retirement requires its active PerlOwnerSlot at "
                            + releaseSite);
        }
    }

    public int activeOwnerCount() {
        return activeOwnerSnapshot().size();
    }

    /** Snapshot current counted scalar owners by their authoritative slot identities. */
    public java.util.Set<RuntimeScalar> activeOwnerSnapshot() {
        java.util.Set<RuntimeScalar> snapshot = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (PerlOwnerSlot slot : ownerSlotSnapshot()) {
            if (!slot.isActive() || slot.referent() != this
                    || !PerlOwnerSlot.isCountedKind(slot.kind())) {
                continue;
            }
            RuntimeScalar scalar = slot.countedScalarOwner();
            if (scalar != null && scalar.countedOwnerSlot() == slot
                    && scalar.refCountOwned && scalar.value == this) {
                snapshot.add(scalar);
            }
        }
        return snapshot;
    }

    /** Snapshot semantic owner-slot identities, including closure pads. */
    public synchronized java.util.Set<PerlOwnerSlot> ownerSlotSnapshot() {
        if (ownerSlots == null || ownerSlots.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<PerlOwnerSlot> snapshot = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        snapshot.addAll(ownerSlots);
        return snapshot;
    }

    private synchronized java.util.List<PerlOwnerSlot> closureCaptureOwnerSnapshot() {
        if (ownerSlots == null || ownerSlots.isEmpty()) return java.util.List.of();
        java.util.ArrayList<PerlOwnerSlot> snapshot = new java.util.ArrayList<>();
        for (PerlOwnerSlot slot : ownerSlots) {
            PerlOwnerSlot.Kind kind = slot.kind();
            if (kind == PerlOwnerSlot.Kind.SCALAR_CAPTURE
                    || kind == PerlOwnerSlot.Kind.CLOSURE_PAD) {
                snapshot.add(slot);
            }
        }
        return snapshot.isEmpty() ? java.util.List.of() : snapshot;
    }

    /** Release closure pads whose CODE owners have no surviving owner identity. */
    void releaseUnownedClosureCaptures() {
        for (PerlOwnerSlot ownerSlot : closureCaptureOwnerSnapshot()) {
            if (!ownerSlot.isActive()) continue;
            RuntimeCode captureOwner = ownerSlot.captureOwner();
            if (captureOwner != null
                    && !captureOwner.hasPerlOwnerForCaptureOtherThan(null)) {
                captureOwner.releaseCaptures();
            }
        }
    }

    /**
     * Count live counted scalar-cell slots reachable from package globals or
     * live my-vars. This root-walk compatibility fallback remains only for
     * slots whose current storage cannot yet be proven from owner identities.
     */
    public int reachableOwnerCount() {
        int count = 0;
        for (RuntimeScalar scalar : activeOwnerSnapshot()) {
            if (ReachabilityWalker.isScalarReachable(scalar)) {
                count++;
            }
        }
        return count;
    }

    private static final boolean REFCOUNT_TRACE_ENV =
            System.getenv("PJ_REFCOUNT_TRACE") != null;

    static {
        if (REFCOUNT_TRACE_ENV) {
            Runtime.getRuntime().addShutdownHook(new Thread(RuntimeBase::dumpTraceOwners));
        }
    }

    public static boolean refCountTraceEnabled() {
        return REFCOUNT_TRACE_ENV;
    }

    public void traceRefCount(int delta, String reason) {
        if (!refCountTrace || !REFCOUNT_TRACE_ENV) return;
        int after = this.refCount + delta;
        StringBuilder sb = new StringBuilder();
        sb.append("[REFCOUNT] base=").append(System.identityHashCode(this))
          .append(" (").append(this.getClass().getSimpleName()).append(")")
          .append(" blessId=").append(this.blessId)
          .append(" ").append(this.refCount).append(" -> ").append(after)
          .append("   ").append(reason);
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 1; i < Math.min(st.length, 6); i++) {
            sb.append("\n      at ").append(st[i]);
        }
        System.err.println(sb);
    }

    /** Apply the shared counter half of a tracked Perl-owner acquisition. */
    final void acquireTrackedOwnerCount(String acquisitionSite) {
        if (refCount < 0 || refCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("tracked owner count is unavailable at " + acquisitionSite);
        }
        traceRefCount(+1, acquisitionSite);
        refCount++;
        hadCountedReference = true;
    }

    /** Apply one exact tracked-owner decrement; returns true at zero. */
    final boolean releaseTrackedOwnerCount(String releaseSite) {
        if (refCount <= 0) {
            throw new IllegalStateException("tracked owner count is not positive at " + releaseSite);
        }
        traceRefCount(-1, releaseSite);
        return --refCount == 0;
    }

    // ─────────────────────────────────────────────────────────────────────
    // D-W6.11 Step 1: per-scalar ownership tracking (debug-only).
    // When refCountTrace is on, every setLargeRefCounted increment records
    // the owning RuntimeScalar identity. Every paired decrement removes it.
    // At end of run, surviving owners are printed — if the count !=
    // expected (e.g. metaclass should have 1 owner = $METAS slot but
    // shows 0), we know the underflow site by elimination.
    // ─────────────────────────────────────────────────────────────────────
    private static final java.util.concurrent.atomic.AtomicLong nextTraceReferentGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    // Trace-only identity: keep it out of every RuntimeBase instance when
    // PJ_REFCOUNT_TRACE is disabled.
    private static final java.util.Map<RuntimeBase, Long> traceReferentGenerations =
        new java.util.IdentityHashMap<>();

    private static final java.util.Map<RuntimeBase, java.util.LinkedHashMap<Integer, OwnerTrace>> traceOwners
        = new java.util.IdentityHashMap<>();
    private static final java.util.Map<RuntimeBase, java.util.ArrayList<PendingOwnerRelease>>
        pendingTraceOwnerReleases = new java.util.IdentityHashMap<>();
    // Non-scalar holds (method dispatch, blessing temporaries, tie wrappers,
    // and similar runtime edges) have no RuntimeScalar identity. Keep their
    // trace-only balance separately so a selected snapshot can account for
    // every direct refCount increment while tracing is enabled.
    private static final java.util.Map<RuntimeBase, java.util.LinkedHashMap<String, Integer>>
        transientTraceOwners = new java.util.IdentityHashMap<>();

    private static final class OwnerTrace {
        final int scalarIdentity;
        final String acquireSite;

        OwnerTrace(RuntimeScalar scalar, String acquireSite) {
            this.scalarIdentity = System.identityHashCode(scalar);
            this.acquireSite = acquireSite;
        }
    }

    /**
     * Completion token retained between queueing and draining an owner release
     * or zero-count lifecycle boundary. It deliberately does not retain the
     * scalar itself: source identity and acquisition site are enough for
     * shutdown diagnosis, and a trace facility must not change Perl/JVM
     * reachability. Completion is idempotent so the pending count and optional
     * owner-slot token retire exactly once.
     */
    static final class PendingOwnerRelease {
        final int scalarIdentity;
        final long referentGeneration;
        final String acquireSite;
        final String queueSite;
        final PerlOwnerSlot.PendingRelease ownerSlotRelease;
        final java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();

        PendingOwnerRelease(int scalarIdentity, long referentGeneration,
                            String acquireSite, String queueSite) {
            this(scalarIdentity, referentGeneration, acquireSite, queueSite, null);
        }

        PendingOwnerRelease(int scalarIdentity, long referentGeneration,
                            String acquireSite, String queueSite,
                            PerlOwnerSlot.PendingRelease ownerSlotRelease) {
            this.scalarIdentity = scalarIdentity;
            this.referentGeneration = referentGeneration;
            this.acquireSite = acquireSite;
            this.queueSite = queueSite;
            this.ownerSlotRelease = ownerSlotRelease;
        }

        boolean identifiesReleasedOwnerEdge() {
            return ownerSlotRelease != null;
        }
    }

    private static long traceReferentGeneration(RuntimeBase base) {
        return traceReferentGenerations.computeIfAbsent(base,
                ignored -> nextTraceReferentGeneration.incrementAndGet());
    }

    public synchronized void recordOwner(RuntimeScalar owner, String site) {
        if (!refCountTrace || !REFCOUNT_TRACE_ENV) return;
        traceOwners
            .computeIfAbsent(this, k -> new java.util.LinkedHashMap<>())
            .put(System.identityHashCode(owner), new OwnerTrace(owner, site));
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        sb.append("[REFCOUNT-RECORD] base=").append(System.identityHashCode(this))
          .append(" owner=").append(System.identityHashCode(owner))
          .append("   ").append(site);
        for (int i = 1; i < Math.min(st.length, 12); i++) {
            sb.append("\n      at ").append(st[i]);
        }
        System.err.println(sb);
    }

    public synchronized void releaseOwner(RuntimeScalar owner, String site) {
        if (!refCountTrace || !REFCOUNT_TRACE_ENV) return;
        java.util.LinkedHashMap<Integer, OwnerTrace> owners = traceOwners.get(this);
        if (owners == null) return;
        OwnerTrace prev = owners.remove(System.identityHashCode(owner));
        if (prev == null) {
            System.err.println("[REFCOUNT-OWNER] *** UNPAIRED RELEASE *** base="
                + System.identityHashCode(this)
                + " owner=" + System.identityHashCode(owner)
                + " release-site=" + site);
            new Throwable().printStackTrace(System.err);
        }
    }

    /** Move an active owner trace into the deferred-decrement queue. */
    public PendingOwnerRelease queueOwnerRelease(RuntimeScalar owner, String queueSite) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(queueSite, "queueSite");
        PerlOwnerSlot currentSlot = owner.countedOwnerSlot();
        boolean scalarClaimedCountedOwner = owner.refCountOwned;
        PerlOwnerSlot.PendingRelease slotRelease = owner.deferCountedOwnerRelease(this);
        if (slotRelease == null
                && (currentSlot != null
                    || (scalarClaimedCountedOwner && hadCountedReference))) {
            throw new IllegalStateException(
                    "counted owner release lost its PerlOwnerSlot before queueing: "
                            + owner.getClass().getSimpleName() + " -> "
                            + getClass().getSimpleName() + " at " + queueSite);
        }
        if (slotRelease == null) {
            // Compatibility for synthetic or pre-tracking scalars only. The
            // validation above ensures a real tracked owner cannot use this.
            owner.refCountOwned = false;
        }
        registerPendingOwnerRelease(slotRelease);
        if (!refCountTrace || !REFCOUNT_TRACE_ENV) {
            return new PendingOwnerRelease(
                    System.identityHashCode(owner), 0, "counted owner-slot", queueSite, slotRelease);
        }
        return queueTracedOwnerRelease(owner, queueSite, slotRelease);
    }

    /** Queue a zero-count lifecycle boundary without fabricating an owner count. */
    synchronized PendingOwnerRelease queueLifecycleOnlyAtZero(String queueSite) {
        registerPendingOwnerRelease(null);
        return new PendingOwnerRelease(0,
                REFCOUNT_TRACE_ENV ? traceReferentGeneration(this) : 0,
                "zero-count lifecycle", queueSite);
    }

    private synchronized PendingOwnerRelease queueTracedOwnerRelease(
            RuntimeScalar owner, String queueSite, PerlOwnerSlot.PendingRelease slotRelease) {
        int scalarIdentity = System.identityHashCode(owner);
        java.util.LinkedHashMap<Integer, OwnerTrace> owners = traceOwners.get(this);
        OwnerTrace ownerTrace = owners == null ? null : owners.remove(scalarIdentity);
        String acquireSite = ownerTrace == null ? "<missing active owner trace>" : ownerTrace.acquireSite;
        PendingOwnerRelease release = new PendingOwnerRelease(
                scalarIdentity, traceReferentGeneration(this), acquireSite, queueSite, slotRelease);
        pendingTraceOwnerReleases
                .computeIfAbsent(this, k -> new java.util.ArrayList<>())
                .add(release);
        return release;
    }

    /** Queue provenance for a deferred owner-slot decrement. */
    synchronized PendingOwnerRelease queueOwnerSlotRelease(
            PerlOwnerSlot.PendingRelease ownerSlotRelease) {
        registerPendingOwnerRelease(ownerSlotRelease);
        PendingOwnerRelease release = new PendingOwnerRelease(
                0, REFCOUNT_TRACE_ENV ? traceReferentGeneration(this) : 0,
                "owner-slot", "MortalList.deferOwnerSlotDecrement", ownerSlotRelease);
        if (REFCOUNT_TRACE_ENV) {
            pendingTraceOwnerReleases.computeIfAbsent(this, ignored -> new java.util.ArrayList<>())
                    .add(release);
        }
        return release;
    }

    /** Mark the exact queued trace record as drained without relying on scalar state. */
    public synchronized boolean completeQueuedOwnerRelease(
            PendingOwnerRelease release, String releaseSite) {
        if (release == null || !release.completed.compareAndSet(false, true)) return false;
        if (release.ownerSlotRelease != null) {
            if (release.ownerSlotRelease.kind() == PerlOwnerSlot.Kind.RETURN_VALUE) {
                pendingReturnOwners--;
            }
            release.ownerSlotRelease.complete();
        }
        pendingOwnerReleases--;
        if (!REFCOUNT_TRACE_ENV) return true;
        java.util.ArrayList<PendingOwnerRelease> releases = pendingTraceOwnerReleases.get(this);
        if (releases != null) {
            releases.remove(release);
            if (releases.isEmpty()) pendingTraceOwnerReleases.remove(this);
        }
        System.err.println("[REFCOUNT-OWNER-RELEASE] base=" + System.identityHashCode(this)
                + " scalar=" + release.scalarIdentity
                + " referent-generation=" + release.referentGeneration
                + " acquire-site=" + release.acquireSite
                + " queue-site=" + release.queueSite
                + " release-site=" + releaseSite);
        return true;
    }

    public void cancelQueuedOwnerRelease(PendingOwnerRelease release, String cancelSite) {
        completeQueuedOwnerRelease(release, cancelSite + " (cancelled)");
    }

    /** Record a non-scalar owner token for trace attribution only. */
    public synchronized void acquireTransientTraceOwner(String kind, String site) {
        if (kind == null || !refCountTrace || !REFCOUNT_TRACE_ENV) return;
        transientTraceOwners.computeIfAbsent(this, ignored -> new java.util.LinkedHashMap<>())
                .merge(kind + " @ " + site, 1, Integer::sum);
    }

    /** Release the matching trace-only non-scalar owner token. */
    public synchronized void releaseTransientTraceOwner(String kind, String site) {
        if (kind == null || !refCountTrace || !REFCOUNT_TRACE_ENV) return;
        java.util.LinkedHashMap<String, Integer> owners = transientTraceOwners.get(this);
        if (owners == null) return;
        String prefix = kind + " @ ";
        String matchingKey = null;
        for (String key : owners.keySet()) {
            if (key.startsWith(prefix)) {
                matchingKey = key;
                break;
            }
        }
        if (matchingKey == null) {
            System.err.println("[REFCOUNT-TRANSIENT] *** UNPAIRED RELEASE *** base="
                    + System.identityHashCode(this) + " kind=" + kind
                    + " release-site=" + site);
            return;
        }
        int count = owners.get(matchingKey);
        if (count == 1) owners.remove(matchingKey);
        else owners.put(matchingKey, count - 1);
        if (owners.isEmpty()) transientTraceOwners.remove(this);
    }

    /**
     * Return an assertion-boundary view of the trace-only owner ledger for
     * this referent.  This is intentionally a string rather than a graph of
     * runtime objects: diagnostics must not keep an owner, a pad, or a
     * deferred scalar alive merely by inspecting it.
     *
     * <p>The view is useful while a Perl test is still at the assertion that
     * observed an unexpected count.  Shutdown dumps are too late because a
     * queued decrement may already have drained and unrelated referents make
     * the output difficult to attribute.  Active entries are scalar-store
     * tokens; pending entries are the immutable provenance retained after a
     * token is queued for deferred release.</p>
     */
    public synchronized String ownerTraceSnapshot() {
        StringBuilder result = new StringBuilder();
        result.append("base=").append(System.identityHashCode(this))
                // Do not register a static trace identity merely because a
                // program queried this diagnostic with tracing disabled.
                // The diagnostic itself must remain observational in normal
                // runtimes.
                .append(" generation=").append(REFCOUNT_TRACE_ENV
                        ? traceReferentGeneration(this) : 0)
                .append(" refCount=").append(refCount)
                .append(" active=");
        java.util.LinkedHashMap<Integer, OwnerTrace> owners = traceOwners.get(this);
        if (owners == null || owners.isEmpty()) {
            result.append("[]");
        } else {
            result.append('[');
            boolean first = true;
            for (OwnerTrace owner : owners.values()) {
                if (!first) result.append(", ");
                first = false;
                result.append("scalar=").append(owner.scalarIdentity)
                        .append(" acquire=").append(owner.acquireSite);
            }
            result.append(']');
        }
        result.append(" capturePads=");
        if (ownerSlots == null || ownerSlots.isEmpty()) {
            result.append("[]");
        } else {
            result.append('[');
            boolean first = true;
            for (PerlOwnerSlot slot : ownerSlots) {
                if (slot.kind() != PerlOwnerSlot.Kind.CLOSURE_PAD) continue;
                RuntimeScalar pad = slot.padCell();
                if (!first) result.append(", ");
                first = false;
                result.append("scalar=").append(System.identityHashCode(pad))
                        .append(" type=").append(pad.type)
                        .append(" captureCount=").append(pad.captureCount)
                        .append(" captureRefCountOwned=").append(pad.captureRefCountOwned())
                        .append(" scopeExited=").append(pad.scopeExited)
                        .append(" slotOwnsReferent=").append(pad.refCountOwned)
                        .append(" value=");
                if (pad.value instanceof RuntimeBase valueBase) {
                    result.append(valueBase.getClass().getSimpleName())
                            .append('#').append(System.identityHashCode(valueBase));
                } else if (pad.value == null) {
                    result.append("null");
                } else {
                    result.append(pad.value.getClass().getSimpleName());
                }
                result.append(" capturedBy=[");
                boolean firstCaptureOwner = true;
                for (PerlOwnerSlot padOwner : pad.ownerSlotSnapshot()) {
                    if (padOwner.kind() != PerlOwnerSlot.Kind.SCALAR_CAPTURE) continue;
                    if (!firstCaptureOwner) result.append(", ");
                    firstCaptureOwner = false;
                    RuntimeCode code = padOwner.captureOwner();
                    if (code == null) {
                        result.append("unknown");
                    } else {
                        result.append(System.identityHashCode(code)).append(':')
                                .append(code.packageName == null ? "" : code.packageName)
                                .append("::").append(code.subName == null ? "" : code.subName)
                                .append('@').append(code.cvStartFile == null ? "" : code.cvStartFile)
                                .append(':').append(code.cvStartLine);
                    }
                }
                result.append(']');
                RuntimeCode captureOwner = slot.captureOwner();
                if (captureOwner != null) {
                    result.append(" ownerCode=").append(System.identityHashCode(captureOwner))
                            .append(" name=")
                            .append(captureOwner.packageName == null ? "" : captureOwner.packageName)
                            .append("::")
                            .append(captureOwner.subName == null ? "" : captureOwner.subName)
                            .append(" source=")
                            .append(captureOwner.cvStartFile == null ? "" : captureOwner.cvStartFile)
                            .append(':').append(captureOwner.cvStartLine);
                }
            }
            result.append(']');
        }
        result.append(" ownerSlots=[");
        boolean firstOwnerSlot = true;
        for (PerlOwnerSlot ownerSlot : ownerSlotSnapshot()) {
            if (!ownerSlot.isActive()) continue;
            if (!firstOwnerSlot) result.append(", ");
            firstOwnerSlot = false;
            result.append(ownerSlot.kind()).append('#').append(ownerSlot.identity());
            int resourceOwnerIdentity = ownerSlot.resourceScalarIdentity();
            if (resourceOwnerIdentity != 0) {
                result.append(" resourceScalarId=").append(resourceOwnerIdentity);
                if (ownerSlot.resourceScalarOwner() == null) result.append(" carrierCollected");
            }
            RuntimeScalar owner = ownerSlot.countedScalarOwner();
            if (owner == null) owner = ownerSlot.untrackedScalarOwner();
            if (owner == null) owner = ownerSlot.scalarContentsOwner();
            if (owner == null) owner = ownerSlot.suspendedScalarOwner();
            if (owner == null) owner = ownerSlot.resourceScalarOwner();
            if (owner != null) {
                result.append(" scalar=").append(System.identityHashCode(owner))
                        .append(" type=").append(owner.type)
                        .append(" scopeExited=").append(owner.scopeExited)
                        .append(" owns=").append(owner.refCountOwned);
                RuntimeBase container = owner.containerOwner;
                if (container != null) {
                    result.append(" container=").append(container.getClass().getSimpleName())
                            .append('#').append(System.identityHashCode(container));
                    if (container instanceof RuntimeArray array) {
                        PerlRuntime runtime = PerlRuntime.currentOrNull();
                        boolean activeArgs = runtime != null
                                && runtime.executionState().argsStack.contains(array);
                        result.append(" arraySize=").append(array.elements.size())
                                .append(" aliased=").append(array.elementsAliased)
                                .append(" owned=").append(array.elementsOwned)
                                .append(" activeArgs=").append(activeArgs);
                        for (int i = 0; i < array.elements.size(); i++) {
                            if (array.elements.get(i) == owner) {
                                result.append(" index=").append(i);
                                break;
                            }
                        }
                        result.append(" arrayRefCount=").append(array.refCount)
                                .append(" arrayOwners=[");
                        boolean firstArrayOwner = true;
                        for (PerlOwnerSlot arrayOwner : array.ownerSlotSnapshot()) {
                            if (!arrayOwner.isActive()) continue;
                            if (!firstArrayOwner) result.append(", ");
                            firstArrayOwner = false;
                            result.append(arrayOwner.kind()).append('#').append(arrayOwner.identity());
                            RuntimeScalar arrayOwnerCell = arrayOwner.countedScalarOwner();
                            if (arrayOwnerCell == null) {
                                arrayOwnerCell = arrayOwner.untrackedScalarOwner();
                            }
                            if (arrayOwnerCell != null) {
                                result.append(" scalar=")
                                        .append(System.identityHashCode(arrayOwnerCell));
                            }
                        }
                        result.append(']');
                    }
                }
                if (owner.isPackageGlobalRoot) result.append(" packageRoot");
                if (owner.localBindingExists) result.append(" localBinding");
            }
        }
        result.append(']');
        result.append(" pending=");
        java.util.ArrayList<PendingOwnerRelease> pending = pendingTraceOwnerReleases.get(this);
        if (pending == null || pending.isEmpty()) {
            result.append("[]");
        } else {
            result.append('[');
            for (int i = 0; i < pending.size(); i++) {
                if (i != 0) result.append(", ");
                PendingOwnerRelease release = pending.get(i);
                if (release.ownerSlotRelease != null) {
                    result.append("ownerSlot=").append(release.ownerSlotRelease.ownerSlotIdentity())
                            .append(" sequence=").append(release.ownerSlotRelease.sequence())
                            .append(" kind=").append(release.ownerSlotRelease.kind());
                } else {
                    result.append("scalar=").append(release.scalarIdentity)
                            .append(" generation=").append(release.referentGeneration)
                            .append(" acquire=").append(release.acquireSite)
                            .append(" queued=").append(release.queueSite);
                }
            }
            result.append(']');
        }
        result.append(" transient=");
        java.util.LinkedHashMap<String, Integer> transientOwners = transientTraceOwners.get(this);
        if (transientOwners == null || transientOwners.isEmpty()) {
            result.append("[]");
        } else {
            result.append('[');
            boolean first = true;
            for (java.util.Map.Entry<String, Integer> owner : transientOwners.entrySet()) {
                if (!first) result.append(", ");
                first = false;
                result.append(owner.getKey()).append(" count=").append(owner.getValue());
            }
            result.append(']');
        }
        result.append(" semanticCaptureOwners=").append(semanticCaptureOwnerCount());
        return result.toString();
    }

    public static void dumpTraceOwners() {
        if (!REFCOUNT_TRACE_ENV) return;
        for (java.util.Map.Entry<RuntimeBase, java.util.LinkedHashMap<Integer, OwnerTrace>> e
                : traceOwners.entrySet()) {
            RuntimeBase b = e.getKey();
            if (e.getValue().isEmpty()) continue;
            System.err.println("[REFCOUNT-OWNERS] base=" + System.identityHashCode(b)
                + " (" + b.getClass().getSimpleName() + ")"
                + " blessId=" + b.blessId
                + " refCount=" + b.refCount
                + " owners=" + e.getValue().size());
            for (java.util.Map.Entry<Integer, OwnerTrace> own : e.getValue().entrySet()) {
                System.err.println("    owner=" + own.getKey() + " from " + own.getValue().acquireSite);
            }
            System.err.println("    " + b.ownerTraceSnapshot());
        }
        for (java.util.Map.Entry<RuntimeBase, java.util.ArrayList<PendingOwnerRelease>> e
                : pendingTraceOwnerReleases.entrySet()) {
            RuntimeBase b = e.getKey();
            for (PendingOwnerRelease release : e.getValue()) {
                System.err.println("[REFCOUNT-PENDING-OWNER] base=" + System.identityHashCode(b)
                        + " scalar=" + release.scalarIdentity
                        + " referent-generation=" + release.referentGeneration
                        + " acquire-site=" + release.acquireSite
                        + " queue-site=" + release.queueSite
                        + " release-site=<pending>");
            }
        }
    }

    /**
     * True if this container (hash or array) was created as a named variable
     * ({@code my %hash} or {@code my @array}) and a reference to it was created
     * via the {@code \} operator. This flag indicates that a JVM local variable
     * slot holds a strong reference that is NOT counted in {@code refCount}.
     * <p>
     * When {@code refCount} reaches 0, this flag prevents premature destruction:
     * the local variable may still be alive, so the container is not truly
     * unreferenced. The flag is cleared by {@code scopeExitCleanupHash/Array}
     * when the local variable's scope ends, allowing subsequent refCount==0
     * to correctly trigger callDestroy.
     */
    public boolean localBindingExists = false;

    /**
     * True once a scalar/container slot has taken a counted reference to this
     * base. If later refcount drift leaves the count at zero during scope
     * cleanup, this tells cleanup that an external owner may have existed and a
     * bounded reachability check is worthwhile.
     */
    public boolean hadCountedReference = false;

    /**
     * True once DESTROY has been called for this object. Perl 5 semantics:
     * if an object is resurrected by DESTROY (stored somewhere during DESTROY),
     * and its refCount later reaches 0 again, DESTROY is NOT called a second time.
     * The object is simply freed with weak ref clearing and cascading cleanup.
     * This prevents infinite DESTROY cycles from self-referential patterns like
     * Schema::DESTROY re-attaching to a ResultSource.
     */
    public boolean destroyFired = false;

    /**
     * Phase 3 (refcount_alignment_plan.md): True while DESTROY is actively
     * running on this object. Used as a re-entry guard: when refCount drops
     * to 0 during the DESTROY body (via deferred decrements from MortalList
     * flush, closure releases, etc.), the caller transitions refCount to
     * MIN_VALUE and calls callDestroy. callDestroy detects
     * {@code currentlyDestroying == true} and restores refCount to 0 (so
     * subsequent stores can still track refs) then returns without entering
     * the Perl DESTROY body a second time.
     */
    public boolean currentlyDestroying = false;

    /**
     * Phase 3 (refcount_alignment_plan.md): True when a previous DESTROY
     * body left the object with a strong reference count > 0 (resurrection
     * via an escaped strong ref). Matches Perl 5's semantics for
     * re-invoking DESTROY when the resurrected object is finally released.
     * Checked in callDestroy to decide whether to invoke Perl DESTROY a
     * second time. Required for DBIC detected_reinvoked_destructor pattern
     * (t/storage/txn_scope_guard.t test 18).
     */
    public boolean needsReDestroy = false;

    /**
     * Global flag: true once any object has been blessed (blessId set to non-zero).
     * Used by MortalList.scopeExitCleanupArray/Hash to skip expensive container
     * walks when no blessed objects have ever been created in this JVM instance.
     * Once set to true, it stays true forever (conservative but safe).
     */
    public static boolean blessedObjectExists() {
        return PerlRuntime.current().lifecycleState.blessedObjectExists;
    }

    static void noteBlessedObject() {
        PerlRuntime.current().lifecycleState.blessedObjectExists = true;
    }

    /**
     * Adds this entity to the specified RuntimeList.
     *
     * @param list the RuntimeList to which this entity will be added
     */
    public void addToList(RuntimeList list) {
        list.add(this);
    }

    /**
     * Adds itself to a RuntimeArray.
     *
     * @param array the RuntimeArray object to which this entity will be added
     */
    public abstract void addToArray(RuntimeArray array);

    /**
     * Retrieves a RuntimeScalar instance.
     *
     * @return a RuntimeScalar object representing the scalar value
     */
    public abstract RuntimeScalar scalar();

    /**
     * Retrieves a RuntimeList instance.
     * This is always called at the end of a subroutine to transform the return value to RuntimeList.
     *
     * @return a RuntimeList object representing the list of elements
     */
    public abstract RuntimeList getList();

    /**
     * Retrieves the array value of the object as aliases.
     * This method initializes a new RuntimeArray and sets it as the alias for this entity.
     *
     * @return a RuntimeArray representing the array of aliases for this entity
     */
    public RuntimeArray getArrayOfAlias() {
        RuntimeArray arr = new RuntimeArray();
        this.setArrayOfAlias(arr);
        arr.elementsAliased = true;
        arr.elementsOwned = arr.ownedAliasElements != null && !arr.ownedAliasElements.isEmpty();
        return arr;
    }

    /**
     * Returns the iterator used by an implicit-topic foreach loop.
     * Non-array list expressions retain their evaluated membership snapshot.
     */
    public Iterator<RuntimeScalar> foreachAliasIterator() {
        return getArrayOfAlias().iterator();
    }

    /**
     * Retrieves the argument array for {@code goto &sub}. Most values use
     * ordinary aliasing, but RuntimeArray overrides this to transfer ownership
     * of any refs inserted into the current frame's {@code @_}.
     */
    public RuntimeArray getTailCallArrayOfAlias() {
        return getArrayOfAlias();
    }

    /**
     * Gets the total number of elements in all elements of the list as a RuntimeScalar.
     * This method provides a count of elements, useful for determining the size of collections.
     *
     * @return a RuntimeScalar representing the count of elements
     */
    public RuntimeScalar count() {
        return new RuntimeScalar(countElements());
    }

    /**
     * Abstract method to set the array of aliases for this entity.
     * Subclasses should provide an implementation for this method.
     *
     * @param arr the RuntimeArray to be set as the array of aliases
     */
    public abstract RuntimeArray setArrayOfAlias(RuntimeArray arr);

    /**
     * Abstract method to count the elements within this entity.
     * Subclasses should provide an implementation for this method.
     *
     * @return the number of elements as an integer
     */
    public abstract int countElements();

    public void setBlessId(int blessId) {
        this.blessId = blessId;
        if (blessId != 0) noteBlessedObject();
    }

    /**
     * Gets the first element of the list.
     * For arrays and hashes, returns their first element using iteration.
     *
     * @return The first element as a RuntimeBase, or `undef` if empty
     */
    public RuntimeScalar getFirst() {
        Iterator<RuntimeScalar> iterator = this.iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return scalarUndef;
    }

    public String toStringRef() {
        registerReferenceAddress();
        return this.toString();
    }

    protected final String referenceAddressHex() {
        return Long.toHexString(PerlRuntime.referenceAddress(this));
    }

    protected final void registerReferenceAddress() {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null) {
            runtime.registerReferenceAddress(this);
        }
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    /**
     * Retrieves the boolean value of the object.
     *
     * @return a boolean representing the truthiness of the object
     */
    public abstract boolean getBoolean();

    /**
     * Boolean conversion with lexical operator overloading disabled.
     * Aggregate runtime values have no overload dispatch of their own, so the
     * default implementation is identical to ordinary boolean conversion.
     */
    public boolean getBooleanNoOverload() {
        return getBoolean();
    }

    /**
     * Retrieves the defined boolean value of the object.
     *
     * @return a boolean indicating whether the object is defined
     */
    public abstract boolean getDefinedBoolean();

    /**
     * Creates a reference to the current object.
     *
     * @return a RuntimeScalar representing the reference
     */
    public abstract RuntimeScalar createReference();

    /**
     * Creates a reference for an operation that only inspects its target.
     * Aggregate element proxies override this so {@code tied $hash{missing}}
     * and {@code untie $hash{missing}} do not materialize a hash slot.
     */
    public RuntimeScalar createReferenceNoVivify() {
        return createReference();
    }

    /**
     * Creates a reference and tracks refCounts for contained elements.
     * Used for anonymous array/hash construction ([...], {...}) where elements
     * need refCount tracking to prevent premature destruction of referents.
     * Default implementation delegates to createReference().
     *
     * @return a RuntimeScalar representing the reference
     */
    public RuntimeScalar createReferenceWithTrackedElements() {
        return createReference();
    }

    /**
     * Undefines the elements of the object.
     *
     * @return the object after undefining its elements
     */
    public abstract RuntimeBase undefine();

    /**
     * Adds itself to a RuntimeScalar.
     *
     * @param scalar the RuntimeScalar object to which this entity will be added
     * @return the updated RuntimeScalar object
     */
    public abstract RuntimeScalar addToScalar(RuntimeScalar scalar);

    /**
     * Sets itself from a RuntimeList.
     *
     * @param list the RuntimeList object from which this entity will be set
     * @return the updated RuntimeArray object
     */
    public abstract RuntimeArray setFromList(RuntimeList list);

    /** Assign from a list when the Perl expression result is discarded. */
    public RuntimeArray setFromListVoid(RuntimeList list) {
        return setFromList(list);
    }

    /**
     * Retrieves the result of keys() as a RuntimeArray instance.
     *
     * @return a RuntimeArray object representing the keys
     */
    public abstract RuntimeArray keys();

    /**
     * Context-aware keys() operator.
     *
     * <p>Default implementation materializes the key list via {@link #keys()}.
     * Subclasses may override to avoid allocation in scalar/void contexts.</p>
     */
    public RuntimeBase keys(int ctx) {
        RuntimeArray list = keys();
        if (ctx == RuntimeContextType.SCALAR) {
            return list.scalar();
        }
        return list;
    }

    /**
     * Retrieves the result of values() as a RuntimeArray instance.
     *
     * @return a RuntimeArray object representing the values
     */
    public abstract RuntimeArray values();

    /**
     * Retrieves the result of each() as a RuntimeList instance.
     *
     * @return a RuntimeList object representing the key-value pairs
     */
    public abstract RuntimeList each(int ctx);

    /**
     * Performs the chop operation on the object.
     *
     * @return a RuntimeScalar representing the result of the chop operation
     */
    public abstract RuntimeScalar chop();

    /**
     * Performs the chomp operation on the object.
     *
     * @return a RuntimeScalar representing the result of the chomp operation
     */
    public abstract RuntimeScalar chomp();
}

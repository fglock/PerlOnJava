package org.perlonjava.runtime.runtimetypes;

import java.util.Iterator;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * The RuntimeBase class serves as an abstract base class for scalar, hash,
 * and array variables in the runtime environment. It provides common functionality
 * and interfaces for these entities.
 */
public abstract class RuntimeBase implements DynamicState, Iterable<RuntimeScalar> {
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

    // ─────────────────────────────────────────────────────────────────────
    // D-W6.13: production-grade ownership tracking.
    // Active for blessed objects that have at least one weak reference
    // (the only case where we care about precise refCount semantics).
    // Stores the set of RuntimeScalars that hold a counted strong ref
    // to this base (refCountOwned=true && value==this).
    // At MortalList.flush() refCount→0, this is consulted: if non-empty,
    // refCount is restored to the owner count and DESTROY is suppressed.
    // ─────────────────────────────────────────────────────────────────────
    public java.util.Set<RuntimeScalar> activeOwners = null;

    public void activateOwnerTracking() {
        if (activeOwners == null) {
            activeOwners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            // Note: backfilling from ScalarRefRegistry.snapshot() was
            // observed to cause DBIC leak-test regressions due to
            // WeakHashMap.expungeStaleEntries() side-effects on JVM GC
            // observability. We rely on incremental population via
            // recordActiveOwner() from setLargeRefCounted; objects that
            // had setLarge incs BEFORE activation will be missed, but
            // that's a known limitation — the activation should fire on
            // first weaken(), which usually precedes the relevant stores.
        }
    }

    public void recordActiveOwner(RuntimeScalar scalar) {
        if (activeOwners != null) {
            activeOwners.add(scalar);
        }
    }

    public void releaseActiveOwner(RuntimeScalar scalar) {
        if (activeOwners != null) {
            activeOwners.remove(scalar);
        }
    }

    public int activeOwnerCount() {
        if (activeOwners == null) return 0;
        // Filter for actual still-owning scalars: refCountOwned=true and
        // value==this. Stale entries (overwritten without going through a
        // tracked release path, or scope-exited via untracked paths) are
        // pruned and ignored.
        java.util.Iterator<RuntimeScalar> it = activeOwners.iterator();
        int count = 0;
        while (it.hasNext()) {
            RuntimeScalar sc = it.next();
            if (sc != null && sc.refCountOwned && sc.value == this) {
                count++;
            } else {
                it.remove();
            }
        }
        return count;
    }

    /**
     * D-W6.14: count owners that are reachable from package globals or
     * live my-vars. This is the strict version used by the production
     * rescue at MortalList.flush refCount→0: only objects whose owners
     * can be reached from real roots are kept alive. Phantom owners
     * (scalars that should be dead but haven't been released yet) are
     * excluded. Cycles with no external root → all owners unreachable
     * → 0 → DESTROY fires (matching real Perl's cycle leak behavior
     * resolved by user weaken()).
     */
    public int reachableOwnerCount() {
        if (activeOwners == null) return 0;
        int count = 0;
        for (RuntimeScalar sc : activeOwners) {
            if (sc != null && sc.refCountOwned && sc.value == this
                    && ReachabilityWalker.isScalarReachable(sc)) {
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

    // ─────────────────────────────────────────────────────────────────────
    // D-W6.11 Step 1: per-scalar ownership tracking (debug-only).
    // When refCountTrace is on, every setLargeRefCounted increment records
    // the owning RuntimeScalar identity. Every paired decrement removes it.
    // At end of run, surviving owners are printed — if the count !=
    // expected (e.g. metaclass should have 1 owner = $METAS slot but
    // shows 0), we know the underflow site by elimination.
    // ─────────────────────────────────────────────────────────────────────
    private static final java.util.Map<RuntimeBase, java.util.LinkedHashMap<Integer, String>> traceOwners
        = new java.util.IdentityHashMap<>();

    public synchronized void recordOwner(RuntimeScalar owner, String site) {
        if (!refCountTrace || !REFCOUNT_TRACE_ENV) return;
        traceOwners
            .computeIfAbsent(this, k -> new java.util.LinkedHashMap<>())
            .put(System.identityHashCode(owner), site);
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        sb.append("[REFCOUNT-RECORD] base=").append(System.identityHashCode(this))
          .append(" owner=").append(System.identityHashCode(owner))
          .append("   ").append(site);
        for (int i = 1; i < Math.min(st.length, 5); i++) {
            sb.append("\n      at ").append(st[i]);
        }
        System.err.println(sb);
    }

    public synchronized void releaseOwner(RuntimeScalar owner, String site) {
        if (!refCountTrace || !REFCOUNT_TRACE_ENV) return;
        java.util.LinkedHashMap<Integer, String> owners = traceOwners.get(this);
        if (owners == null) return;
        String prev = owners.remove(System.identityHashCode(owner));
        if (prev == null) {
            System.err.println("[REFCOUNT-OWNER] *** UNPAIRED RELEASE *** base="
                + System.identityHashCode(this)
                + " owner=" + System.identityHashCode(owner)
                + " release-site=" + site);
            new Throwable().printStackTrace(System.err);
        }
    }

    public static void dumpTraceOwners() {
        if (!REFCOUNT_TRACE_ENV) return;
        for (java.util.Map.Entry<RuntimeBase, java.util.LinkedHashMap<Integer, String>> e
                : traceOwners.entrySet()) {
            RuntimeBase b = e.getKey();
            if (e.getValue().isEmpty()) continue;
            System.err.println("[REFCOUNT-OWNERS] base=" + System.identityHashCode(b)
                + " (" + b.getClass().getSimpleName() + ")"
                + " blessId=" + b.blessId
                + " refCount=" + b.refCount
                + " owners=" + e.getValue().size());
            for (java.util.Map.Entry<Integer, String> own : e.getValue().entrySet()) {
                System.err.println("    owner=" + own.getKey() + " from " + own.getValue());
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
    public static volatile boolean blessedObjectExists = false;

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
        return arr;
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
        if (blessId != 0) blessedObjectExists = true;
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
        return this.toString();
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
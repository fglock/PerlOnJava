package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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

    // Always-on: refCount tracking for birth-tracked objects (anonymous hashes,
    // arrays, closures with captures) requires balanced increment/decrement.
    // The increment side fires unconditionally in setLarge() when refCount >= 0,
    // so the decrement side (deferDecrementIfTracked, flush, etc.) must also
    // be active from the start.  The per-method `!active` guards are retained
    // as a trivially-predicted branch; the JIT will elide them.
    public static boolean active = true;

    // List of RuntimeBase references awaiting decrement.
    // Populated by delete() when removing tracked elements.
    // Drained at statement boundaries (FREETMPS equivalent).
    private static final ArrayList<RuntimeBase> pending = new ArrayList<>();

    // Scalars whose scope has exited while captureCount > 0.
    // These variables hold blessed references that could not be decremented
    // at scope exit because closures still reference the RuntimeScalar.
    // Processed by flushDeferredCaptures() after the main script returns,
    // before END blocks run.
    private static final ArrayList<RuntimeScalar> deferredCaptures = new ArrayList<>();

    // Phase I: parallel identity set for O(1) membership check.
    // Used by ReachabilityWalker to skip scalars that are waiting in
    // deferredCaptures for final cleanup — they are effectively dead
    // from Perl's view, only held Java-alive by this static list.
    private static final java.util.IdentityHashMap<RuntimeScalar, Integer> deferredCapturesSet = new java.util.IdentityHashMap<>();

    /**
     * Phase I: O(1) check whether the given scalar is in
     * {@link #deferredCaptures}. Used by the reachability walker to
     * filter out stale {@link ScalarRefRegistry} seeds.
     */
    public static boolean isDeferredCapture(RuntimeScalar scalar) {
        if (scalar == null) return false;
        return deferredCapturesSet.containsKey(scalar);
    }

    /**
     * Schedule a deferred refCount decrement for a tracked referent.
     * Called from delete() when removing a tracked blessed reference
     * from a container.
     */
    public static void deferDecrement(RuntimeBase base) {
        if (base.refCountTrace) {
            base.traceRefCount(0, "MortalList.deferDecrement (queued)");
        }
        pending.add(base);
    }

    /**
     * Record a captured scalar whose scope has exited but whose refCount
     * could not be decremented because {@code captureCount > 0}.
     * Called from {@link RuntimeScalar#scopeExitCleanup} for non-CODE
     * blessed references that are captured by closures.
     * <p>
     * These entries are processed by {@link #flushDeferredCaptures()} after
     * the main script returns, before END blocks run.
     */
    public static void addDeferredCapture(RuntimeScalar scalar) {
        deferredCaptures.add(scalar);
        deferredCapturesSet.merge(scalar, 1, Integer::sum);
    }

    /**
     * Process deferred captures whose captureCount has already reached 0.
     * Called from {@link #popAndFlush()} at block scope exit, AFTER the
     * mortal list has been processed (which may trigger callDestroy →
     * releaseCaptures → captureCount decrements on captured variables).
     * <p>
     * This bridges the gap between deferred capture registration (at scope
     * exit when captureCount > 0) and flushDeferredCaptures (after the main
     * script returns). Without this, objects whose captures are fully
     * released at block exit still appear "alive" to leak tracers like
     * DBIC's assert_empty_weakregistry, which runs inside the main script.
     * <p>
     * Only processes entries where captureCount == 0 AND scopeExited == true,
     * leaving others for later processing (either a subsequent block exit
     * or flushDeferredCaptures at script end).
     */
    private static void processReadyDeferredCaptures() {
        if (deferredCaptures.isEmpty()) return;
        boolean found = false;
        for (int i = deferredCaptures.size() - 1; i >= 0; i--) {
            RuntimeScalar scalar = deferredCaptures.get(i);
            if (scalar.captureCount == 0 && scalar.scopeExited) {
                deferDecrementIfTracked(scalar);
                deferredCaptures.remove(i);
                removeFromDeferredSet(scalar);
                found = true;
            }
        }
        if (found) {
            flush();
        }
    }

    private static void removeFromDeferredSet(RuntimeScalar scalar) {
        Integer c = deferredCapturesSet.get(scalar);
        if (c == null) return;
        if (c <= 1) deferredCapturesSet.remove(scalar);
        else deferredCapturesSet.put(scalar, c - 1);
    }

    /**
     * Process all deferred captured scalars.
     * For each scalar, schedule a refCount decrement via
     * {@link #deferDecrementIfTracked}, then flush the pending list.
     * <p>
     * Called from PerlLanguageProvider after the main script's
     * {@code MortalList.flush()} and before END blocks, so that
     * blessed objects whose refCount was kept elevated by interpreter
     * closure captures (which capture ALL visible lexicals, not just
     * referenced ones) have DESTROY fire before END block leak checks.
     * <p>
     * This is safe because at this point ALL lexical scopes have exited
     * (the main script has returned). Closures installed in stashes still
     * hold JVM references to the RuntimeScalar, but the cooperative
     * refCount should reflect that the declaring scope is gone.
     */
    public static void flushDeferredCaptures() {
        if (deferredCaptures.isEmpty()) return;
        for (RuntimeScalar scalar : deferredCaptures) {
            deferDecrementIfTracked(scalar);
        }
        deferredCaptures.clear();
        deferredCapturesSet.clear();
        flush();

        // After flushing deferred captures, clear weak refs for objects that
        // were rescued by DESTROY (e.g., Schema::DESTROY self-save pattern).
        // This must happen AFTER the flush above so that all pending refCount
        // decrements have been processed, and BEFORE END blocks run so that
        // DBIC's assert_empty_weakregistry sees the weak refs as undef.
        DestroyDispatch.clearRescuedWeakRefs();

        // Final sweep: clear weak refs for ALL remaining blessed objects.
        // At this point the main script has returned and all lexical scopes
        // have exited. Some objects may still have inflated cooperative
        // refCounts (due to JVM temporaries, method-call copies, interpreter
        // captures) that prevent DESTROY from firing. Their weak refs would
        // remain defined forever, causing DBIC's leak tracer to report false
        // leaks. Clearing weak refs here is safe because:
        // 1. Only weak refs are cleared — the Java objects remain alive
        // 2. CODE refs are excluded (they may still be called from stashes)
        // 3. END blocks (where leak checks run) execute AFTER this point
        WeakRefRegistry.clearAllBlessedWeakRefs();
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
                if (base.refCountTrace) {
                    base.traceRefCount(0, "MortalList.deferDecrementIfTracked (queued, scalar.refCountOwned->false)");
                    base.releaseOwner(scalar, "deferDecrementIfTracked");
                }
                base.releaseActiveOwner(scalar);
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
     * Like {@link #deferDecrementIfTracked}, but delegates to
     * {@link RuntimeScalar#scopeExitCleanup} if the scalar is captured
     * by a closure ({@code captureCount > 0}).
     * Used by the explicit {@code return} bytecode path which bypasses
     * {@link RuntimeScalar#scopeExitCleanup}.
     */
    public static void deferDecrementIfNotCaptured(RuntimeScalar scalar) {
        if (!active || scalar == null) return;
        if (scalar.captureCount > 0) {
            // Delegate to scopeExitCleanup which handles:
            // - Self-referential cycle detection (eval STRING closures)
            // - Setting scopeExited flag for deferred cleanup via releaseCaptures
            RuntimeScalar.scopeExitCleanup(scalar);
            return;
        }
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
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDestroyForContainerClear (queued)");
                    }
                    base.releaseActiveOwner(scalar);
                    pending.add(base);
                } else if (base.blessId != 0 && base.refCount == 0) {
                    // Never-stored blessed object: bump to 1 so flush triggers DESTROY
                    if (base.refCountTrace) {
                        base.traceRefCount(+1, "MortalList.deferDestroyForContainerClear (refCount=1 bump for never-stored)");
                    }
                    base.refCount = 1;
                    pending.add(base);
                }
                // Note: WEAKLY_TRACKED (-2) objects are not scheduled here.
                // See deferDecrementIfTracked() for rationale.
            }
        }
    }

    /**
     * Scope-exit cleanup for a single JVM local variable of unknown type.
     * Used by the JVM backend's eval exception handler to clean up all
     * my-variables when die unwinds through eval, since the normal
     * SCOPE_EXIT_CLEANUP bytecodes are skipped by Java exception handling.
     * <p>
     * Dispatches to the appropriate cleanup method based on runtime type.
     * Safe to call with null, non-Perl types, or already-cleaned-up values.
     *
     * @param local the JVM local variable value (may be null or any type)
     */
    public static void evalExceptionScopeCleanup(Object local) {
        if (local == null) return;
        if (local instanceof RuntimeScalar rs) {
            RuntimeScalar.scopeExitCleanup(rs);
        } else if (local instanceof RuntimeHash rh) {
            scopeExitCleanupHash(rh);
        } else if (local instanceof RuntimeArray ra) {
            scopeExitCleanupArray(ra);
        }
        // Other types (RuntimeList, Integer, etc.) are ignored - they don't need cleanup
    }

    /**
     * Recursively walk a RuntimeHash's values and defer refCount decrements
     * for any tracked blessed references found (including inside nested
     * arrays/hashes). Called at scope exit for {@code my %hash} variables.
     */
    public static void scopeExitCleanupHash(RuntimeHash hash) {
        if (!active || hash == null) return;
        // Clear localBindingExists: the named variable's scope is ending.
        // This allows subsequent refCount==0 events (from setLargeRefCounted
        // or flush) to correctly trigger callDestroy, since the local
        // variable no longer holds a strong reference.
        hash.localBindingExists = false;
        // Skip container walks only when there are NO blessed objects AND NO
        // weak refs anywhere in the JVM. If weak refs exist (even to unblessed
        // data), we must still cascade decrements so their weak-ref entries
        // can be cleared when the referent's refCount reaches 0.
        if (!RuntimeBase.blessedObjectExists && !WeakRefRegistry.weakRefsExist) return;
        // If the hash has outstanding references (e.g., from \%hash stored elsewhere),
        // do NOT clean up elements — the hash is still alive and its elements are
        // accessible through the reference. Cleanup will happen when the last
        // reference is released (in DestroyDispatch.callDestroy).
        if (hash.refCount > 0) return;
        // Quick scan: skip if no value could transitively contain blessed/tracked refs.
        boolean needsWalk = false;
        for (RuntimeScalar val : hash.elements.values()) {
            if (val != null && (val.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && val.value instanceof RuntimeBase rb) {
                if (rb.blessId != 0 || rb.refCount >= 0) {
                    needsWalk = true;
                    break;
                }
                // Container ref — peek at children for any reference-type elements
                if (rb instanceof RuntimeArray innerArr) {
                    for (RuntimeScalar inner : innerArr.elements) {
                        if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                            needsWalk = true;
                            break;
                        }
                    }
                } else if (rb instanceof RuntimeHash innerHash) {
                    for (RuntimeScalar inner : innerHash.elements.values()) {
                        if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                            needsWalk = true;
                            break;
                        }
                    }
                }
                if (needsWalk) break;
            }
        }
        if (!needsWalk) return;
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
        // Clear localBindingExists: the named variable's scope is ending.
        // This allows subsequent refCount==0 events (from setLargeRefCounted
        // or flush) to correctly trigger callDestroy, since the local
        // variable no longer holds a strong reference.
        arr.localBindingExists = false;
        // Skip container walks only when there are NO blessed objects AND NO
        // weak refs anywhere in the JVM (see scopeExitCleanupHash for details).
        if (!RuntimeBase.blessedObjectExists && !WeakRefRegistry.weakRefsExist) return;
        // If the array has outstanding references (e.g., from \@array stored elsewhere),
        // do NOT clean up elements — the array is still alive and its elements are
        // accessible through the reference. Cleanup will happen when the last
        // reference is released (in DestroyDispatch.callDestroy).
        if (arr.refCount > 0) return;
        // Quick scan: check if any element either:
        //   1. Owns a refCount (was assigned via setLarge with a tracked referent), OR
        //   2. Is a direct blessed reference (blessId != 0), OR
        //   3. Is a container (array/hash ref) that might hold nested blessed refs
        // For case 3, we peek one level deep to avoid false positives for arrays
        // of plain-data arrayrefs (like the life_bitpacked @grid).
        boolean needsWalk = false;
        for (RuntimeScalar elem : arr.elements) {
            if (elem == null || (elem.type & RuntimeScalarType.REFERENCE_BIT) == 0) continue;
            // Fast check: refCountOwned means this ref was properly tracked via setLarge
            if (elem.refCountOwned) { needsWalk = true; break; }
            if (!(elem.value instanceof RuntimeBase rb)) continue;
            // Direct blessed ref
            if (rb.blessId != 0 || rb.refCount >= 0) { needsWalk = true; break; }
            // Container ref — peek at children for reference-type elements
            if (rb instanceof RuntimeArray innerArr) {
                for (RuntimeScalar inner : innerArr.elements) {
                    if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                        needsWalk = true; break;
                    }
                }
            } else if (rb instanceof RuntimeHash innerHash) {
                for (RuntimeScalar inner : innerHash.elements.values()) {
                    if (inner != null && (inner.type & RuntimeScalarType.REFERENCE_BIT) != 0) {
                        needsWalk = true; break;
                    }
                }
            }
            if (needsWalk) break;
        }
        if (!needsWalk) return;
        for (RuntimeScalar elem : arr.elements) {
            deferDecrementRecursive(elem);
        }
    }

    /**
     * Iteratively process a scalar value: if it holds a reference to a
     * tracked blessed object and owns a refCount, defer a decrement.
     * If it holds a reference to an unblessed container, walk into
     * its elements (iteratively with cycle detection to avoid
     * StackOverflowError on circular data structures like ExifTool's).
     */
    private static void deferDecrementRecursive(RuntimeScalar scalar) {
        if (scalar == null || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0) return;
        if (!(scalar.value instanceof RuntimeBase topBase)) return;

        // Fast path for leaf references (the overwhelmingly common case):
        // unblessed, untracked referents with no nested containers to walk.
        if (topBase.blessId == 0 && topBase.refCount == -1) {
            // Unblessed, untracked — nothing to do unless it's a container.
            if (topBase instanceof RuntimeArray arr) {
                // Check if any element is a tracked ref before iterating.
                boolean hasTracked = false;
                for (RuntimeScalar elem : arr.elements) {
                    if (elem != null && (elem.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && elem.value instanceof RuntimeBase eb
                            && (eb.blessId != 0 || eb.refCount >= 0)) {
                        hasTracked = true;
                        break;
                    }
                }
                if (!hasTracked) return;
            } else if (topBase instanceof RuntimeHash hash) {
                boolean hasTracked = false;
                for (RuntimeScalar val : hash.elements.values()) {
                    if (val != null && (val.type & RuntimeScalarType.REFERENCE_BIT) != 0
                            && val.value instanceof RuntimeBase vb
                            && (vb.blessId != 0 || vb.refCount >= 0)) {
                        hasTracked = true;
                        break;
                    }
                }
                if (!hasTracked) return;
            } else {
                return;  // Non-container unblessed untracked leaf — nothing to do
            }
        }

        // Slow path: tracked or blessed referents need full walk with cycle detection.
        // Use an explicit work queue + visited set to avoid stack overflow
        // on circular references (e.g., ExifTool's self-referential hashes).
        ArrayDeque<RuntimeScalar> work = new ArrayDeque<>();
        Set<RuntimeBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        work.add(scalar);

        while (!work.isEmpty()) {
            RuntimeScalar s = work.poll();
            if (s == null || (s.type & RuntimeScalarType.REFERENCE_BIT) == 0) continue;
            if (!(s.value instanceof RuntimeBase base)) continue;
            if (!visited.add(base)) continue;  // already visited — cycle

            if (base.blessId != 0) {
                if (s.refCountOwned && base.refCount > 0) {
                    s.refCountOwned = false;
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDecrementRecursive (blessed, queued)");
                        base.releaseOwner(s, "deferDecrementRecursive blessed");
                    }
                    base.releaseActiveOwner(s);
                    pending.add(base);
                } else if (base.refCount == 0) {
                    if (base.refCountTrace) {
                        base.traceRefCount(+1, "MortalList.deferDecrementRecursive (blessed never-stored bump+queue)");
                    }
                    base.refCount = 1;
                    pending.add(base);
                }
            } else {
                if (s.refCountOwned && base.refCount > 0) {
                    s.refCountOwned = false;
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDecrementRecursive (unblessed container, queued)");
                    }
                    base.releaseActiveOwner(s);
                    pending.add(base);
                }
                // Walk into unblessed containers to find nested blessed refs
                if (base instanceof RuntimeArray arr) {
                    for (RuntimeScalar elem : arr.elements) {
                        if (elem != null) work.add(elem);
                    }
                } else if (base instanceof RuntimeHash hash) {
                    for (RuntimeScalar val : hash.elements.values()) {
                        if (val != null) work.add(val);
                    }
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
                if (base.refCountTrace) {
                    base.traceRefCount(+1, "MortalList.mortalizeForVoidDiscard (refCount=1 bump+queue)");
                }
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
     * <p>
     * Reentrancy guard: flush() can be called recursively when callDestroy()
     * triggers DESTROY → doCallDestroy → scopeExitCleanupHash → flush().
     * Without the guard, the inner flush() re-processes entries from the same
     * pending list that the outer flush is iterating over, causing double
     * decrements and premature destruction (e.g., DBIx::Class Schema clones
     * being destroyed mid-construction, clearing weak refs to still-live
     * objects). With the guard, only the outermost flush() processes entries;
     * new entries added by cascading DESTROY are picked up by the outer
     * loop's continuing iteration (since it checks pending.size() each pass).
     * <p>
     * Also used by {@link RuntimeList#setFromList} to suppress flushing during
     * list assignment materialization. This prevents premature destruction of
     * return values while the caller is still capturing them into variables.
     */
    private static boolean flushing = false;

    /**
     * Suppress or unsuppress flushing. Used by setFromList to prevent pending
     * decrements from earlier scopes (e.g., clone's $self) being processed
     * during the materialization of list assignment (@_ → local vars).
     * Without this, return values from chained method calls like
     * {@code shift->clone->connection(@_)} can be destroyed mid-capture.
     *
     * @return the previous value of the flushing flag (for nesting).
     */
    public static boolean suppressFlush(boolean suppress) {
        boolean prev = flushing;
        flushing = suppress;
        return prev;
    }

    // Phase B2a (refcount_alignment_52leaks_plan.md): throttled
    // auto-sweep of the weak-ref registry, gated by ModuleInitGuard.
    // Runs at statement boundaries (flush points) but skips while
    // inside require/use/do/BEGIN/eval-STRING code paths — those
    // often rely on weak-refed intermediate state that the sweep
    // would prematurely clear.
    private static long lastAutoSweepNanos = 0;
    // Tuned for DBIC-scale tests: 5s throttle. Shorter intervals
    // (100ms, 500ms) fire too frequently — 52leaks.t creates thousands
    // of weaken'd refs and each sweep's System.gc() + weak-ref cascade
    // can run for tens of seconds. 5s gives the walker time to amortize.
    //
    // Trade-off: tests that rely on deterministic DESTROY after `undef`
    // of a blessed ref (e.g. t/storage/error.t test 49) need explicit
    // Internals::jperl_gc() to fire the walker within their short
    // wall-clock.
    private static final long AUTO_SWEEP_MIN_INTERVAL_NS = 5_000_000_000L;
    private static final boolean AUTO_GC_DISABLED =
            System.getenv("JPERL_NO_AUTO_GC") != null;
    private static final boolean AUTO_GC_DEBUG =
            System.getenv("JPERL_GC_DEBUG") != null;
    private static boolean inAutoSweep = false;

    public static void flush() {
        if (!active || pending.isEmpty() || flushing) return;
        flushing = true;
        try {
            // Process list — DESTROY may add new entries, so use index-based loop
            for (int i = 0; i < pending.size(); i++) {
                RuntimeBase base = pending.get(i);
                if (base.refCount > 0) {
                    base.traceRefCount(-1, "MortalList.flush (deferred decrement)");
                }
                if (base.refCount > 0 && --base.refCount == 0) {
                    if (base.localBindingExists) {
                        // Named container: local variable may still exist. Skip callDestroy.
                        // Cleanup will happen at scope exit (scopeExitCleanupHash/Array).
                        //
                        // Do NOT clear weak refs here: localBindingExists=true means
                        // the container is still alive via its lexical slot. Test
                        // op/hashassign.t 218 (bug #76716, "undef %hash should not
                        // zap weak refs") requires that `is $p, \%tb; undef %tb;`
                        // does not zap the weak ref $p to %tb — the `\%tb` inside
                        // `is(...)` triggers a deferred decrement whose refCount
                        // transition 1→0 lands here, but the hash is still alive.
                        // An earlier "Fix 10a" cleared weak refs here for anon-hash
                        // leak-tracing scenarios; those scenarios now use
                        // createAnonymousReference() (localBindingExists stays false)
                        // so the clear is no longer needed and broke #76716.
                    } else if (base.blessId != 0
                            && base.storedInPackageGlobal
                            && WeakRefRegistry.hasWeakRefsTo(base)
                            && ReachabilityWalker.isReachableFromRoots(base)) {
                        // D-W6.18: property-based walker gate.
                        // Replaces the class-name heuristic
                        // (classNeedsWalkerGate). Object's lifetime is
                        // module-global metadata (stored in a package-
                        // global hash like %METAS), so cooperative
                        // refCount transient zeros must not fire DESTROY.
                        // Walker confirms reachability; suppress destroy.
                        // D-W6.16: heuristic walker gate (primary).
                        // The new reachableOwnerCount() infrastructure
                        // (D-W6.14/16) handles Class::MOP/Moose correctly
                        // without needing this heuristic, but DBIC's
                        // row-leak tests still over-rescue when using it
                        // as the only gate. Heuristic stays as primary
                        // until the over-rescue is fixed (D-W6.17).
                    } else {
                        base.refCount = Integer.MIN_VALUE;
                        DestroyDispatch.callDestroy(base);
                    }
                }
            }
            pending.clear();
            marks.clear(); // All entries drained; marks are meaningless now
        } finally {
            flushing = false;
        }
        // Phase B2a: guarded auto-sweep.
        maybeAutoSweep();
    }

    private static void maybeAutoSweep() {
        if (AUTO_GC_DISABLED) return;
        if (inAutoSweep) return;
        if (!WeakRefRegistry.weakRefsExist) return;
        // Phase B2a: skip while require/use/BEGIN/eval-STRING is running.
        // Those paths depend on weak-refed intermediate state staying
        // defined until the init completes.
        if (ModuleInitGuard.inModuleInit()) return;
        long now = System.nanoTime();
        if (now - lastAutoSweepNanos < AUTO_SWEEP_MIN_INTERVAL_NS) return;
        lastAutoSweepNanos = now;
        inAutoSweep = true;
        try {
            // Quiet mode: only clear weak refs for unreachable objects,
            // don't fire DESTROY. DESTROY cascades can re-enter DBIC/
            // Moo code that isn't prepared for mid-statement cleanup.
            // Explicit Internals::jperl_gc() still fires DESTROY for
            // callers that want full cleanup.
            int cleared = ReachabilityWalker.sweepWeakRefs(true);
            if (AUTO_GC_DEBUG) {
                System.err.println("DBG auto-sweep cleared=" + cleared);
            }
        } finally {
            inAutoSweep = false;
        }
    }

    /**
     * Phase 3 (refcount_alignment_plan.md): Return the current pending-queue
     * size. Used by {@link DestroyDispatch#doCallDestroy} to snapshot the
     * pending list before invoking the Perl DESTROY body, so that the
     * entries added during DESTROY can be drained after it returns without
     * waiting for the outer {@link #flush} to run.
     */
    public static int pendingSize() {
        return pending.size();
    }

    /**
     * Phase 3 (refcount_alignment_plan.md): Process pending entries added
     * after a specific checkpoint, regardless of whether an outer
     * {@link #flush} is already running. Used by
     * {@link DestroyDispatch#doCallDestroy} to flush the deferred
     * decrements queued by a DESTROY body (shift @_, $self scope exit)
     * so the post-DESTROY refCount accurately reflects resurrection.
     *
     * @param startIdx the {@link #pendingSize} captured before apply()
     */
    public static void drainPendingSince(int startIdx) {
        if (!active) return;
        if (startIdx < 0) startIdx = 0;
        // Loop because DESTROY may add further entries
        int i = startIdx;
        while (i < pending.size()) {
            RuntimeBase base = pending.get(i);
            i++;
            if (base.refCount > 0 && --base.refCount == 0) {
                if (base.localBindingExists) {
                    WeakRefRegistry.clearWeakRefsTo(base);
                } else {
                    base.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(base);
                }
            }
        }
        // Truncate the pending list back to startIdx to mark these entries
        // as processed. Outer flush won't re-process them.
        while (pending.size() > startIdx) {
            pending.remove(pending.size() - 1);
        }
    }

    /**
     * Push a mark recording the current pending list size.
     * Called before scope-exit cleanup so that popAndFlush() only
     * processes entries added by the cleanup (not earlier entries
     * from outer scopes or prior operations).
     * Also called at function entry (RuntimeCode.apply) to establish
     * a function-scoped mortal boundary — entries from the caller's
     * scope stay below the mark and are not processed by statement-
     * boundary flushes inside the callee.
     * Analogous to Perl 5's SAVETMPS.
     */
    public static void pushMark() {
        if (!active) return;
        marks.add(pending.size());
    }

    /**
     * Pop the most recent mark without flushing.
     * Called at function return to remove the function-scoped boundary.
     * Entries that were above the mark "fall" into the caller's scope
     * and will be processed by the caller's flushAboveMark() at the
     * next statement boundary.
     */
    public static void popMark() {
        if (!active || marks.isEmpty()) return;
        marks.removeLast();
    }

    /**
     * Flush entries above the top mark without popping it.
     * Used at statement boundaries (FREETMPS equivalent) to process
     * deferred decrements from the current function scope only.
     * Entries below the mark (from caller scopes) are untouched,
     * preventing premature DESTROY of method chain temporaries like
     * {@code Foo->new()->method()} where the bless mortal entry
     * must survive until the caller's statement boundary.
     * <p>
     * If no mark exists (top-level code), behaves like {@link #flush()}.
     */
    public static void flushAboveMark() {
        if (!active || pending.isEmpty() || flushing) return;
        int mark = marks.isEmpty() ? 0 : marks.getLast();
        if (pending.size() <= mark) return;
        flushing = true;
        try {
            for (int i = mark; i < pending.size(); i++) {
                RuntimeBase base = pending.get(i);
                if (base.refCount > 0 && --base.refCount == 0) {
                    if (base.localBindingExists) {
                        // Named container: local variable may still exist.
                    } else {
                        base.refCount = Integer.MIN_VALUE;
                        DestroyDispatch.callDestroy(base);
                    }
                }
            }
            // Remove only entries above the mark
            while (pending.size() > mark) {
                pending.removeLast();
            }
        } finally {
            flushing = false;
        }
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
        if (pending.size() <= mark) {
            // Even if no mortal entries to process, check deferred captures
            // that may have become ready (captureCount reached 0) during
            // scope cleanup.
            processReadyDeferredCaptures();
            return;
        }
        // Process entries from mark onwards (DESTROY may add new entries)
        for (int i = mark; i < pending.size(); i++) {
            RuntimeBase base = pending.get(i);
            if (base.refCount > 0 && --base.refCount == 0) {
                if (base.localBindingExists) {
                    // Named container: local variable may still exist. Skip callDestroy.
                } else {
                    base.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(base);
                }
            }
        }
        // Remove only the entries we processed (keep entries before mark)
        while (pending.size() > mark) {
            pending.removeLast();
        }
        // After processing mortals (which may have triggered releaseCaptures
        // via callDestroy), check if any deferred captures are now ready.
        processReadyDeferredCaptures();
    }
}

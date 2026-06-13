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

    // Tied scalar wrappers whose handler release must happen at the next
    // statement boundary. Used when a :lvalue sub returns a tied lexical: the
    // callee's scope exits before the caller performs STORE.
    private static final ArrayList<TiedVariableBase> pendingTiedReleases = new ArrayList<>();

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
    private static boolean deferredCapturesMayBeReady = false;

    private static final ThreadLocal<ArrayDeque<RuntimeBase>> temporaryRoots =
            ThreadLocal.withInitial(ArrayDeque::new);

    public static void pushTemporaryRoot(RuntimeBase root) {
        if (root != null) {
            temporaryRoots.get().push(root);
        }
    }

    public static void popTemporaryRoot(RuntimeBase root) {
        if (root != null) {
            ArrayDeque<RuntimeBase> roots = temporaryRoots.get();
            if (!roots.isEmpty() && roots.peek() == root) {
                roots.pop();
            } else {
                roots.removeFirstOccurrence(root);
            }
        }
    }

    public static java.util.List<RuntimeBase> snapshotTemporaryRoots() {
        return new java.util.ArrayList<>(temporaryRoots.get());
    }

    public static boolean hasTemporaryRoots() {
        return !temporaryRoots.get().isEmpty();
    }

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

    public static void deferTiedObjectRelease(TiedVariableBase tiedVariable) {
        if (!active || tiedVariable == null) return;
        pendingTiedReleases.add(tiedVariable);
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
        if (scalar.captureCount == 0 && scalar.scopeExited) {
            deferredCapturesMayBeReady = true;
        }
    }

    static void noteDeferredCaptureMayBeReady() {
        if (!deferredCaptures.isEmpty()) {
            deferredCapturesMayBeReady = true;
        }
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
        if (!deferredCapturesMayBeReady) return;
        deferredCapturesMayBeReady = false;
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
     * hold JVM references to the RuntimeScalar, but the selective
     * refCount should reflect that the declaring scope is gone.
     */
    public static void flushDeferredCaptures() {
        if (deferredCaptures.isEmpty()) return;
        for (RuntimeScalar scalar : deferredCaptures) {
            deferDecrementIfTracked(scalar);
        }
        deferredCaptures.clear();
        deferredCapturesSet.clear();
        deferredCapturesMayBeReady = false;
        flush();

        // After flushing deferred captures, clear weak refs for objects that
        // were rescued by DESTROY (e.g., Schema::DESTROY self-save pattern).
        // This must happen AFTER the flush above so that all pending refCount
        // decrements have been processed, and BEFORE END blocks run so that
        // DBIC's assert_empty_weakregistry sees the weak refs as undef.
        DestroyDispatch.clearRescuedWeakRefs();

        // Final sweep: clear weak refs for ALL remaining blessed objects.
        // At this point the main script has returned and all lexical scopes
        // have exited. Some objects may still have inflated selective
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
     * Release a scope-exited closure capture. This is normally the same as
     * {@link #deferDecrementIfTracked}, but DBIC's leak tracer can wrap
     * Try::Tiny blocks with {@code goto} and weak refs, making a captured
     * temporary consume the counted owner of package-global metadata. In that
     * case, transfer ownership only when the referent is still reachable from a
     * non-lexical root; stack-local temporaries must release normally so
     * DESTROY fires at lexical scope exit.
     */
    public static void releaseCapturedDecrement(RuntimeScalar scalar) {
        if (!active || scalar == null) return;
        if (!scalar.refCountOwned) return;
        if ((scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base
                && base.blessId != 0
                && WeakRefRegistry.hasWeakRefsTo(base)
                && isReachableFromNonLexicalRootForCaptureRelease(base)) {
            scalar.refCountOwned = false;
            if (base.refCountTrace) {
                base.traceRefCount(0, "MortalList.releaseCapturedDecrement (transferred to live scalar)");
                base.releaseOwner(scalar, "releaseCapturedDecrement transfer");
            }
            base.releaseActiveOwner(scalar);
            return;
        }
        deferDecrementIfTracked(scalar);
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
        boolean hadLocalBinding = hash.localBindingExists;
        hash.localBindingExists = false;
        if (hash.captureCount > 0) {
            hash.scopeExited = true;
            return;
        }
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
        if (hash.type == RuntimeHash.TIED_HASH && hash.elements instanceof TieHash tieHash) {
            tieHash.releaseTiedObject();
        }
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
        // Refcount drift can miss an escaped hash reference. DBIC's
        // source_registrations hash is assigned into the live schema while the
        // original hash later exits; walking it here would destroy ResultSources
        // still reachable through the schema.
        if (hadLocalBinding
                && hash.refCount >= 0
                && hash.hadCountedReference
                && isReachableFromExternalRootCached(hash)) return;
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
        if (arr.captureCount > 0) {
            arr.scopeExited = true;
            return;
        }
        // Skip container walks only when there are NO blessed objects AND NO
        // weak refs anywhere in the JVM (see scopeExitCleanupHash for details).
        if (!RuntimeBase.blessedObjectExists && !WeakRefRegistry.weakRefsExist) return;
        // If the array has outstanding references (e.g., from \@array stored elsewhere),
        // do NOT clean up elements — the array is still alive and its elements are
        // accessible through the reference. Cleanup will happen when the last
        // reference is released (in DestroyDispatch.callDestroy).
        if (arr.refCount > 0) return;
        if (arr.type == RuntimeArray.TIED_ARRAY && arr.elements instanceof TieArray tieArray) {
            tieArray.releaseTiedObject();
        }
        // Alias arrays such as @_ do not own their original elements. They can
        // still receive owned inserts via unshift/push before a goto &sub; in
        // that mixed case, release only those inserted elements.
        if (arr.elementsAliased) {
            if (arr.ownedAliasElements == null || arr.ownedAliasElements.isEmpty()) return;
            RuntimeScalar[] owned = arr.ownedAliasElements.toArray(new RuntimeScalar[0]);
            for (RuntimeScalar elem : owned) {
                deferDecrementRecursive(elem);
                arr.forgetOwnedAliasElement(elem);
            }
            return;
        }
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

    public static void releaseTailCallArgs(RuntimeArray args) {
        if (args == null || !active || !args.elementsOwned) return;
        if (args.elementsAliased) {
            if (args.ownedAliasElements == null || args.ownedAliasElements.isEmpty()) return;
            RuntimeScalar[] owned = args.ownedAliasElements.toArray(new RuntimeScalar[0]);
            for (RuntimeScalar elem : owned) {
                releaseTailCallArgElement(elem);
                args.forgetOwnedAliasElement(elem);
            }
            return;
        }
        for (RuntimeScalar elem : args.elements) {
            releaseTailCallArgElement(elem);
        }
        args.elementsOwned = false;
    }

    public static void releaseTailCallCodeRef(RuntimeScalar codeRef) {
        if (codeRef == null
                || !active
                || !codeRef.refCountOwned
                || codeRef.type != RuntimeScalarType.CODE
                || !(codeRef.value instanceof RuntimeCode code)
                || code.refCount <= 0) {
            return;
        }

        codeRef.refCountOwned = false;
        if (code.refCountTrace) {
            code.traceRefCount(-1, "MortalList.releaseTailCallCodeRef");
            code.releaseOwner(codeRef, "releaseTailCallCodeRef");
        }
        code.releaseActiveOwner(codeRef);

        if (--code.refCount > 0) return;

        if (code.stashRefCount > 0) {
            code.refCount = 0;
            return;
        }

        if (code.localBindingExists) {
            code.refCount = 1;
            return;
        }

        code.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(code);
    }

    private static void releaseTailCallArgElement(RuntimeScalar scalar) {
        if (scalar == null
                || !scalar.refCountOwned
                || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                || !(scalar.value instanceof RuntimeBase base)
                || base.refCount <= 0) {
            return;
        }

        scalar.refCountOwned = false;
        if (base.refCountTrace) {
            base.traceRefCount(-1, "MortalList.releaseTailCallArgElement");
            base.releaseOwner(scalar, "releaseTailCallArgElement");
        }
        base.releaseActiveOwner(scalar);

        if (--base.refCount > 0) return;

        if (base.localBindingExists || reachableTailCallArgReferent(base)) {
            base.refCount = 1;
            return;
        }

        base.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(base);
    }

    private static boolean reachableTailCallArgReferent(RuntimeBase base) {
        return WeakRefRegistry.hasWeakRefsTo(base)
                && (ReachabilityWalker.isReachableFromRoots(base)
                || ReachabilityWalker.isReachableFromLiveScalarRegistry(base)
                || ReachabilityWalker.isReachableFromLiveCodeCaptures(base));
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
                boolean hasDirectWeakElementRefs = containerHasWeakElementRefs(base);
                boolean hasCleanupTargets = hasDirectWeakElementRefs
                        || containerMayContainCleanupTargets(base);
                if (s.refCountOwned && base.refCount > 0) {
                    s.refCountOwned = false;
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.deferDecrementRecursive (unblessed container, queued)");
                    }
                    base.releaseActiveOwner(s);
                    pending.add(base);
                    if (!WeakRefRegistry.weakRefsExist && base.refCount > 1) {
                        continue;
                    }
                    if (!hasCleanupTargets
                            || base.activeOwnerCount() > 0
                            || (base.refCount > 1
                            && !hasDirectWeakElementRefs
                            && isReachableFromExternalRootCached(base))) {
                        continue;
                    }
                } else if (base.refCount > 0
                        && !WeakRefRegistry.weakRefsExist) {
                    // A non-owning copy of a still-counted unblessed container
                    // must not release that container's children. With no weak
                    // refs anywhere, there is no weak-ref cleanup that requires
                    // peeking inside before the container's own count reaches 0.
                    continue;
                } else if (base.refCount > 0
                        && (!hasCleanupTargets
                        || base.activeOwnerCount() > 0
                        || (!hasDirectWeakElementRefs
                        && isReachableFromExternalRootCached(base)))) {
                    // This scalar is a non-owning copy of a still-live container.
                    // Cleaning it up must not walk into the shared container and
                    // release the original owner's children.
                    continue;
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

    static boolean containerMayContainCleanupTargets(RuntimeBase base) {
        if (!(base instanceof RuntimeArray || base instanceof RuntimeHash)) return false;
        ArrayDeque<RuntimeBase> work = new ArrayDeque<>();
        Set<RuntimeBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        work.add(base);

        while (!work.isEmpty()) {
            RuntimeBase cur = work.poll();
            if (cur == null || !visited.add(cur)) continue;
            if (cur instanceof RuntimeArray arr) {
                for (RuntimeScalar elem : arr.elements) {
                    if (scalarMayContainCleanupTarget(elem, work)) return true;
                }
            } else if (cur instanceof RuntimeHash hash) {
                for (RuntimeScalar value : hash.elements.values()) {
                    if (scalarMayContainCleanupTarget(value, work)) return true;
                }
            }
        }
        return false;
    }

    private static boolean scalarMayContainCleanupTarget(RuntimeScalar scalar,
                                                        ArrayDeque<RuntimeBase> work) {
        if (scalar == null) return false;
        if (WeakRefRegistry.hasWeakRefsTo(scalar)) return true;
        if ((scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0) return false;
        if (!(scalar.value instanceof RuntimeBase base)) return false;
        if (WeakRefRegistry.hasWeakRefsTo(base)) return true;
        if (base.blessId != 0) return true;
        if (base instanceof RuntimeArray || base instanceof RuntimeHash) {
            work.add(base);
        } else if (base instanceof RuntimeCode && base.refCount >= 0) {
            return true;
        }
        return false;
    }

    private static boolean containerHasWeakElementRefs(RuntimeBase base) {
        if (!WeakRefRegistry.weakRefsExist) return false;
        if (base instanceof RuntimeArray arr) {
            for (RuntimeScalar elem : arr.elements) {
                if (elem != null && WeakRefRegistry.hasWeakRefsTo(elem)) {
                    return true;
                }
            }
        } else if (base instanceof RuntimeHash hash) {
            for (RuntimeScalar value : hash.elements.values()) {
                if (value != null && WeakRefRegistry.hasWeakRefsTo(value)) {
                    return true;
                }
            }
        }
        return false;
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
                    && scalar.value instanceof RuntimeBase base) {
                boolean unblessedDiscardableContainer = base.blessId == 0
                        && (base instanceof RuntimeHash
                        || base instanceof RuntimeArray
                        || base instanceof RuntimeCode);
                boolean reachableCodeRef = base instanceof RuntimeCode
                        && scalar.isStoredInRegisteredContainerOwner()
                        && ReachabilityWalker.isScalarReachable(scalar);
                if (scalar.refCountOwned && base.refCount > 0 && unblessedDiscardableContainer
                        && !reachableCodeRef) {
                    scalar.refCountOwned = false;
                    if (base.refCountTrace) {
                        base.traceRefCount(0, "MortalList.mortalizeForVoidDiscard (queued owned container discard)");
                        base.releaseOwner(scalar, "mortalizeForVoidDiscard container");
                    }
                    base.releaseActiveOwner(scalar);
                    pending.add(base);
                } else if (base.refCount == 0
                        && (base.blessId != 0
                        || base instanceof RuntimeHash
                        || base instanceof RuntimeArray
                        || base instanceof RuntimeCode)) {
                    if (base.refCountTrace) {
                        base.traceRefCount(+1, "MortalList.mortalizeForVoidDiscard (refCount=1 bump+queue)");
                    }
                    base.refCount = 1;
                    pending.add(base);
                }
            }
        }
    }

    // Mark stack for scoped flushing (analogous to Perl 5's SAVETMPS).
    // Each mark records the pending list size at scope entry, so that
    // popAndFlush() only processes entries added within that scope.
    private static final ArrayList<Integer> marks = new ArrayList<>();
    private static final ArrayList<Integer> tiedReleaseMarks = new ArrayList<>();

    private static void processDeferredEntriesFrom(int pendingStartIdx, int tiedReleaseStartIdx) {
        int pendingIdx = pendingStartIdx;
        int tiedReleaseIdx = tiedReleaseStartIdx;
        while (pendingIdx < pending.size() || tiedReleaseIdx < pendingTiedReleases.size()) {
            while (tiedReleaseIdx < pendingTiedReleases.size()) {
                pendingTiedReleases.get(tiedReleaseIdx++).releaseTiedObject();
            }
            while (pendingIdx < pending.size()) {
                processDeferredBase(pending.get(pendingIdx++), false);
            }
        }
    }

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
    // Phase D-W6.20 (debug knob): force the auto-sweep on EVERY flush()
    // call, bypassing the 5-s throttle and the `weakRefsExist` gate.
    // Used to reproduce timing-dependent walker bugs (e.g. the
    // ScalarRefRegistry-vs-forceGc race that surfaces as DBIC's
    // "detached result source" mid-test crash). Off by default; when on,
    // every Perl statement boundary triggers a full sweepWeakRefs walk —
    // very slow, only for diagnostics.
    private static final boolean FORCE_SWEEP_EVERY_FLUSH =
            System.getenv("JPERL_FORCE_SWEEP_EVERY_FLUSH") != null;
    private static boolean inAutoSweep = false;
    private static boolean immediateWeakSweepRequested = false;

    public static void requestImmediateWeakSweep() {
        immediateWeakSweepRequested = true;
    }

    // D-W6.18 perf: cached reachable-set, valid for the duration of a
    // single flush() invocation. The walker BFS is O(globals); without
    // this cache, calling it once per pending target turns flush into
    // O(targets × globals). DBIC stresses this hard because many
    // blessed objects are stored-in-package-global AND have weak refs
    // (Schema/ResultSource back-refs) — every flush would re-walk the
    // full global graph for each one. Computed lazily on first need.
    private static Set<RuntimeBase> flushReachableCache = null;
    private static ReachabilityWalker.ExternalRootSnapshot externalRootSnapshot = null;
    private static ReachabilityWalker.LiveRootSnapshot liveRootSnapshot = null;

    private static boolean isReachableCached(RuntimeBase base) {
        if (flushReachableCache == null) {
            ReachabilityWalker w = new ReachabilityWalker();
            flushReachableCache = w.walk();
        }
        return flushReachableCache.contains(base);
    }

    private static boolean isReachableFromExternalRootCached(RuntimeBase base) {
        if (ReachabilityWalker.isReachableFromTemporaryRoots(base)) {
            return true;
        }
        if (externalRootSnapshot == null) {
            externalRootSnapshot = new ReachabilityWalker.ExternalRootSnapshot();
        }
        if (externalRootSnapshot.isReachableFromNonLexicalRoot(base)) {
            return true;
        }
        if (liveRootSnapshot == null) {
            liveRootSnapshot = new ReachabilityWalker.LiveRootSnapshot();
        }
        return liveRootSnapshot.isReachable(base);
    }

    static void invalidateExternalRootSnapshot() {
        externalRootSnapshot = null;
    }

    static void invalidateAllRootSnapshots() {
        invalidateExternalRootSnapshot();
        invalidateLiveRootSnapshot();
    }

    static void invalidateLiveRootSnapshot() {
        liveRootSnapshot = null;
    }

    private static void invalidateDrainReachabilityCaches() {
        flushReachableCache = null;
        invalidateLiveRootSnapshot();
    }

    private static boolean isReachableFromNonLexicalRootForCaptureRelease(RuntimeBase base) {
        if (ReachabilityWalker.isReachableFromTemporaryRoots(base)) {
            return true;
        }
        if (externalRootSnapshot == null) {
            externalRootSnapshot = new ReachabilityWalker.ExternalRootSnapshot();
        }
        return externalRootSnapshot.isReachableFromNonLexicalRoot(base);
    }

    private static void processDeferredBase(RuntimeBase base, boolean clearWeakRefsForLocalBinding) {
        boolean hasWeakRefs = WeakRefRegistry.hasWeakRefsTo(base);
        if (base.refCount > 0) {
            base.traceRefCount(-1, "MortalList.flush (deferred decrement)");
        }
        if (base.refCount > 0 && --base.refCount == 0) {
            if (base.localBindingExists) {
                // Named container: local variable may still exist. Skip callDestroy.
                // Cleanup will happen at scope exit (scopeExitCleanupHash/Array).
                //
                // Do NOT clear weak refs here for normal flush paths:
                // localBindingExists=true means the container is still alive via
                // its lexical slot. Test op/hashassign.t 218 (bug #76716,
                // "undef %hash should not zap weak refs") requires that
                // `is $p, \%tb; undef %tb;` does not zap the weak ref $p to %tb.
                if (clearWeakRefsForLocalBinding) {
                    WeakRefRegistry.clearWeakRefsTo(base);
                }
            } else if (base instanceof RuntimeCode code
                    && hasWeakRefs
                    && (RuntimeCode.isActiveCode(code)
                    || (!code.hadStashRef && RuntimeCode.argsStackDepth() > 1))) {
                // Sub::Defer stores a weak self-reference in the deferred
                // CODE's captured info array. Selective refcounts can reach
                // zero while the wrapper is still executing or nested
                // generation is wiring Moo metadata. Clearing then makes
                // nested dispatch call undefer_sub(undef).
                base.refCount = 1;
            } else if (base.blessId == 0
                    && hasWeakRefs
                    && (ReachabilityWalker.isReachableFromLiveCodeCaptures(base)
                    || ReachabilityWalker.isReachableFromGlobalCodeCaptures(base))) {
                // Sub::Defer/Sub::Quote keep metadata arrays/hashes alive
                // through captures in a live CODE ref while storing only weak
                // registry entries. Selective refcounts can transiently reach
                // zero before the caller has finished using the returned CODE.
                base.refCount = 1;
            } else if (base.blessId == 0
                    && hasWeakRefs
                    && ReachabilityWalker.hasStrongCycle(base)) {
                // Unblessed self-retaining cycles are intentionally leaked by
                // Perl's refcounting. AnyEvent timers use this shape: the weak
                // timer queue points at an array whose callback closes over the
                // scalar holding that same array.
                base.refCount = 1;
            } else if (base.blessId != 0
                    && hasWeakRefs
                    && !blessedClassHasDestroy(base)
                    && (RuntimeCode.argsStackDepth() > 1
                    || isReachableFromExternalRootCached(base)
                    || ReachabilityWalker.isReachableFromRoots(base))) {
                // A weakened probe copy can make the selective count reach
                // zero while an ordinary blessed object is still held by a
                // live lexical. Test::Refcount exercises this shape; clearing
                // weak refs here drops callback invocants that should remain
                // valid. Classes with DESTROY keep the stricter path below.
                base.refCount = 1;
            } else if (base.blessId != 0
                    && base.storedInPackageGlobal
                    && hasWeakRefs
                    && isReachableCached(base)) {
                // Module-global metadata such as Moose/Class::MOP metaclasses
                // can transiently hit zero in the selective refcount model.
                // If the walker can still reach the object from Perl-visible
                // roots, keep weak links to it intact.
            } else {
                base.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(base);
            }
        }
    }

    private static boolean blessedClassHasDestroy(RuntimeBase base) {
        String className = NameNormalizer.getBlessStr(base.blessId);
        return className != null && DestroyDispatch.classHasDestroy(base.blessId, className);
    }

    public static void flush() {
        if (!active) return;
        if (flushing) return;
        if (pending.isEmpty() && pendingTiedReleases.isEmpty()) {
            processReadyDeferredCaptures();
            maybeAutoSweepAfterFlush();
            return;
        }
        invalidateDrainReachabilityCaches();
        flushing = true;
        try {
            processDeferredEntriesFrom(0, 0);
            pending.clear();
            pendingTiedReleases.clear();
            marks.clear(); // All entries drained; marks are meaningless now
            tiedReleaseMarks.clear();
        } finally {
            flushing = false;
            invalidateDrainReachabilityCaches();
        }
        processReadyDeferredCaptures();
        maybeAutoSweepAfterFlush();
    }

    private static void maybeAutoSweep() {
        if (AUTO_GC_DISABLED) return;
        if (inAutoSweep) return;
        boolean immediateSweep = immediateWeakSweepRequested;
        // FORCE_SWEEP_EVERY_FLUSH bypasses the weakRefsExist gate AND the
        // 5-s throttle so reproducers can deterministically trigger the
        // walker at every statement boundary.
        if (!FORCE_SWEEP_EVERY_FLUSH && !WeakRefRegistry.weakRefsExist && !immediateSweep) return;
        // Phase B2a: skip while require/use/BEGIN/eval-STRING is running.
        // Those paths depend on weak-refed intermediate state staying
        // defined until the init completes.
        if (ModuleInitGuard.inModuleInit()) return;
        if (RuntimeCode.argsStackDepth() > 1) return;
        if (hasTemporaryRoots()) return;
        if (!FORCE_SWEEP_EVERY_FLUSH && !immediateSweep) {
            long now = System.nanoTime();
            if (now - lastAutoSweepNanos < AUTO_SWEEP_MIN_INTERVAL_NS) return;
            lastAutoSweepNanos = now;
        }
        inAutoSweep = true;
        try {
            if (immediateSweep) {
                immediateWeakSweepRequested = false;
                lastAutoSweepNanos = System.nanoTime();
            }
            // Quiet auto-sweeps run from normal statement-boundary checks.
            // Do not force HotSpot GC here: current live-lexical tracking is
            // driven by MyVarCleanupStack, and forcing JVM GC at this cadence
            // dominates DBIC-scale runtimes. Keep the old forced behavior only
            // for the diagnostic "sweep every flush" mode.
            int cleared = ReachabilityWalker.sweepWeakRefs(true, FORCE_SWEEP_EVERY_FLUSH);
            if (AUTO_GC_DEBUG) {
                System.err.println("DBG auto-sweep cleared=" + cleared);
            }
        } finally {
            inAutoSweep = false;
        }
    }

    private static void maybeAutoSweepAfterFlush() {
        maybeAutoSweep();
    }

    private static void maybeAutoSweepIfRequested() {
        if (immediateWeakSweepRequested) {
            maybeAutoSweep();
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
        if (startIdx >= pending.size()) return;
        invalidateDrainReachabilityCaches();
        // Loop because DESTROY may add further entries
        int i = startIdx;
        try {
            while (i < pending.size()) {
                processDeferredBase(pending.get(i), true);
                i++;
            }
        } finally {
            invalidateDrainReachabilityCaches();
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
        tiedReleaseMarks.add(pendingTiedReleases.size());
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
        if (!tiedReleaseMarks.isEmpty()) {
            tiedReleaseMarks.removeLast();
        }
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
        if (!active) return;
        if (flushing) return;
        boolean topLevel = marks.isEmpty();
        if (pending.isEmpty() && pendingTiedReleases.isEmpty()) {
            processReadyDeferredCaptures();
            if (topLevel) {
                maybeAutoSweep();
            }
            return;
        }
        int mark = marks.isEmpty() ? 0 : marks.getLast();
        int tiedMark = tiedReleaseMarks.isEmpty() ? 0 : tiedReleaseMarks.getLast();
        if (pending.size() <= mark && pendingTiedReleases.size() <= tiedMark) {
            processReadyDeferredCaptures();
            if (topLevel) {
                maybeAutoSweep();
            }
            return;
        }
        invalidateDrainReachabilityCaches();
        flushing = true;
        try {
            processDeferredEntriesFrom(mark, tiedMark);
            // Remove only entries above the mark
            while (pending.size() > mark) {
                pending.removeLast();
            }
            while (pendingTiedReleases.size() > tiedMark) {
                pendingTiedReleases.removeLast();
            }
        } finally {
            flushing = false;
            invalidateDrainReachabilityCaches();
        }
        processReadyDeferredCaptures();
        if (topLevel) {
            maybeAutoSweep();
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
        int tiedMark = tiedReleaseMarks.isEmpty() ? 0 : tiedReleaseMarks.removeLast();
        if (pending.size() <= mark && pendingTiedReleases.size() <= tiedMark) {
            // Even if no mortal entries to process, check deferred captures
            // that may have become ready (captureCount reached 0) during
            // scope cleanup.
            processReadyDeferredCaptures();
            maybeAutoSweepIfRequested();
            return;
        }
        invalidateDrainReachabilityCaches();
        // Process entries from mark onwards (DESTROY may add new entries)
        processDeferredEntriesFrom(mark, tiedMark);
        // Remove only the entries we processed (keep entries before mark)
        while (pending.size() > mark) {
            pending.removeLast();
        }
        while (pendingTiedReleases.size() > tiedMark) {
            pendingTiedReleases.removeLast();
        }
        invalidateDrainReachabilityCaches();
        // After processing mortals (which may have triggered releaseCaptures
        // via callDestroy), check if any deferred captures are now ready.
        processReadyDeferredCaptures();
        maybeAutoSweepIfRequested();
    }
}

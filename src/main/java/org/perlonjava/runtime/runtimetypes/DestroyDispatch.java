package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.WarnDie;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central DESTROY dispatch logic for blessed objects.
 * <p>
 * Handles:
 * - Checking whether a class defines DESTROY (or AUTOLOAD)
 * - Caching DESTROY method lookups per blessId
 * - Calling DESTROY with correct Perl semantics (exception → warning, save/restore globals)
 * - Cache invalidation when @ISA changes or methods are redefined
 */
public class DestroyDispatch {

    /** Phase D-W6 debug: enable destroy tracing via -Dperlonjava.destroyTrace=1
     *  or env PJ_DESTROY_TRACE=1. */
    private static final boolean DESTROY_TRACE =
            "1".equals(System.getProperty("perlonjava.destroyTrace"))
            || "1".equals(System.getenv("PJ_DESTROY_TRACE"));

    // BitSet indexed by |blessId| — set if the class defines DESTROY (or AUTOLOAD)
    private static final BitSet destroyClasses = new BitSet();

    // Cache of resolved DESTROY methods per blessId (avoids hierarchy traversal on every call)
    private static final ConcurrentHashMap<Integer, RuntimeScalar> destroyMethodCache =
            new ConcurrentHashMap<>();

    // DESTROY rescue detection: when DESTROY stores $self in a hash element,
    // the object should survive (like Perl 5's Schema::DESTROY self-save pattern).
    // These fields track the current DESTROY target so RuntimeHash.put can detect
    // when the referent is being "rescued" by storing it elsewhere.
    static volatile RuntimeBase currentDestroyTarget = null;
    static volatile boolean destroyTargetRescued = false;

    // Phase D: sweep-pending flag. Set by RuntimeScalar.set() when it
    // releases a blessed-with-DESTROY ref whose refCount stays > 0
    // (cyclic) *while inside* a DESTROY body. Drained by doCallDestroy's
    // outermost finally: if set, fire the reachability walker once to
    // catch any newly-orphaned subgraphs that would otherwise keep weak
    // refs defined past their owners' lives. Amortizes what would
    // otherwise be a sweep on every set() — only the outermost DESTROY
    // pays the cost.
    static boolean sweepPendingAfterOuterDestroy = false;

    public static boolean isInsideDestroy() {
        return currentDestroyTarget != null;
    }

    // Rescued objects whose weak refs need deferred clearing.
    // We cannot clear weak refs immediately after rescue because that would also
    // clear back-references from sibling objects (e.g., $source->{schema}) that
    // are still needed during the test. Instead, we collect rescued objects here
    // and clear their weak refs (with a deep sweep into nested blessed objects)
    // just before END blocks run, when all test code has finished and the
    // back-references are no longer needed.
    private static final java.util.List<RuntimeBase> rescuedObjects =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Phase 4 (refcount_alignment_plan.md): Snapshot the rescued-objects
     * list for use by {@link ReachabilityWalker}. Rescued objects (the
     * result of Schema-style DESTROY self-save) are roots of the live
     * graph even though they've "already fired" DESTROY.
     */
    public static java.util.List<RuntimeBase> snapshotRescuedForWalk() {
        synchronized (rescuedObjects) {
            return new java.util.ArrayList<>(rescuedObjects);
        }
    }

    /**
     * Check whether the class identified by blessId defines DESTROY (or AUTOLOAD).
     * Result is cached in the destroyClasses BitSet.
     *
     * @param blessId   the numeric class identity (from NameNormalizer.getBlessId)
     * @param className the Perl class name
     * @return true if DESTROY (or AUTOLOAD) is defined in the class hierarchy
     */
    /**
     * Phase D-W2c: walker-gated destroy is restricted to known-needed
     * class hierarchies (Class::MOP and Moose / Moo). The gate is
     * essential for those modules' bootstrap (their metaclasses and
     * %METAS rely on transient refCount drift being absorbed by the
     * walker), but it actively breaks DBIC's lazy-cache pattern and
     * other CDBI / DBIx::Class flows where rows are MEANT to be
     * destroyed at refCount=0 even when stack-local my-vars
     * transiently reference them.
     *
     * The gate applies if and only if the class is in the
     * Class::MOP / Moose family. The check is fast: a per-blessId
     * BitSet lookup after the first miss-and-resolve.
     *
     * Patterns outside this family (e.g. user weak-ref cycles
     * documented in dev/sandbox/walker_gate_dbic_minimal.t) do NOT
     * get the gate; they were already broken on master and need a
     * separate fix path.
     */
    private static final java.util.BitSet walkerGateClasses = new java.util.BitSet();
    private static final java.util.BitSet walkerGateChecked = new java.util.BitSet();

    public static boolean classNeedsWalkerGate(int blessId) {
        int idx = Math.abs(blessId);
        if (walkerGateChecked.get(idx)) return walkerGateClasses.get(idx);
        String cn = NameNormalizer.getBlessStr(blessId);
        boolean needs = cn != null && (
                cn.startsWith("Class::MOP")
             || cn.startsWith("Moose::")
             || cn.equals("Moose")
             || cn.startsWith("Moo::")
             || cn.equals("Moo")
        );
        walkerGateChecked.set(idx);
        if (needs) walkerGateClasses.set(idx);
        return needs;
    }

    public static boolean classHasDestroy(int blessId, String className) {
        int idx = Math.abs(blessId);
        if (destroyClasses.get(idx)) return true;
        // First time for this class — check hierarchy.
        // findMethodInHierarchy already falls through to AUTOLOAD if no explicit DESTROY exists.
        RuntimeScalar m = InheritanceResolver.findMethodInHierarchy("DESTROY", className, null, 0);
        if (m != null) {
            destroyClasses.set(idx);
            // Activate the mortal mechanism now that we know DESTROY classes exist
            MortalList.active = true;
            return true;
        }
        return false;
    }

    /**
     * Called when inheritance changes (@ISA modified, methods redefined).
     * Clears both the destroyClasses BitSet and the DESTROY method cache.
     */
    public static void invalidateCache() {
        destroyClasses.clear();
        destroyMethodCache.clear();
    }

    /**
     * Call DESTROY on a referent whose refCount has reached 0.
     * The caller MUST have already set refCount to Integer.MIN_VALUE.
     *
     * @param referent the RuntimeBase object to destroy
     */
    public static void callDestroy(RuntimeBase referent) {
        // refCount is already MIN_VALUE (set by caller)

        // Phase D-W6 debug: optional trace of every destroy call.
        // Enable with -Dperlonjava.destroyTrace=1 (or env PJ_DESTROY_TRACE=1)
        // to find refCount-drift sources.
        if (DESTROY_TRACE) {
            String klass = referent.blessId != 0
                    ? NameNormalizer.getBlessStr(referent.blessId)
                    : referent.getClass().getSimpleName();
            String extra = "";
            if (referent instanceof RuntimeCode rc) {
                extra = " name=" + (rc.packageName != null ? rc.packageName : "?")
                        + "::" + (rc.subName != null ? rc.subName : "(anon)");
            }
            System.err.println("[DESTROY] " + klass + "@"
                    + System.identityHashCode(referent)
                    + " refCount=" + referent.refCount + extra);
            new RuntimeException("destroy trace").printStackTrace(System.err);
        }

        // Phase 3 (refcount_alignment_plan.md): Re-entry guard.
        // If this object is already inside its own DESTROY body, a transient
        // decrement-to-0 (local temp release, deferred MortalList flush,
        // @DB::args replacement across caller() calls) brought us back here.
        // Restore refCount to 0 so subsequent stores inside the ongoing
        // DESTROY can still track references, then return. The outer
        // doCallDestroy will handle final cleanup based on refCount when
        // its Perl body returns.
        if (referent.currentlyDestroying) {
            if (referent.refCount == Integer.MIN_VALUE) {
                referent.refCount = 0;
            }
            return;
        }

        // Phase 3 (refcount_alignment_plan.md): Resurrection re-fire.
        // If a prior DESTROY left refCount > 0 (object resurrected by a
        // strong ref escaping DESTROY), the caller set MIN_VALUE only after
        // the later decrement brought refCount back to 0. Re-invoke the
        // Perl DESTROY now that the resurrected ref has been released.
        // Matches Perl 5's behavior of calling DESTROY multiple times for
        // resurrected objects (DBIC detected_reinvoked_destructor pattern).
        if (referent.destroyFired && referent.needsReDestroy) {
            referent.needsReDestroy = false;
            String cn = NameNormalizer.getBlessStr(referent.blessId);
            if (cn != null && !cn.isEmpty()) {
                doCallDestroy(referent, cn);
                return;
            }
            // Unblessed (rare): fall through to cleanup
        }

        // Perl 5 semantics: DESTROY CAN be called multiple times for resurrected
        // objects. However, in PerlOnJava, cooperative refCount inflation means
        // rescue detection fires more broadly than in Perl 5, so we keep
        // destroyFired=true after rescue to prevent infinite loops.
        // The destroyFired flag acts as a one-shot guard: once DESTROY has fired,
        // subsequent callDestroy invocations just do cleanup (weak ref clearing +
        // cascade) without re-calling the Perl DESTROY method.
        if (referent.destroyFired) {
            // If this object was rescued by DESTROY (e.g., Schema::DESTROY self-save)
            // and is still in the rescuedObjects list, skip cleanup entirely. The weak
            // refs and internal fields must remain intact because the phantom chain
            // (or other code) may still access the object through its weak refs.
            // Proper cleanup happens at END time via clearRescuedWeakRefs.
            if (rescuedObjects.contains(referent)) {
                return;
            }
            WeakRefRegistry.clearWeakRefsTo(referent);
            if (referent instanceof RuntimeHash hash) {
                MortalList.scopeExitCleanupHash(hash);
                MortalList.flush();
            } else if (referent instanceof RuntimeArray arr) {
                MortalList.scopeExitCleanupArray(arr);
                MortalList.flush();
            }
            return;
        }

        // Release closure captures when a CODE ref's refCount hits 0.
        // This allows captured variables to be properly cleaned up
        // (e.g., blessed objects in captured scalars can fire DESTROY).
        // However, skip releaseCaptures if the CODE ref is still installed in the
        // stash (stashRefCount > 0). The cooperative refCount can falsely reach 0
        // for stash-installed closures because glob assignments, closure captures,
        // and other JVM-level references aren't always counted. Releasing captures
        // prematurely would cascade to clear weak references (e.g., in Sub::Defer's
        // %DEFERRED hash), causing infinite recursion in Moo/DBIx::Class.
        if (referent instanceof RuntimeCode code) {
            if (code.stashRefCount <= 0) {
                code.releaseCaptures();
            }
        }

        String className = NameNormalizer.getBlessStr(referent.blessId);
        if (className == null || className.isEmpty()) {
            // Unblessed object — clear weak refs immediately and cascade into elements
            // to decrement refCounts of any tracked references they hold.
            WeakRefRegistry.clearWeakRefsTo(referent);
            if (referent instanceof RuntimeHash hash) {
                MortalList.scopeExitCleanupHash(hash);
            } else if (referent instanceof RuntimeArray arr) {
                MortalList.scopeExitCleanupArray(arr);
            }
            return;
        }

        // Blessed object — DEFER clearWeakRefsTo until after DESTROY.
        // In Perl 5, weak references are only cleared after DESTROY if the object
        // was NOT resurrected. Schema::DESTROY relies on $source->{schema} (a weak
        // ref to the Schema) still being alive during DESTROY so it can find a
        // source with refcount > 1 and re-attach. Clearing weak refs before DESTROY
        // would break this self-save pattern.
        doCallDestroy(referent, className);
    }

    /**
     * Perform the actual DESTROY method call.
     */
    private static void doCallDestroy(RuntimeBase referent, String className) {
        // Mark as destroyed before running DESTROY — one-shot guard.
        // Prevents re-entrant DESTROY if cascading cleanup brings this
        // object's refCount to 0 again within the same call stack.
        // Also prevents infinite DESTROY loops for rescued objects
        // (destroyFired stays true after rescue — see note in rescue path).
        referent.destroyFired = true;

        // Use cached method if available
        RuntimeScalar destroyMethod = destroyMethodCache.get(referent.blessId);
        if (destroyMethod == null) {
            destroyMethod = InheritanceResolver.findMethodInHierarchy(
                    "DESTROY", className, null, 0);
            if (destroyMethod != null) {
                destroyMethodCache.put(referent.blessId, destroyMethod);
            }
        }

        if (destroyMethod == null || destroyMethod.type != RuntimeScalarType.CODE) {
            // No DESTROY method — clear weak refs and cascade cleanup into elements
            // to decrement refCounts of any tracked references they hold.
            // Without this, blessed objects without DESTROY (e.g., Moo objects like
            // DBIx::Class::Storage::BlockRunner) leak their contained references.
            WeakRefRegistry.clearWeakRefsTo(referent);
            if (referent instanceof RuntimeHash hash) {
                MortalList.scopeExitCleanupHash(hash);
                MortalList.flush();
            } else if (referent instanceof RuntimeArray arr) {
                MortalList.scopeExitCleanupArray(arr);
                MortalList.flush();
            }
            return;
        }

        // If findMethodInHierarchy returned an AUTOLOAD sub (because no explicit DESTROY
        // exists), we need to set $AUTOLOAD before calling it. The method resolver sets
        // autoloadVariableName on the RuntimeCode when it falls through to the AUTOLOAD pass.
        RuntimeCode code = (RuntimeCode) destroyMethod.value;
        if (code.autoloadVariableName != null) {
            String fullMethodName = className + "::DESTROY";
            GlobalVariable.getGlobalVariable(code.autoloadVariableName).set(fullMethodName);
        }

        // Perl requires: local($@) around DESTROY — save before try so it
        // is restored even when DESTROY throws (die inside DESTROY).
        RuntimeScalar savedDollarAt = new RuntimeScalar();
        RuntimeScalar dollarAt = GlobalVariable.getGlobalVariable("main::@");
        savedDollarAt.type = dollarAt.type;
        savedDollarAt.value = dollarAt.value;

        // Enable rescue detection: track the DESTROY target and reset the flag.
        // During DESTROY, if $self is stored in a hash element (e.g.,
        // Schema::DESTROY reattaching to a ResultSource), RuntimeHash.put
        // will detect the referent and set destroyTargetRescued = true.
        // After DESTROY, if rescued, skip cascade to keep internals alive.
        RuntimeBase savedTarget = currentDestroyTarget;
        boolean savedRescued = destroyTargetRescued;
        currentDestroyTarget = referent;
        destroyTargetRescued = false;

        // Phase 3 (refcount_alignment_plan.md): Transition from MIN_VALUE
        // back to 0 so increments/decrements inside DESTROY work normally.
        // currentlyDestroying guards callDestroy re-entry from transient
        // decrement-to-0 events (see callDestroy's entry check).
        boolean savedCurrentlyDestroying = referent.currentlyDestroying;
        referent.currentlyDestroying = true;
        if (referent.refCount == Integer.MIN_VALUE) {
            referent.refCount = 0;
        }

        try {
            // Build $self reference to pass as $_[0]
            RuntimeScalar self = new RuntimeScalar();
            // Determine the reference type based on the referent's runtime class.
            // Order matters: RuntimeGlob extends RuntimeScalar, so check RuntimeGlob
            // BEFORE RuntimeScalar to avoid misclassifying globs as plain references.
            if (referent instanceof RuntimeHash) {
                self.type = RuntimeScalarType.HASHREFERENCE;
            } else if (referent instanceof RuntimeArray) {
                self.type = RuntimeScalarType.ARRAYREFERENCE;
            } else if (referent instanceof RuntimeGlob) {
                self.type = RuntimeScalarType.GLOBREFERENCE;
            } else if (referent instanceof RuntimeScalar) {
                self.type = RuntimeScalarType.REFERENCE;
            } else if (referent instanceof RuntimeCode) {
                self.type = RuntimeScalarType.CODE;
            } else {
                self.type = RuntimeScalarType.REFERENCE; // fallback
            }
            self.value = referent;

            RuntimeArray args = new RuntimeArray();
            args.push(self);
            // Phase 3: Snapshot pending size so we can drain only the entries
            // added during apply (shift @_, $self scope exit) without
            // clobbering outer-scope pending entries.
            int pendingBefore = MortalList.pendingSize();
            RuntimeCode.apply(destroyMethod, args, RuntimeContextType.VOID);

            // Phase 3: Drain pending entries added during apply, regardless
            // of whether an outer flush is currently running.
            MortalList.drainPendingSince(pendingBefore);

            // Phase 3: Balance the args.push(self) increment. If the body
            // consumed the element via shift, args.elements is empty (nothing
            // to balance). Otherwise, the args.push bump is still on refCount
            // and must be undone so we don't falsely detect resurrection.
            //
            // Direct decrement (not via MortalList pending) avoids
            // infinite-loop feedback when this decrement itself would fire
            // callDestroy recursively.
            for (RuntimeScalar elem : args.elements) {
                if (elem != null && elem.refCountOwned
                        && elem.value instanceof RuntimeBase base
                        && base.refCount > 0) {
                    base.refCount--;
                    elem.refCountOwned = false;
                }
            }
            args.elements.clear();
            args.elementsOwned = false;

            // Phase 3: Resurrection detection. If refCount > 0 at this point,
            // a strong ref to the object escaped DESTROY (e.g. Devel::StackTrace-
            // like @DB::args capture into a persistent array, or Schema-style
            // self-save). Mark needsReDestroy and let the next decrement-to-0
            // re-invoke DESTROY. Don't clear weak refs or cascade — the object
            // is still alive.
            if (referent.refCount > 0 && !destroyTargetRescued) {
                referent.needsReDestroy = true;
                return;
            }

            // Check if DESTROY rescued the object by storing $self somewhere.
            // If destroyTargetRescued was set during DESTROY (detected by
            // RuntimeScalar.setLargeRefCounted when the old value was a weak ref
            // to currentDestroyTarget being overwritten by a strong ref to the
            // same target), the object should survive — skip cascade cleanup.
            //
            // Example: Schema::DESTROY re-attaches itself to a ResultSource via
            //   $source->{schema} = $self
            // This triggers rescue detection because the old value ($source->{schema},
            // a weak ref to Schema) is being replaced by a strong ref to Schema.
            if (destroyTargetRescued) {
                // Object was rescued by DESTROY (e.g., Schema::DESTROY self-save).
                //
                // refCount has been set to 1 by setLargeRefCounted during rescue
                // detection (MIN_VALUE → 1). This represents the rescue container's
                // single counted reference (e.g., $source->{schema} = $self).
                //
                // When the rescue source eventually dies and its DESTROY weakens
                // source->{schema}, refCount goes 1→0→callDestroy. That callDestroy
                // is intercepted by the rescuedObjects check (skip cleanup), keeping
                // Schema's internals intact during the phantom chain. Proper cleanup
                // happens later via processRescuedObjects at block scope exit.
                //
                // Keep destroyFired=true to prevent infinite DESTROY loops.
                //
                // Don't clear weak refs here — the rescued object is still alive,
                // and other sources may still have weak refs to it that need to
                // remain defined until the object truly dies.
                //
                // Don't cascade — the rescued object's internal fields (Storage,
                // DBI::db, ResultSources) must remain intact because the object
                // is still alive.
                //
                // Track rescued objects so clearRescuedWeakRefs can clean up
                // at END time.
                rescuedObjects.add(referent);
                return;
            }

            // Object was NOT rescued — clear weak refs now (deferred from callDestroy).
            // In Perl 5, weak refs are cleared after DESTROY only if the object
            // wasn't resurrected. We match that by clearing here.
            WeakRefRegistry.clearWeakRefsTo(referent);

            // Cascading destruction: after DESTROY runs and the object was NOT rescued,
            // walk the destroyed object's internal fields for any blessed references
            // and defer their refCount decrements. This ensures nested objects
            // (e.g., $self->{inner}) are destroyed when their parent is destroyed.
            if (referent instanceof RuntimeHash hash) {
                MortalList.scopeExitCleanupHash(hash);
                MortalList.flush();
            } else if (referent instanceof RuntimeArray arr) {
                MortalList.scopeExitCleanupArray(arr);
                MortalList.flush();
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getName();
            // Use WarnDie.warn() (not Warnings.warn()) so the warning routes
            // through $SIG{__WARN__}, matching Perl 5 semantics.
            // Perl 5 prefixes DESTROY warnings with \t. Do NOT add \n — let
            // WarnDie.warn() handle the " at file line N\n" suffix naturally.
            // If msg already ends with \n (e.g., die "msg\n"), warn suppresses
            // the suffix. If msg doesn't (e.g., die $ref), warn appends it.
            String warning = "\t(in cleanup) " + msg;
            WarnDie.warn(
                    new RuntimeScalar(warning),
                    new RuntimeScalar(""));
        } finally {
            // Restore the DESTROY target and rescue flag for nested DESTROY calls
            currentDestroyTarget = savedTarget;
            destroyTargetRescued = savedRescued;
            // Phase 3: Exit DESTROY state. If refCount is still 0 and we're
            // not taking the resurrection path, set MIN_VALUE so future
            // callDestroy enters the normal cleanup path.
            referent.currentlyDestroying = savedCurrentlyDestroying;
            if (referent.refCount == 0 && !referent.needsReDestroy) {
                referent.refCount = Integer.MIN_VALUE;
            }
            // Restore $@ — must happen whether DESTROY succeeded or threw.
            // Without this, die inside DESTROY would clobber the caller's $@.
            dollarAt.type = savedDollarAt.type;
            dollarAt.value = savedDollarAt.value;
            // Phase D: outermost DESTROY is finishing. If any nested set()
            // released a cyclic blessed-with-DESTROY ref, fire one walker
            // sweep to clear any now-orphaned weak refs. This amortizes
            // the sweep cost to at most one per top-level DESTROY instead
            // of per-set(). Gated by ModuleInitGuard to avoid tripping
            // during require/use load.
            if (savedTarget == null && sweepPendingAfterOuterDestroy
                    && !ModuleInitGuard.inModuleInit()) {
                sweepPendingAfterOuterDestroy = false;
                ReachabilityWalker.sweepWeakRefs(false);
            }
        }
    }

    /**
     * Process rescued objects at block scope exit (called from {@link MortalList#popAndFlush}).
     * <p>
     * Rescued objects are kept alive during the scope where they were rescued (e.g., during
     * the DBIC phantom chain). At block scope exit, we check if they are ready for cleanup:
     * <ul>
     *   <li>refCount == 1: The rescue container's counted reference is the only one left.
     *       No code path holds a live reference to the object.</li>
     *   <li>refCount == MIN_VALUE: The weaken cascade already brought refCount to 0, and
     *       callDestroy was called but skipped because the object was in rescuedObjects.
     *       The object is definitely dead and needs cleanup.</li>
     * </ul>
     * <p>
     * For each such object, we remove it from rescuedObjects and call callDestroy, which
     * (now that the object is no longer in rescuedObjects) will clear weak refs and cascade
     * into elements. This ensures DBIC's leak tracer sees the weak refs as undef.
     */
    public static void processRescuedObjects() {
        if (rescuedObjects.isEmpty()) return;
        // Snapshot and clear to avoid ConcurrentModificationException
        java.util.List<RuntimeBase> snapshot;
        synchronized (rescuedObjects) {
            snapshot = new java.util.ArrayList<>(rescuedObjects);
            rescuedObjects.clear();
        }
        boolean anyProcessed = false;
        for (RuntimeBase obj : snapshot) {
            if (obj.destroyFired && (obj.refCount == 1 || obj.refCount == Integer.MIN_VALUE)) {
                // Object is dead — either the rescue container was the only reference
                // (refCount == 1), or the weaken cascade already triggered callDestroy
                // which was skipped (refCount == MIN_VALUE). Clean up now.
                obj.refCount = Integer.MIN_VALUE;
                callDestroy(obj);  // destroyFired=true, NOT in rescuedObjects → clearWeakRefsTo + cascade
                anyProcessed = true;
            } else {
                // Object still has external references or unexpected state.
                // Keep tracking it for later processing.
                rescuedObjects.add(obj);
            }
        }
        if (anyProcessed) {
            MortalList.flush();
        }
    }

    /**
     * Clear weak refs for all objects that were rescued by DESTROY.
     * Called by MortalList.flushDeferredCaptures() before END blocks run.
     * <p>
     * This deferred approach is necessary because clearing weak refs immediately
     * after rescue would destroy back-references from sibling objects that are
     * still needed (e.g., other ResultSources' $source->{schema} weak refs).
     * By deferring until just before END blocks, all test code has finished
     * executing and the back-references are no longer needed.
     * <p>
     * For each rescued object:
     * 1. Clear its own weak refs (for DBIC's leak tracer registry)
     * 2. Deep-sweep its hash contents for nested blessed objects (Storage, DBI::db)
     *    and clear their weak refs too
     */
    public static void clearRescuedWeakRefs() {
        if (rescuedObjects.isEmpty()) return;
        java.util.List<RuntimeBase> snapshot;
        synchronized (rescuedObjects) {
            snapshot = new java.util.ArrayList<>(rescuedObjects);
            rescuedObjects.clear();
        }
        for (RuntimeBase rescued : snapshot) {
            WeakRefRegistry.clearWeakRefsTo(rescued);
            if (rescued instanceof RuntimeHash hash) {
                deepClearWeakRefs(hash);
            }
        }
    }

    /**
     * Recursively walk a hash's values and clear weak refs for any blessed
     * objects found, including nested hashes and arrays. This is used after
     * DESTROY rescue to clear weak refs for objects contained inside the
     * rescued object (e.g., Storage::DBI and DBI::db inside a Schema hash).
     * <p>
     * Unlike {@link MortalList#scopeExitCleanupHash}, this method does NOT
     * decrement refcounts or trigger DESTROY on the found objects. It only
     * clears weak refs. This is critical because the rescued object is still
     * alive and its internals must remain intact for future use.
     * <p>
     * Uses a depth limit to avoid infinite recursion on circular references
     * (which are common in DBIC — Schema → Storage → DBI::db → Schema).
     *
     * @param hash The hash to walk
     */
    private static void deepClearWeakRefs(RuntimeHash hash) {
        deepClearWeakRefsImpl(hash, 5);
    }

    /**
     * Implementation of deep weak-ref clearing with depth limit.
     *
     * @param hash     The hash to walk
     * @param maxDepth Maximum recursion depth (prevents infinite loops on circular refs)
     */
    private static void deepClearWeakRefsImpl(RuntimeHash hash, int maxDepth) {
        if (maxDepth <= 0) return;
        for (RuntimeScalar val : hash.elements.values()) {
            // Check for any reference type (REFERENCE, HASHREFERENCE, ARRAYREFERENCE, etc.)
            // using the REFERENCE_BIT flag. A blessed hash stored as $schema->{storage}
            // may have type HASHREFERENCE rather than plain REFERENCE.
            if ((val.type & RuntimeScalarType.REFERENCE_BIT) != 0
                    && val.value instanceof RuntimeBase base) {
                // Clear weak refs for this blessed object (e.g., Storage::DBI, DBI::db).
                // Only clear if the object is blessed (blessId != 0) to avoid clearing
                // weak refs for plain unblessed containers that might be shared.
                if (base.blessId != 0) {
                    WeakRefRegistry.clearWeakRefsTo(base);
                }
                // Recurse into nested hashes to find deeper blessed objects
                // (e.g., Schema → {storage} → Storage → {_dbh} → DBI::db)
                if (base instanceof RuntimeHash nestedHash) {
                    deepClearWeakRefsImpl(nestedHash, maxDepth - 1);
                }
            }
        }
    }
}

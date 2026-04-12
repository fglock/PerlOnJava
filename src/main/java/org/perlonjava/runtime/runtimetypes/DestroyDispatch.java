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

    /**
     * Check whether the class identified by blessId defines DESTROY (or AUTOLOAD).
     * Result is cached in the destroyClasses BitSet.
     *
     * @param blessId   the numeric class identity (from NameNormalizer.getBlessId)
     * @param className the Perl class name
     * @return true if DESTROY (or AUTOLOAD) is defined in the class hierarchy
     */
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
        // Note: refCount stays at MIN_VALUE to prevent re-entrant DESTROY calls.
        RuntimeBase savedTarget = currentDestroyTarget;
        boolean savedRescued = destroyTargetRescued;
        currentDestroyTarget = referent;
        destroyTargetRescued = false;

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
            RuntimeCode.apply(destroyMethod, args, RuntimeContextType.VOID);

            // Check if DESTROY rescued the object by storing $self somewhere.
            // If destroyTargetRescued was set during DESTROY (detected by
            // RuntimeScalar.set), the object should survive — skip cascade.
            if (destroyTargetRescued) {
                // Object was rescued by DESTROY (e.g., Schema::DESTROY self-save).
                // Mark as untracked (-1) so cooperative refcounting won't trigger
                // DESTROY again. The JVM GC will handle final cleanup.
                // Setting to 1 instead would cause infinite DESTROY loops because
                // the temporary reference that triggered DESTROY is already gone,
                // so refCount would immediately drop back to 0.
                referent.refCount = -1;
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
            // Restore $@ — must happen whether DESTROY succeeded or threw.
            // Without this, die inside DESTROY would clobber the caller's $@.
            dollarAt.type = savedDollarAt.type;
            dollarAt.value = savedDollarAt.value;
        }
    }
}

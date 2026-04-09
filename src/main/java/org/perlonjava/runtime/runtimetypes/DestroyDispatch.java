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

        // Clear weak refs BEFORE calling DESTROY (or returning for unblessed objects)
        WeakRefRegistry.clearWeakRefsTo(referent);

        // Release closure captures when a CODE ref's refCount hits 0.
        // This allows captured variables to be properly cleaned up
        // (e.g., blessed objects in captured scalars can fire DESTROY).
        if (referent instanceof RuntimeCode code) {
            code.releaseCaptures();
        }

        String className = NameNormalizer.getBlessStr(referent.blessId);
        if (className == null) return;

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
            return; // No DESTROY and no AUTOLOAD found
        }

        // If findMethodInHierarchy returned an AUTOLOAD sub (because no explicit DESTROY
        // exists), we need to set $AUTOLOAD before calling it. The method resolver sets
        // autoloadVariableName on the RuntimeCode when it falls through to the AUTOLOAD pass.
        RuntimeCode code = (RuntimeCode) destroyMethod.value;
        if (code.autoloadVariableName != null) {
            String fullMethodName = className + "::DESTROY";
            GlobalVariable.getGlobalVariable(code.autoloadVariableName).set(fullMethodName);
        }

        try {
            // Perl requires: local($@, $!, $?) around DESTROY
            // Save global status variables
            RuntimeScalar savedDollarAt = new RuntimeScalar();
            savedDollarAt.type = GlobalVariable.getGlobalVariable("main::@").type;
            savedDollarAt.value = GlobalVariable.getGlobalVariable("main::@").value;

            // Build $self reference to pass as $_[0]
            RuntimeScalar self = new RuntimeScalar();
            // Determine the reference type based on the referent's runtime class
            if (referent instanceof RuntimeHash) {
                self.type = RuntimeScalarType.HASHREFERENCE;
            } else if (referent instanceof RuntimeArray) {
                self.type = RuntimeScalarType.ARRAYREFERENCE;
            } else if (referent instanceof RuntimeScalar) {
                self.type = RuntimeScalarType.REFERENCE;
            } else if (referent instanceof RuntimeGlob) {
                self.type = RuntimeScalarType.GLOBREFERENCE;
            } else if (referent instanceof RuntimeCode) {
                self.type = RuntimeScalarType.CODE;
            } else {
                self.type = RuntimeScalarType.REFERENCE; // fallback
            }
            self.value = referent;

            RuntimeArray args = new RuntimeArray();
            args.push(self);
            RuntimeCode.apply(destroyMethod, args, RuntimeContextType.VOID);

            // Cascading destruction: after DESTROY runs, walk the destroyed object's
            // internal fields for any blessed references and defer their refCount
            // decrements. This ensures nested objects (e.g., $self->{inner}) are
            // destroyed when their parent is destroyed.
            // 
            // Note: RuntimeCode.apply() calls MortalList.flush() at the top, which
            // clears all pending entries. So we must walk AFTER apply returns and
            // process the cascading entries immediately (flush them inline) rather
            // than relying on the caller's popAndFlush loop to pick them up.
            if (referent instanceof RuntimeHash hash) {
                MortalList.scopeExitCleanupHash(hash);
                MortalList.flush();
            } else if (referent instanceof RuntimeArray arr) {
                MortalList.scopeExitCleanupArray(arr);
                MortalList.flush();
            }

            // Restore saved globals
            GlobalVariable.getGlobalVariable("main::@").type = savedDollarAt.type;
            GlobalVariable.getGlobalVariable("main::@").value = savedDollarAt.value;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getName();
            // Use WarnDie.warn() (not Warnings.warn()) so the warning routes
            // through $SIG{__WARN__}, matching Perl 5 semantics.
            WarnDie.warn(
                    new RuntimeScalar("(in cleanup) " + msg + "\n"),
                    new RuntimeScalar(""));
        }
    }
}

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
        // First time for this class — check hierarchy
        RuntimeScalar m = InheritanceResolver.findMethodInHierarchy("DESTROY", className, null, 0);
        if (m == null) {
            m = InheritanceResolver.findMethodInHierarchy("AUTOLOAD", className, null, 0);
        }
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
        String className = NameNormalizer.getBlessStr(referent.blessId);
        if (className == null) return;

        // Clear weak refs BEFORE calling DESTROY
        WeakRefRegistry.clearWeakRefsTo(referent);

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
            // No DESTROY — check AUTOLOAD
            RuntimeScalar autoloadRef = InheritanceResolver.findMethodInHierarchy(
                    "AUTOLOAD", className, null, 0);
            if (autoloadRef == null) return;
            GlobalVariable.getGlobalVariable(className + "::AUTOLOAD")
                    .set(new RuntimeScalar(className + "::DESTROY"));
            destroyMethod = autoloadRef;
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

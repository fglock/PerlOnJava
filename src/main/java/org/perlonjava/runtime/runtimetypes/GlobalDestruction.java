package org.perlonjava.runtime.runtimetypes;

/**
 * Handles global destruction at program exit.
 * <p>
 * Walks all package stashes and global variables to find blessed objects
 * with refCount >= 0 that still need DESTROY. This covers globals, stash
 * entries, and values inside global arrays and hashes.
 * <p>
 * Matches Perl 5 behavior where global destruction runs after END blocks.
 */
public class GlobalDestruction {

    /**
     * Run global destruction: walk all global variables and call DESTROY
     * on any tracked blessed references that haven't been destroyed yet.
     */
    public static void runGlobalDestruction() {
        // Set ${^GLOBAL_PHASE} to "DESTRUCT"
        GlobalVariable.getGlobalVariable(GlobalContext.GLOBAL_PHASE).set("DESTRUCT");

        // Walk all global scalars
        for (RuntimeScalar val : GlobalVariable.globalVariables.values()) {
            destroyIfTracked(val);
        }

        // Walk global arrays for blessed ref elements
        for (RuntimeArray arr : GlobalVariable.globalArrays.values()) {
            // Skip tied arrays — iterating them calls FETCHSIZE/FETCH on the
            // tie object, which may already be destroyed or invalid at global
            // destruction time (e.g., broken ties from eval+last).
            if (arr.type == RuntimeArray.TIED_ARRAY) continue;
            for (RuntimeScalar elem : arr) {
                destroyIfTracked(elem);
            }
        }

        // Walk global hashes for blessed ref values
        for (RuntimeHash hash : GlobalVariable.globalHashes.values()) {
            // Skip tied hashes — iterating them dispatches through FIRSTKEY/
            // NEXTKEY/FETCH which may fail if the tie object is already gone.
            if (hash.type == RuntimeHash.TIED_HASH) continue;
            for (RuntimeScalar elem : hash.values()) {
                destroyIfTracked(elem);
            }
        }
    }

    /**
     * Call DESTROY on a scalar if it holds a tracked blessed reference.
     */
    private static void destroyIfTracked(RuntimeScalar val) {
        if (val != null
                && (val.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && val.value instanceof RuntimeBase base
                && base.refCount >= 0) {
            base.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(base);
        }
    }
}

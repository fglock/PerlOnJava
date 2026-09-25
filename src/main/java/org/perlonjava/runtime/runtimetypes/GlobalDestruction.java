package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
        Set<RuntimeBase> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());

        // Snapshot the collections before iterating: a DESTROY callback may
        // mutate GlobalVariable.{globalVariables,globalArrays,globalHashes}
        // (e.g. by creating a new tied variable, opening/closing handles,
        // or installing END-like cleanup), which would otherwise raise
        // ConcurrentModificationException. Real-world trigger: exit(N)
        // while holding a System::Command object whose Reaper's DESTROY
        // spawns further cleanup. See dev/modules/git_modules_support.md.

        // Walk all global scalars
        for (RuntimeScalar val : new ArrayList<>(GlobalVariable.globalVariables.values())) {
            destroyIfTracked(val, visited);
        }

        // CODE slots are kept in a separate namespace map from ordinary
        // package variables. They are also the roots for closures whose pads
        // may contain the last blessed object reference.
        for (RuntimeScalar code : new ArrayList<>(GlobalVariable.globalCodeRefs.values())) {
            if (code != null && code.value instanceof RuntimeCode runtimeCode) {
                runtimeCode.releaseCaptures();
            }
            destroyIfTracked(code, visited);
        }

        // Walk global arrays for blessed ref elements
        for (RuntimeArray arr : new ArrayList<>(GlobalVariable.globalArrays.values())) {
            if (arr == null) continue;  // defensive: rare null entries seen during END
            // Skip tied arrays — iterating them calls FETCHSIZE/FETCH on the
            // tie object, which may already be destroyed or invalid at global
            // destruction time (e.g., broken ties from eval+last).
            if (arr.type == RuntimeArray.TIED_ARRAY) continue;
            for (RuntimeScalar elem : new ArrayList<>(arr.elements)) {
                destroyIfTracked(elem, visited);
            }
        }

        // Walk global hashes for blessed ref values
        for (RuntimeHash hash : new ArrayList<>(GlobalVariable.globalHashes.values())) {
            if (hash == null) continue;  // defensive
            // Skip tied hashes — iterating them dispatches through FIRSTKEY/
            // NEXTKEY/FETCH which may fail if the tie object is already gone.
            // The tie handler itself is still owned by the global hash, though,
            // and must be released so its DESTROY method runs at process exit.
            if (hash.type == RuntimeHash.TIED_HASH) {
                if (hash.elements instanceof TieHash tieHash) {
                    tieHash.releaseTiedObject();
                }
                continue;
            }
            for (RuntimeScalar elem : new ArrayList<>(hash.elements.values())) {
                destroyIfTracked(elem, visited);
            }
        }

        // Perl treats its standard output handle as an IO::Handle object at
        // global destruction.  Closing the Java stream alone skips a Perl
        // IO::Handle::DESTROY method, which is observable (and in particular
        // may still use regex state and print to STDOUT).  Dispatch it before
        // RuntimeIO.closeAllHandles() closes the stream.
        destroyStandardOutputHandle(visited);

        // Releasing a global closure can enqueue the final decrement for a
        // captured lexical. Drain that decrement before global teardown ends.
        MortalList.flush();
    }

    private static void destroyStandardOutputHandle(Set<RuntimeBase> visited) {
        RuntimeGlob stdout = GlobalVariable.getGlobalIO("main::STDOUT");
        if (stdout.destroyFired) return;

        int blessId = stdout.blessId;
        if (blessId == 0) {
            blessId = NameNormalizer.getBlessId("IO::Handle");
            RuntimeScalar destroy = InheritanceResolver.findMethodInHierarchy(
                    "DESTROY", "IO::Handle", null, 0);
            if (destroy == null || !(destroy.value instanceof RuntimeCode code)
                    || !code.defined()
                    // The bundled fallback has no Perl-visible standard-handle
                    // destructor semantics.  Dispatch only an application/module
                    // definition, such as the override exercised by op/ref.t.
                    || code.cvStartFile.startsWith("jar:")) return;
            stdout.setBlessId(blessId);
        }
        if (!visited.add(stdout)) return;
        stdout.refCount = Integer.MIN_VALUE;
        DestroyDispatch.callDestroy(stdout);
    }

    /**
     * Call DESTROY on a scalar if it holds a tracked blessed reference.
     */
    private static void destroyIfTracked(RuntimeScalar val, Set<RuntimeBase> visited) {
        if (val != null
                && (val.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && val.value instanceof RuntimeBase base
                // A global object can be blessed before its class installs
                // DESTROY.  Its initial transient cleanup then leaves the
                // object untracked (or at MIN_VALUE), but a later method
                // definition must still be observed at global destruction.
                && (base.refCount >= 0 || (base.blessId != 0 && !base.destroyFired))) {
            destroyBaseIfTracked(base, visited);
        }
    }

    private static void destroyBaseIfTracked(RuntimeBase base, Set<RuntimeBase> visited) {
        if (base == null
                || (base.refCount < 0 && (base.blessId == 0 || base.destroyFired))
                || !visited.add(base)) {
            return;
        }
        if (base.blessId != 0 || WeakRefRegistry.hasWeakRefsTo(base) || base instanceof RuntimeCode) {
            if (base instanceof RuntimeCode code) {
                // A global named subroutine can be the last owner of a
                // closure's lexical pad. Release that pad before dispatching
                // destruction so objects held only by captured lexicals are
                // also finalized during global destruction.
                code.releaseCaptures();
            }
            base.refCount = Integer.MIN_VALUE;
            DestroyDispatch.callDestroy(base);
            return;
        }
        if (base instanceof RuntimeArray arr && MortalList.containerMayContainCleanupTargets(base)) {
            for (RuntimeScalar elem : new ArrayList<>(arr.elements)) {
                destroyIfTracked(elem, visited);
            }
        } else if (base instanceof RuntimeHash hash && MortalList.containerMayContainCleanupTargets(base)) {
            for (RuntimeScalar elem : new ArrayList<>(hash.elements.values())) {
                destroyIfTracked(elem, visited);
            }
        }
    }

}

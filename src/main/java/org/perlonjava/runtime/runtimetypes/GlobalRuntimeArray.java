package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * A DynamicState implementation for global arrays that saves/restores the
 * globalArrays map entry when localized.  This ensures that references
 * taken to a localized array (e.g., {@code \@ARGV} inside a {@code local @ARGV}
 * scope) continue to point to the localized data after the scope exits.
 *
 * <p>Without this, the old approach (RuntimeArray.dynamicSaveState) modified
 * the array contents in-place, causing any reference captured during the
 * local scope to see the restored (empty/original) values.
 *
 * <p>Follows the same pattern as {@link GlobalRuntimeHash} for hashes
 * and {@link GlobalRuntimeScalar} for scalars.
 */
public class GlobalRuntimeArray implements DynamicState {
    @SuppressWarnings("unchecked")
    private static Stack<SavedGlobalArrayState> localizedStack() {
        return (Stack<SavedGlobalArrayState>) (Stack<?>)
                PerlRuntime.current().executionState().globalArrayStates;
    }
    private final String fullName;

    public GlobalRuntimeArray(String fullName) {
        this.fullName = fullName;
    }

    /**
     * Called from emitted code for {@code local @array} when the array
     * is a global (not lexical) variable.  Registers a DynamicState marker on
     * the local-variable stack so that scope exit restores the original array.
     *
     * @param fullName the fully-qualified array name (e.g. "main::ARGV")
     * @return the new (empty) RuntimeArray that is now the current global
     */
    public static RuntimeArray makeLocal(String fullName) {
        var localMarker = new GlobalRuntimeArray(fullName);
        DynamicVariableManager.pushLocalVariable(localMarker);
        return GlobalVariable.getGlobalArray(fullName);
    }

    @Override
    public void dynamicSaveState() {
        // Save the current array reference from the global map
        RuntimeArray original = GlobalVariable.globalArrays.get(fullName);
        localizedStack().push(new SavedGlobalArrayState(fullName, original));

        // Install a fresh empty array in the global map
        RuntimeArray newLocal = new RuntimeArray();
        if (fullName.endsWith("::ISA")) {
            newLocal.markIsaArray();
        }
        GlobalVariable.globalArrays.put(fullName, newLocal);
        if (fullName.endsWith("::ISA")) {
            org.perlonjava.runtime.mro.InheritanceResolver.noteIsaMutation();
        }

        // Update glob aliases so they all point to the new local array
        java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(fullName);
        for (String alias : aliasGroup) {
            if (!alias.equals(fullName)) {
                GlobalVariable.globalArrays.put(alias, newLocal);
            }
        }
    }

    @Override
    public void dynamicRestoreState() {
        Stack<SavedGlobalArrayState> localizedStack = localizedStack();
        if (!localizedStack.isEmpty()) {
            SavedGlobalArrayState saved = localizedStack.peek();
            if (saved.fullName.equals(this.fullName)) {
                localizedStack.pop();

                // If the local'd array was blessed during the scope (e.g.
                // `bless \@foo, 'Class'` where @foo is the localized one),
                // fire DESTROY now since the local array is about to be
                // discarded. Test: postfixderef.t #38 "no stooges outlast
                // their scope".
                RuntimeArray localArray = GlobalVariable.globalArrays.get(saved.fullName);
                if (localArray != null && localArray.blessId != 0
                        && !localArray.destroyFired
                        && (saved.originalArray == null || localArray != saved.originalArray)) {
                    localArray.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(localArray);
                }

                // Restore the original array reference in the global map
                // Release references held by the temporary localized array
                // before discarding it, matching RuntimeArray's local-scope
                // restoration path.
                if (localArray != null && localArray != saved.originalArray) {
                    MortalList.deferDestroyForContainerClear(localArray.elements);
                }
                GlobalVariable.globalArrays.put(saved.fullName, saved.originalArray);
                if (saved.fullName.endsWith("::ISA")) {
                    if (saved.originalArray != null) {
                        saved.originalArray.markIsaArray();
                    }
                    org.perlonjava.runtime.mro.InheritanceResolver.noteIsaMutation();
                }

                // Restore glob aliases
                java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(saved.fullName);
                for (String alias : aliasGroup) {
                    if (!alias.equals(saved.fullName)) {
                        GlobalVariable.globalArrays.put(alias, saved.originalArray);
                    }
                }
            }
        }
    }

    @Override
    public Object dynamicSuspendState() {
        RuntimeArray activeVariable = GlobalVariable.getGlobalArray(fullName);
        RuntimeArray activeState = new RuntimeArray();
        if (activeVariable != null) {
            activeState.type = activeVariable.type;
            activeState.blessId = activeVariable.blessId;
            activeState.scalarContextSize = activeVariable.scalarContextSize;
            activeState.elements.addAll(activeVariable.elements);
        }
        dynamicRestoreState();
        return activeState;
    }

    @Override
    public void dynamicResumeState(Object token) {
        dynamicSaveState();
        if (token instanceof RuntimeArray activeState) {
            RuntimeArray activeVariable = GlobalVariable.getGlobalArray(fullName);
            activeVariable.type = activeState.type;
            activeVariable.blessId = activeState.blessId;
            activeVariable.scalarContextSize = activeState.scalarContextSize;
            activeVariable.elements.addAll(activeState.elements);
        }
    }

    private record SavedGlobalArrayState(String fullName, RuntimeArray originalArray) {
    }
}

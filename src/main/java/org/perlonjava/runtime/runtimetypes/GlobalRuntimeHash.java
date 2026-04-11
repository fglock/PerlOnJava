package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * A DynamicState implementation for global hashes that saves/restores the
 * globalHashes map entry when localized.  This handles the case where
 * {@code local %hash} is followed by {@code *hash = \%other} — the glob
 * slot assignment replaces the map entry, so a simple save-and-restore of
 * the hash contents (as RuntimeHash.dynamicSaveState does) is insufficient.
 *
 * <p>Follows the same pattern as {@link GlobalRuntimeScalar} for scalars.
 */
public class GlobalRuntimeHash implements DynamicState {
    // Localized stack is now held per-PerlRuntime.
    @SuppressWarnings("unchecked")
    private static Stack<SavedGlobalHashState> localizedStack() {
        return (Stack<SavedGlobalHashState>) (Stack<?>) PerlRuntime.current().globalHashLocalizedStack;
    }
    private final String fullName;

    public GlobalRuntimeHash(String fullName) {
        this.fullName = fullName;
    }

    /**
     * Called from the JVM-emitted code for {@code local %hash} when the hash
     * is a global (not lexical) variable.  Registers a DynamicState marker on
     * the local-variable stack so that scope exit restores the original hash.
     *
     * @param fullName the fully-qualified hash name (e.g. "main::_")
     * @return the current RuntimeHash (callers may ignore this in VOID context)
     */
    public static RuntimeHash makeLocal(String fullName) {
        var localMarker = new GlobalRuntimeHash(fullName);
        DynamicVariableManager.pushLocalVariable(localMarker);
        return GlobalVariable.getGlobalHash(fullName);
    }

    @Override
    public void dynamicSaveState() {
        // Save the current hash reference from the global map
        RuntimeHash original = GlobalVariable.getGlobalHashesMap().get(fullName);
        localizedStack().push(new SavedGlobalHashState(fullName, original));

        // Install a fresh empty hash in the global map
        RuntimeHash newLocal = new RuntimeHash();
        GlobalVariable.getGlobalHashesMap().put(fullName, newLocal);

        // Update glob aliases so they all point to the new local hash
        java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(fullName);
        for (String alias : aliasGroup) {
            if (!alias.equals(fullName)) {
                GlobalVariable.getGlobalHashesMap().put(alias, newLocal);
            }
        }
    }

    @Override
    public void dynamicRestoreState() {
        if (!localizedStack().isEmpty()) {
            SavedGlobalHashState saved = localizedStack().peek();
            if (saved.fullName.equals(this.fullName)) {
                localizedStack().pop();

                // Restore the original hash reference in the global map
                GlobalVariable.getGlobalHashesMap().put(saved.fullName, saved.originalHash);

                // Restore glob aliases
                java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(saved.fullName);
                for (String alias : aliasGroup) {
                    if (!alias.equals(saved.fullName)) {
                        GlobalVariable.getGlobalHashesMap().put(alias, saved.originalHash);
                    }
                }
            }
        }
    }

    private record SavedGlobalHashState(String fullName, RuntimeHash originalHash) {
    }
}

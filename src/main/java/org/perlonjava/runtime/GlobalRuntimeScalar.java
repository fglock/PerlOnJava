package org.perlonjava.runtime;

import java.util.Stack;

/**
 * A RuntimeScalar subclass for global variables that knows its fully qualified name.
 * This allows implementing Perl's `local` semantics by replacing the variable in the
 * global symbol table and restoring it when the context exits.
 */
public class GlobalRuntimeScalar extends RuntimeScalar {
    private final String fullName;

    // Stack to store the previous values when localized
    private static final Stack<SavedGlobalState> localizedStack = new Stack<>();

    public GlobalRuntimeScalar(String fullName) {
        super();
        this.fullName = fullName;
    }

    public static RuntimeScalar makeLocal(String fullName) {
        var localMarker = new GlobalRuntimeScalar(fullName);
        DynamicVariableManager.pushLocalVariable(localMarker);
        return GlobalVariable.getGlobalVariable(fullName);
    }

    @Override
    public void dynamicSaveState() {
        // Create a new RuntimeScalar for the localized value
        GlobalRuntimeScalar newLocal = new GlobalRuntimeScalar(fullName);

        // Save the current global reference
        var originalVariable = GlobalVariable.globalVariables.get(fullName);

        // System.out.println("Saving state for " + fullName + " with value " + value + " put " + originalVariable.hashCode());

        localizedStack.push(new SavedGlobalState(fullName, originalVariable));

        // Replace this variable in the global symbol table with the new one
        GlobalVariable.globalVariables.put(fullName, newLocal);
    }

    @Override
    public void dynamicRestoreState() {
        if (!localizedStack.isEmpty()) {
            SavedGlobalState saved = localizedStack.peek();
            if (saved.fullName.equals(this.fullName)) {
                localizedStack.pop();

                // System.out.println("Restoring state for " + saved.fullName + " put back " + saved.originalVariable.hashCode());

                // Restore the original variable in the global symbol table
                GlobalVariable.globalVariables.put(saved.fullName, saved.originalVariable);
            }
        }
    }

    private static class SavedGlobalState {
        final String fullName;
        final RuntimeScalar originalVariable;

        SavedGlobalState(String fullName, RuntimeScalar originalVariable) {
            this.fullName = fullName;
            this.originalVariable = originalVariable;
        }
    }
}


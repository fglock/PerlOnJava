package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;

/**
 * A RuntimeScalar subclass for global variables that knows its fully qualified name.
 * This allows implementing Perl's `local` semantics by replacing the variable in the
 * global symbol table and restoring it when the context exits.
 */
public class GlobalRuntimeScalar extends RuntimeScalar {
    // Stack to store the previous values when localized
    private static final Stack<SavedGlobalState> localizedStack = new Stack<>();
    private final String fullName;

    public GlobalRuntimeScalar(String fullName) {
        super();
        this.fullName = fullName;
    }

    public static RuntimeScalar makeLocal(String fullName) {
        RuntimeScalar original = GlobalVariable.getGlobalVariable(fullName);
        if (original instanceof ScalarSpecialVariable sv && sv.variableId == ScalarSpecialVariable.Id.INPUT_LINE_NUMBER) {
            DynamicVariableManager.pushLocalVariable(original);
            return original;
        }
        if (original instanceof OutputAutoFlushVariable) {
            DynamicVariableManager.pushLocalVariable(original);
            return original;
        }
        if (original instanceof ErrnoVariable) {
            DynamicVariableManager.pushLocalVariable(original);
            return original;
        }
        if (fullName.endsWith("::1")) {
            var regexVar = GlobalVariable.getGlobalVariable(fullName);
            DynamicVariableManager.pushLocalVariable(regexVar);
            return regexVar;
        }
        var localMarker = new GlobalRuntimeScalar(fullName);
        DynamicVariableManager.pushLocalVariable(localMarker);
        return GlobalVariable.getGlobalVariable(fullName);
    }

    @Override
    public void dynamicSaveState() {
        // Save the current global reference
        var originalVariable = GlobalVariable.getGlobalVariablesMap().get(fullName);

        localizedStack.push(new SavedGlobalState(fullName, originalVariable));

        // Create a new variable for the localized scope.
        // For output separator variables, create the matching special type so that
        // set() in the localized scope correctly updates the internal value that print reads.
        // Also save the internal separator value for restoration.
        RuntimeScalar newLocal;
        if (originalVariable instanceof OutputRecordSeparator) {
            OutputRecordSeparator.saveInternalORS();
            newLocal = new OutputRecordSeparator();
        } else if (originalVariable instanceof OutputFieldSeparator) {
            OutputFieldSeparator.saveInternalOFS();
            newLocal = new OutputFieldSeparator();
        } else {
            newLocal = new GlobalRuntimeScalar(fullName);
        }

        // Replace this variable in the global symbol table with the new one
        GlobalVariable.getGlobalVariablesMap().put(fullName, newLocal);

        // Also update all glob aliases to point to the new local variable.
        // This implements Perl 5 semantics where after `*verbose = *Verbose`,
        // `local $verbose = 1` also affects `$Verbose`.
        java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(fullName);
        for (String alias : aliasGroup) {
            if (!alias.equals(fullName)) {
                GlobalVariable.getGlobalVariablesMap().put(alias, newLocal);
            }
        }
    }

    @Override
    public void dynamicRestoreState() {
        if (!localizedStack.isEmpty()) {
            SavedGlobalState saved = localizedStack.peek();
            if (saved.fullName.equals(this.fullName)) {
                localizedStack.pop();

                // Restore the internal separator values if this was an output separator variable
                if (saved.originalVariable instanceof OutputRecordSeparator) {
                    OutputRecordSeparator.restoreInternalORS();
                } else if (saved.originalVariable instanceof OutputFieldSeparator) {
                    OutputFieldSeparator.restoreInternalOFS();
                }

                // Restore the original variable in the global symbol table
                GlobalVariable.getGlobalVariablesMap().put(saved.fullName, saved.originalVariable);

                // Also restore all glob aliases to the original shared variable
                java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(saved.fullName);
                for (String alias : aliasGroup) {
                    if (!alias.equals(saved.fullName)) {
                        GlobalVariable.getGlobalVariablesMap().put(alias, saved.originalVariable);
                    }
                }
            }
        }
    }

    private record SavedGlobalState(String fullName, RuntimeScalar originalVariable) {
    }
}


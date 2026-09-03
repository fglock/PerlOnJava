package org.perlonjava.runtime.runtimetypes;

import java.util.Stack;
import org.perlonjava.runtime.WarningBitsRegistry;

/**
 * A RuntimeScalar subclass for global variables that knows its fully qualified name.
 * This allows implementing Perl's `local` semantics by replacing the variable in the
 * global symbol table and restoring it when the context exits.
 */
public class GlobalRuntimeScalar extends RuntimeScalar {
    // Stack to store the previous values when localized
    @SuppressWarnings("unchecked")
    private static Stack<SavedGlobalState> localizedStack() {
        return (Stack<SavedGlobalState>) (Stack<?>)
                PerlRuntime.current().executionState().globalScalarStates;
    }
    private final String fullName;

    public GlobalRuntimeScalar(String fullName) {
        super();
        this.fullName = fullName;
    }

    boolean localizes(String variableName) {
        return fullName.equals(variableName);
    }

    String localizedName() {
        return fullName;
    }

    RuntimeScalar activeLocalizedValue() {
        Stack<SavedGlobalState> states = localizedStack();
        for (int i = states.size() - 1; i >= 0; i--) {
            SavedGlobalState state = states.get(i);
            if (state.fullName.equals(fullName)) {
                return state.localizedVariable;
            }
        }
        return null;
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
        if (original instanceof CurrentFormatVariable) {
            DynamicVariableManager.pushLocalVariable(original);
            return original;
        }
        if (original instanceof ErrnoVariable) {
            DynamicVariableManager.pushLocalVariable(original);
            return original;
        }
        // Numbered capture variables are magic views into the current regex
        // state.  They must stay magic while localized: replacing $2 (or a
        // higher capture) with a normal GlobalRuntimeScalar prevents a later
        // match from updating it.  $1 was historically handled here, but the
        // same rule applies to every non-zero numeric capture variable.
        if (fullName.matches(".*::[1-9]\\d*")) {
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
        Stack<SavedGlobalState> localizedStack = localizedStack();
        // Save the current global reference
        var originalVariable = GlobalVariable.globalVariables.get(fullName);

        // Tied scalars need special handling: the tie magic must stay in
        // place for the duration of the localized scope, so that an
        // assignment `local $tied = value` dispatches through STORE (and
        // restoration on scope exit dispatches STORE with the saved
        // value). This matches real Perl semantics and is required by
        // modules like File::chdir whose tied $CWD actually chdir's in
        // STORE. See dev/modules/git_modules_support.md.
        if (originalVariable != null
                && originalVariable.type == RuntimeScalarType.TIED_SCALAR) {
            RuntimeScalar savedValue = originalVariable.tiedFetch();
            // Real Perl dispatches STORE(undef) on entry to localize so
            // the tie handler sees the transition. Modules like
            // File::chdir explicitly short-circuit on undef
            // (`return unless defined $_[1];`).
            // This undef is exposed to STORE as $_[1].  It must be a mutable
            // argument, because real Perl lets the handler vivify it (for
            // example Tie::Select's `select $_[1]`).  The shared cached undef
            // is intentionally read-only and therefore is not suitable here.
            originalVariable.tiedStore(new RuntimeScalar());
            localizedStack.push(
                    new SavedGlobalState(fullName, originalVariable, savedValue, null, null));
            // Do NOT replace the slot — the tied scalar stays in place so
            // that the subsequent `= value` assignment dispatches STORE.
            return;
        }

        // Create a new variable for the localized scope.
        // For output separator variables, create the matching special type so that
        // set() in the localized scope correctly updates the internal value that print reads.
        // Also save the internal separator value for restoration.
        RuntimeScalar newLocal;
        if (originalVariable instanceof OutputRecordSeparator) {
            OutputRecordSeparator.saveInternalORS();
            newLocal = new OutputRecordSeparator();
            newLocal.set(RuntimeScalarCache.scalarUndef);
        } else if (originalVariable instanceof OutputFieldSeparator) {
            OutputFieldSeparator.saveInternalOFS();
            newLocal = new OutputFieldSeparator();
            newLocal.set(RuntimeScalarCache.scalarUndef);
        } else if (originalVariable instanceof OperatingSystemVariable) {
            newLocal = new OperatingSystemVariable("");
            newLocal.set(RuntimeScalarCache.scalarUndef);
        } else {
            newLocal = new GlobalRuntimeScalar(fullName);
        }

        String savedRuntimeWarningBits = fullName.equals(GlobalContext.WARNING_SCOPE)
                ? WarningBitsRegistry.getRuntimeWarningBits() : null;
        localizedStack.push(new SavedGlobalState(
                fullName, originalVariable, null, newLocal, savedRuntimeWarningBits));

        // Replace this variable in the global symbol table with the new one
        GlobalVariable.globalVariables.put(fullName, newLocal);

        // Also update all glob aliases to point to the new local variable.
        // This implements Perl 5 semantics where after `*verbose = *Verbose`,
        // `local $verbose = 1` also affects `$Verbose`.
        java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(fullName);
        for (String alias : aliasGroup) {
            if (!alias.equals(fullName)) {
                GlobalVariable.globalVariables.put(alias, newLocal);
            }
        }
    }

    @Override
    public void dynamicRestoreState() {
        Stack<SavedGlobalState> localizedStack = localizedStack();
        if (!localizedStack.isEmpty()) {
            SavedGlobalState saved = localizedStack.peek();
            if (saved.fullName.equals(this.fullName)) {
                localizedStack.pop();

                // Tied path: the slot was never replaced. Restore the
                // original value by dispatching STORE on the tied scalar.
                if (saved.originalVariable != null
                        && saved.originalVariable.type == RuntimeScalarType.TIED_SCALAR) {
                    if (saved.savedTiedValue != null) {
                        saved.originalVariable.tiedStore(saved.savedTiedValue);
                    }
                    return;
                }

                // The global slot may currently be aliased to a foreach element
                // (notably localized $_). Clean up only the scalar object that
                // local() installed; mutating the current slot can corrupt the
                // aliased iterator value.
                GlobalVariable.clearForeachGlobalAlias(saved.fullName);
                RuntimeScalar localVar = saved.localizedVariable;
                RuntimeBase displacedBase = null;
                RuntimeScalar scalarReferenceContents = null;
                // A localized package scalar can hold a blessed hash/array
                // object (for example Test::Deep's local $CompareCache).
                // Release container-owned values before the temporary scalar
                // is discarded, just as lexical-scope cleanup does.
                if (localVar != null && localVar.value instanceof RuntimeHash localHash) {
                    MortalList.deferDestroyForContainerClear(localHash.elements.values());
                } else if (localVar != null && localVar.value instanceof RuntimeArray localArray) {
                    MortalList.deferDestroyForContainerClear(localArray.elements);
                }
                if (localVar != null
                        && localVar.refCountOwned
                        && (localVar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                        && localVar.value instanceof RuntimeBase base
                        && base.refCount > 0) {
                    displacedBase = base;
                    localVar.refCountOwned = false;
                    displacedBase.releaseActiveOwner(localVar);
                }
                if (localVar != null
                        && localVar.ownsScalarReferenceContents
                        && localVar.type == RuntimeScalarType.REFERENCE
                        && localVar.value instanceof RuntimeScalar scalarReferent) {
                    scalarReferenceContents = scalarReferent;
                }

                // Restore the internal separator values if this was an output separator variable
                if (saved.originalVariable instanceof OutputRecordSeparator) {
                    OutputRecordSeparator.restoreInternalORS();
                } else if (saved.originalVariable instanceof OutputFieldSeparator) {
                    OutputFieldSeparator.restoreInternalOFS();
                }

                // Restore the original variable in the global symbol table
                GlobalVariable.globalVariables.put(saved.fullName, saved.originalVariable);

                if (saved.fullName.equals(GlobalContext.WARNING_SCOPE)) {
                    WarningBitsRegistry.setRuntimeWarningBits(saved.savedRuntimeWarningBits);
                }

                // Also restore all glob aliases to the original shared variable
                java.util.List<String> aliasGroup = GlobalVariable.getGlobAliasGroup(saved.fullName);
                for (String alias : aliasGroup) {
                    if (!alias.equals(saved.fullName)) {
                        GlobalVariable.globalVariables.put(alias, saved.originalVariable);
                    }
                }

                // Decrement refCount of the displaced local value after restoring
                // the global slot, so DESTROY sees the restored variable state.
                boolean displacedWillDestroy = false;
                if (displacedBase != null && displacedBase.refCount == 1) {
                    String className = NameNormalizer.getBlessStr(displacedBase.blessId);
                    displacedWillDestroy = className != null && !className.isEmpty()
                            && DestroyDispatch.classHasDestroy(displacedBase.blessId, className);
                }
                if ((displacedWillDestroy || scalarReferenceContents != null)
                        && localVar != null && localVar != saved.originalVariable) {
                    // Compiled code may still hold the localized scalar object
                    // directly. Mirror the restored value into it just before
                    // DESTROY so both lookup paths observe the restored state.
                    if (saved.originalVariable == null) {
                        localVar.type = RuntimeScalarType.UNDEF;
                        localVar.value = null;
                        localVar.blessId = 0;
                    } else {
                        localVar.type = saved.originalVariable.type;
                        localVar.value = saved.originalVariable.value;
                        localVar.blessId = saved.originalVariable.blessId;
                    }
                    localVar.refCountOwned = false;
                }
                if (localVar != null) {
                    localVar.ownsScalarReferenceContents = false;
                }
                RuntimeScalar.releaseScalarReferenceContents(scalarReferenceContents);
                if (displacedBase != null && displacedBase.refCount > 0
                        && --displacedBase.refCount == 0) {
                    displacedBase.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(displacedBase);
                }
            }
        }
    }

    @Override
    public Object dynamicSuspendState() {
        RuntimeScalar activeVariable = GlobalVariable.getGlobalVariable(fullName);
        RuntimeScalar activeState = activeVariable != null
                ? new RuntimeScalar(activeVariable) : new RuntimeScalar();
        boolean tied = activeVariable != null
                && activeVariable.type == RuntimeScalarType.TIED_SCALAR;
        RuntimeScalar tiedValue = tied ? activeVariable.tiedFetch() : null;
        dynamicRestoreState();
        return new SuspendedGlobalScalar(activeState, tiedValue, tied);
    }

    @Override
    public void dynamicResumeState(Object token) {
        dynamicSaveState();
        if (token instanceof SuspendedGlobalScalar suspended) {
            RuntimeScalar activeVariable = GlobalVariable.getGlobalVariable(fullName);
            if (activeVariable != null) {
                if (suspended.tied) {
                    activeVariable.tiedStore(suspended.tiedValue);
                    return;
                }
                RuntimeScalar activeState = suspended.value;
                activeVariable.type = activeState.type;
                activeVariable.value = activeState.value;
                activeVariable.blessId = activeState.blessId;
                activeVariable.ownsScalarReferenceContents = activeState.ownsScalarReferenceContents;
                activeVariable.referencedByScalarReference = activeState.referencedByScalarReference;
                activeVariable.tainted = activeState.tainted;
                activeVariable.numericLiteralText = activeState.numericLiteralText;
                activeVariable.numericContextSeen = activeState.numericContextSeen;
            }
        }
    }

    private record SuspendedGlobalScalar(
            RuntimeScalar value, RuntimeScalar tiedValue, boolean tied) {
    }

    private record SavedGlobalState(
            String fullName,
            RuntimeScalar originalVariable,
            RuntimeScalar savedTiedValue,
            RuntimeScalar localizedVariable,
            String savedRuntimeWarningBits) {
    }
}

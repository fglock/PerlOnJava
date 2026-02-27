package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;

public class RuntimeRegexState implements DynamicState {

    private Object[] savedState;

    public static void pushLocal() {
        DynamicVariableManager.pushLocalDynamicState(new RuntimeRegexState());
    }

    @Override
    public void dynamicSaveState() {
        savedState = RuntimeRegex.saveMatchState();
    }

    @Override
    public void dynamicRestoreState() {
        if (savedState != null) {
            RuntimeRegex.restoreMatchState(savedState);
            savedState = null;
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

/** A Future::AsyncAwait CANCEL registration owned by a suspended frame. */
public final class CancelBlock implements DynamicState {
    private final RuntimeScalar codeRef;
    private final RuntimeArray capturedArgs;

    public CancelBlock(RuntimeScalar codeRef, RuntimeArray capturedArgs) {
        this.codeRef = codeRef;
        this.capturedArgs = capturedArgs;
    }

    @Override
    public void dynamicSaveState() {
        // Registration only.
    }

    @Override
    public void dynamicRestoreState() {
        // Normal completion or failure discards CANCEL blocks.
    }

    @Override
    public Object dynamicSuspendState() {
        return null;
    }

    @Override
    public void dynamicResumeState(Object token) {
        // Registration itself is the state.
    }

    public void run() {
        RuntimeCode.apply(codeRef, capturedArgs, RuntimeContextType.VOID);
    }
}

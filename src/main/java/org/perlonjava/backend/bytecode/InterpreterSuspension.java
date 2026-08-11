package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.MortalList;

/** Internal result used only between the interpreter and async-sub wrapper. */
final class InterpreterSuspension extends RuntimeList {
    final SuspendedInterpreterFrame frame;
    final RuntimeScalar awaited;
    private final RuntimeScalar awaitedOwner;
    private final RuntimeBase awaitedRoot;
    private boolean awaitedRootRetained;
    final int destinationRegister;
    final int context;

    InterpreterSuspension(SuspendedInterpreterFrame frame, RuntimeScalar awaited,
                          int destinationRegister, int context) {
        this.frame = frame;
        this.awaited = awaited;
        this.awaitedOwner = new RuntimeScalar();
        this.awaitedOwner.set(awaited);
        this.awaitedRoot = RuntimeScalarType.isReference(awaited)
                && awaited.value instanceof RuntimeBase base ? base : null;
        MortalList.retainSuspendedRoot(awaitedRoot);
        this.awaitedRootRetained = awaitedRoot != null;
        this.destinationRegister = destinationRegister;
        this.context = context;
    }

    void setResult(RuntimeBase value) {
        frame.registers[destinationRegister] = value;
    }

    void releaseAwaitedOwner() {
        if (awaitedOwner.getDefinedBoolean()) {
            awaitedOwner.set(new RuntimeScalar());
        }
        if (awaitedRootRetained) {
            MortalList.releaseSuspendedRoot(awaitedRoot);
            awaitedRootRetained = false;
        }
    }
}

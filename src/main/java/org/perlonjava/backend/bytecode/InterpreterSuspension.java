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
        // Await operands can be mutable lvalue scalars backed by aggregate
        // slots. Snapshot the Future value: its owner may clear or replace the
        // slot before the readiness callback resumes this frame.
        this.awaited = new RuntimeScalar();
        this.awaited.set(awaited);
        this.awaitedOwner = this.awaited;
        this.awaitedRoot = RuntimeScalarType.isReference(this.awaited)
                && this.awaited.value instanceof RuntimeBase base ? base : null;
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

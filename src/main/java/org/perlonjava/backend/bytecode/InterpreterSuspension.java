package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Internal result used only between the interpreter and async-sub wrapper. */
final class InterpreterSuspension extends RuntimeList {
    final SuspendedInterpreterFrame frame;
    final RuntimeScalar awaited;
    final int destinationRegister;
    final int context;

    InterpreterSuspension(SuspendedInterpreterFrame frame, RuntimeScalar awaited,
                          int destinationRegister, int context) {
        this.frame = frame;
        this.awaited = awaited;
        this.destinationRegister = destinationRegister;
        this.context = context;
    }

    void setResult(RuntimeBase value) {
        frame.registers[destinationRegister] = value;
    }
}

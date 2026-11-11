package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.WeakRefRegistry;

/** Internal result used only between the interpreter and async-sub wrapper. */
final class InterpreterSuspension extends RuntimeList {
    final SuspendedInterpreterFrame frame;
    final RuntimeScalar awaited;
    private final RuntimeScalar awaitedOwner;
    private final PerlOwnerSlot awaitedFrameOwner;
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
        this.awaitedFrameOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SUSPENDED_FRAME);
        awaitedOwner.attachSuspendedFrameOwner(awaitedFrameOwner);
        if (frame != null) frame.setAwaitedFrameOwner(awaitedFrameOwner);
        this.destinationRegister = destinationRegister;
        this.context = context;
    }

    void setResult(RuntimeBase value) {
        frame.registers[destinationRegister] = value;
    }

    void releaseAwaitedOwner() {
        RuntimeBase previousReferent = awaitedFrameOwner.referent();
        try {
            if (awaitedOwner.getDefinedBoolean()) {
                awaitedOwner.set(new RuntimeScalar());
            }
        } finally {
            try {
                awaitedOwner.detachSuspendedFrameOwner(awaitedFrameOwner);
            } finally {
                try {
                    if (frame != null) frame.clearAwaitedFrameOwner(awaitedFrameOwner);
                } finally {
                    WeakRefRegistry.onSuspendedFrameOwnerReleased(previousReferent);
                }
            }
        }
    }
}

package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime bridge to the Future::AsyncAwait::Awaitable method contract. */
final class FutureAsyncAwaitRuntime {
    private static final RuntimeScalar EMPTY_CURRENT_SUB = new RuntimeScalar("");
    private static final ThreadLocal<ArrayDeque<Runnable>> RESUME_QUEUE =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> DRAINING =
            ThreadLocal.withInitial(() -> false);

    private FutureAsyncAwaitRuntime() {
    }

    static boolean isReady(RuntimeScalar future) {
        return call(future, "AWAIT_IS_READY", new RuntimeArray(), RuntimeContextType.SCALAR)
                .scalar().getBoolean();
    }

    static RuntimeBase get(RuntimeScalar future, int context) {
        RuntimeList result = call(future, "AWAIT_GET", new RuntimeArray(), context);
        if (context == RuntimeContextType.VOID) {
            return new RuntimeList();
        }
        if (context == RuntimeContextType.LIST) {
            return result;
        }
        return result.scalar();
    }

    static RuntimeList wrapInitialResult(int effectiveContext, int requestedContext,
                                         RuntimeList result) {
        RuntimeScalar outer;
        if (result instanceof InterpreterSuspension suspension) {
            outer = call(suspension.awaited, "AWAIT_CLONE", new RuntimeArray(),
                    RuntimeContextType.SCALAR).scalar();
            attach(outer, suspension, new AwaitState());
        } else {
            outer = completedFuture(result);
        }
        return RuntimeCode.coerceScalarCallResult(new RuntimeList(outer),
                effectiveContext, requestedContext);
    }

    static RuntimeList failedInitialResult(Throwable error, int effectiveContext,
                                           int requestedContext) {
        RuntimeScalar outer = call(new RuntimeScalar("Future"), "AWAIT_NEW_FAIL",
                new RuntimeArray(exceptionValue(error)), RuntimeContextType.SCALAR).scalar();
        return RuntimeCode.coerceScalarCallResult(new RuntimeList(outer),
                effectiveContext, requestedContext);
    }

    private static RuntimeScalar completedFuture(RuntimeList result) {
        return call(new RuntimeScalar("Future"), "AWAIT_NEW_DONE",
                new RuntimeArray(result), RuntimeContextType.SCALAR).scalar();
    }

    private static void attach(RuntimeScalar outer, InterpreterSuspension suspension,
                               AwaitState state) {
        // Future::AsyncAwait requires cancellation to flow from the Future returned by
        // the async sub to the Future currently being awaited.  Do this for every
        // suspension segment, since a resumed frame may await a different Future.
        call(outer, "AWAIT_CHAIN_CANCEL",
                new RuntimeArray(suspension.awaited), RuntimeContextType.VOID);

        RuntimeCode callback = new RuntimeCode((callbackArgs, callbackContext) -> {
            enqueue(() -> resume(outer, suspension, state));
            return new RuntimeList();
        }, null);
        call(suspension.awaited, "AWAIT_ON_READY",
                new RuntimeArray(new RuntimeScalar(callback)), RuntimeContextType.VOID);
    }

    private static void resume(RuntimeScalar outer, InterpreterSuspension suspension,
                               AwaitState state) {
        // A Future implementation may invoke a readiness callback more than once,
        // and cancellation may race with that callback.  The returned Future owns
        // the terminal transition; the first terminal path wins.
        if (state.terminal.get()) {
            return;
        }
        if (!state.callbackActive.compareAndSet(false, true)) {
            return;
        }
        if (isCancelled(outer)) {
            state.terminal.set(true);
            cleanupAbandonedFrame(suspension.frame);
            state.callbackActive.set(false);
            return;
        }
        try {
            if (isCancelled(outer)) {
                state.terminal.set(true);
                cleanupAbandonedFrame(suspension.frame);
                return;
            }
            try {
                suspension.setResult(get(suspension.awaited, suspension.context));
            } catch (Throwable error) {
                suspension.frame.resumeException = error;
            }

            RuntimeList result = resumeWithCallState(suspension.frame);
            if (result instanceof InterpreterSuspension next) {
                attach(outer, next, state);
                return;
            }
            if (!state.terminal.compareAndSet(false, true) || isCancelled(outer)) {
                return;
            }
            call(outer, "AWAIT_DONE", new RuntimeArray(result), RuntimeContextType.VOID);
        } catch (Throwable error) {
            if (!state.terminal.compareAndSet(false, true) || isCancelled(outer)) {
                return;
            }
            call(outer, "AWAIT_FAIL", new RuntimeArray(exceptionValue(error)),
                    RuntimeContextType.VOID);
        } finally {
            if (!state.terminal.get()) {
                state.callbackActive.set(false);
            }
        }
    }

    private static boolean isCancelled(RuntimeScalar future) {
        return call(future, "AWAIT_IS_CANCELLED", new RuntimeArray(),
                RuntimeContextType.SCALAR).scalar().getBoolean();
    }

    /**
     * Release a suspended frame when cancellation prevents it from resuming.
     * Reattach the detached dynamic states on this callback thread and pop them
     * normally so defer blocks run and localized values are restored exactly once.
     */
    private static void cleanupAbandonedFrame(SuspendedInterpreterFrame frame) {
        List<DynamicVariableManager.SuspendedState> states = frame.suspendedDynamicStates;
        if (states == null) return;
        frame.suspendedDynamicStates = null;

        InterpretedCode code = frame.code;
        RuntimeArray args = (RuntimeArray) frame.registers[1];
        RuntimeCode.pushArgs(args);
        RuntimeCode.pushCallContext(frame.callContext);
        RuntimeCode.pushActiveCode(code);
        if (code.warningBitsString != null) {
            WarningBitsRegistry.pushCurrent(code.warningBitsString);
        }
        int cleanupMark = MyVarCleanupStack.pushMark();
        int localLevel = DynamicVariableManager.getLocalLevel();
        try {
            DynamicVariableManager.resumeSuspended(states);
            DynamicVariableManager.popToLocalLevel(localLevel);
        } finally {
            MyVarCleanupStack.popMark(cleanupMark);
            if (code.warningBitsString != null) {
                WarningBitsRegistry.popCurrent();
            }
            RuntimeCode.popActiveCode(code);
            RuntimeCode.popArgs();
        }
    }

    private static final class AwaitState {
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicBoolean callbackActive = new AtomicBoolean();
    }

    private static RuntimeList resumeWithCallState(SuspendedInterpreterFrame frame) {
        InterpretedCode code = frame.code;
        RuntimeArray args = (RuntimeArray) frame.registers[1];
        RuntimeCode.pushArgs(args);
        RuntimeCode.pushCallContext(frame.callContext);
        RuntimeCode.pushActiveCode(code);
        if (code.warningBitsString != null) {
            WarningBitsRegistry.pushCurrent(code.warningBitsString);
        }
        int cleanupMark = MyVarCleanupStack.pushMark();
        try {
            return BytecodeInterpreter.resume(frame);
        } finally {
            MyVarCleanupStack.popMark(cleanupMark);
            if (code.warningBitsString != null) {
                WarningBitsRegistry.popCurrent();
            }
            RuntimeCode.popActiveCode(code);
            RuntimeCode.popArgs();
        }
    }

    private static RuntimeBase exceptionValue(Throwable error) {
        if (error instanceof PerlDieException die && die.getPayload() != null) {
            return die.getPayload().getFirst();
        }
        String message = error.getMessage();
        return new RuntimeScalar(message != null ? message : error.toString());
    }

    private static RuntimeList call(RuntimeScalar invocant, String method,
                                    RuntimeArray args, int context) {
        return RuntimeCode.call(invocant, new RuntimeScalar(method), EMPTY_CURRENT_SUB,
                args, context);
    }

    private static void enqueue(Runnable action) {
        ArrayDeque<Runnable> queue = RESUME_QUEUE.get();
        queue.addLast(action);
        if (DRAINING.get()) {
            return;
        }
        DRAINING.set(true);
        try {
            while (!queue.isEmpty()) {
                queue.removeFirst().run();
            }
        } finally {
            DRAINING.set(false);
            queue.clear();
        }
    }
}

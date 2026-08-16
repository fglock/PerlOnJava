package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime bridge to the Future::AsyncAwait::Awaitable method contract. */
public final class FutureAsyncAwaitRuntime {
    private static final RuntimeScalar EMPTY_CURRENT_SUB = new RuntimeScalar("");
    private FutureAsyncAwaitRuntime() {
    }

    /** True while an async sub's interpreter-owned frame is executing. */
    public static boolean isExecutingAsyncSub() {
        return RuntimeCode.hasActiveFutureAsyncAwaitSub();
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

    /** Execute a file-scope await through the Awaitable blocking-wait contract. */
    public static RuntimeBase wait(RuntimeBase future, int context) {
        RuntimeScalar awaited = future instanceof RuntimeScalar scalar
                ? scalar : future.scalar();
        RuntimeList result = call(awaited, "AWAIT_WAIT", new RuntimeArray(), context);
        if (context == RuntimeContextType.VOID) {
            return new RuntimeList();
        }
        if (context == RuntimeContextType.LIST) {
            return result;
        }
        return result.scalar();
    }

    static RuntimeList wrapInitialResult(int effectiveContext, int requestedContext,
                                         RuntimeList result, String futureClass) {
        RuntimeScalar outer;
        if (result instanceof InterpreterSuspension suspension) {
            outer = futureClass == null
                    ? call(suspension.awaited, "AWAIT_CLONE", new RuntimeArray(),
                            RuntimeContextType.SCALAR).scalar()
                    : call(new RuntimeScalar(futureClass), "new", new RuntimeArray(),
                            RuntimeContextType.SCALAR).scalar();
            AwaitState state = new AwaitState(outer);
            attach(suspension, state);
        } else {
            outer = completedFuture(result, futureClass);
        }
        return RuntimeCode.coerceScalarCallResult(new RuntimeList(outer),
                effectiveContext, requestedContext);
    }

    static RuntimeList failedInitialResult(Throwable error, int effectiveContext,
                                           int requestedContext, String futureClass) {
        RuntimeScalar outer = call(new RuntimeScalar(futureClass(futureClass)), "AWAIT_NEW_FAIL",
                new RuntimeArray(exceptionValue(error)), RuntimeContextType.SCALAR).scalar();
        return RuntimeCode.coerceScalarCallResult(new RuntimeList(outer),
                effectiveContext, requestedContext);
    }

    private static RuntimeScalar completedFuture(RuntimeList result, String futureClass) {
        return call(new RuntimeScalar(futureClass(futureClass)), "AWAIT_NEW_DONE",
                new RuntimeArray(result), RuntimeContextType.SCALAR).scalar();
    }

    private static String futureClass(String configured) {
        return configured == null ? "Future" : configured;
    }

    private static void attach(InterpreterSuspension suspension, AwaitState state) {
        RuntimeScalar outer = state.outer();
        if (outer == null) {
            state.terminal.set(true);
            warnLostReturningFuture(suspension.frame);
            suspension.releaseAwaitedOwner();
            cleanupAbandonedFrame(suspension.frame);
            return;
        }
        // Future::AsyncAwait requires cancellation to flow from the Future returned by
        // the async sub to the Future currently being awaited.  Do this for every
        // suspension segment, since a resumed frame may await a different Future.
        call(outer, "AWAIT_CHAIN_CANCEL",
                new RuntimeArray(suspension.awaited), RuntimeContextType.VOID);

        PerlRuntime runtime = PerlRuntime.current();
        RuntimeCode callback = new RuntimeCode((callbackArgs, callbackContext) -> {
            enqueue(() -> resume(suspension, state));
            return new RuntimeList();
        }, null).bindCallbackTo(runtime);
        call(suspension.awaited, "AWAIT_ON_READY",
                new RuntimeArray(new RuntimeScalar(callback)), RuntimeContextType.VOID);
    }

    private static void resume(InterpreterSuspension suspension, AwaitState state) {
        // A Future implementation may invoke a readiness callback more than once,
        // and cancellation may race with that callback.  The returned Future owns
        // the terminal transition; the first terminal path wins.
        if (state.terminal.get()) {
            return;
        }
        if (!state.callbackActive.compareAndSet(false, true)) {
            return;
        }
        RuntimeScalar outer = state.outer();
        if (outer == null) {
            state.terminal.set(true);
            warnLostReturningFuture(suspension.frame);
            suspension.releaseAwaitedOwner();
            cleanupAbandonedFrame(suspension.frame);
            state.callbackActive.set(false);
            return;
        }
        if (isCancelled(outer)) {
            state.terminal.set(true);
            suspension.releaseAwaitedOwner();
            cleanupAbandonedFrame(suspension.frame);
            state.clearOuterProbe();
            state.callbackActive.set(false);
            return;
        }
        try {
            if (isCancelled(outer)) {
                state.terminal.set(true);
                suspension.releaseAwaitedOwner();
                cleanupAbandonedFrame(suspension.frame);
                state.clearOuterProbe();
                return;
            }
            try {
                suspension.setResult(get(suspension.awaited, suspension.context));
            } catch (Throwable error) {
                suspension.frame.resumeException = error;
            } finally {
                suspension.releaseAwaitedOwner();
            }

            RuntimeList result = resumeWithCallState(suspension.frame);
            if (result instanceof InterpreterSuspension next) {
                attach(next, state);
                return;
            }
            outer = state.outer();
            if (outer == null) {
                state.terminal.set(true);
                warnLostReturningFuture(suspension.frame);
                return;
            }
            if (!state.terminal.compareAndSet(false, true)) {
                return;
            }
            if (isCancelled(outer)) {
                state.clearOuterProbe();
                return;
            }
            call(outer, "AWAIT_DONE", new RuntimeArray(result), RuntimeContextType.VOID);
            state.clearOuterProbe();
        } catch (Throwable error) {
            outer = state.outer();
            if (outer == null) {
                state.terminal.set(true);
                warnAbandonedFailure(suspension.frame, error);
                return;
            }
            if (!state.terminal.compareAndSet(false, true)) {
                return;
            }
            if (isCancelled(outer)) {
                state.clearOuterProbe();
                return;
            }
            call(outer, "AWAIT_FAIL", new RuntimeArray(exceptionValue(error)),
                    RuntimeContextType.VOID);
            state.clearOuterProbe();
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
            for (int i = states.size() - 1; i >= 0; i--) {
                if (states.get(i).state() instanceof CancelBlock cancelBlock) {
                    cancelBlock.run();
                }
            }
            DynamicVariableManager.popToLocalLevel(localLevel);
        } finally {
            BytecodeInterpreter.abandon(frame);
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

        private final RuntimeScalar outerWeak;

        AwaitState(RuntimeScalar outer) {
            outerWeak = new RuntimeScalar(outer);
            WeakRefRegistry.weaken(outerWeak);
        }

        RuntimeScalar outer() {
            return outerWeak.getDefinedBoolean() ? outerWeak : null;
        }

        void clearOuterProbe() {
            if (outerWeak.getDefinedBoolean()) {
                outerWeak.set(new RuntimeScalar());
            }
        }
    }

    private static void warnLostReturningFuture(SuspendedInterpreterFrame frame) {
        warn("Suspended async sub " + asyncSubDisplayName(frame.code)
                + " lost its returning future at " + frame.code.sourceName
                + " line " + Math.max(1, frame.code.cvStartLine) + ".\n");
    }

    private static void warnAbandonedFailure(SuspendedInterpreterFrame frame, Throwable error) {
        String message = exceptionValue(error).toString();
        warn("Abandoned async sub " + asyncSubDisplayName(frame.code)
                + " failed: " + message
                + (message.endsWith("\n") ? "" : "\n"));
    }

    private static String asyncSubDisplayName(InterpretedCode code) {
        if (code.subName != null && !code.subName.isEmpty()
                && !code.subName.equals("(eval)")) {
            String pkg = code.packageName == null || code.packageName.isEmpty()
                    ? "main" : code.packageName;
            return code.subName.contains("::") ? code.subName : pkg + "::" + code.subName;
        }
        String pkg = code.packageName == null || code.packageName.isEmpty()
                ? "main" : code.packageName;
        return "CODE(0x" + Integer.toHexString(System.identityHashCode(code))
                + ") in package " + pkg;
    }

    private static void warn(String message) {
        org.perlonjava.runtime.operators.WarnDie.warn(
                new RuntimeScalar(message), new RuntimeScalar("misc"));
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
        ExecutionRuntimeState state = PerlRuntime.current().executionState();
        var queue = state.futureResumeQueue;
        queue.addLast(action);
        if (state.futureResumeDraining) {
            return;
        }
        state.futureResumeDraining = true;
        try {
            while (!queue.isEmpty()) {
                queue.removeFirst().run();
            }
        } finally {
            state.futureResumeDraining = false;
            queue.clear();
        }
    }
}

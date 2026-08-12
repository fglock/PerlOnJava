package org.perlonjava.runtime.runtimetypes;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe queue for pending Perl signals.
 * This allows signal handlers to execute in the original thread context
 * rather than the timer thread context.
 */
public class PerlSignalQueue {
    public static final class State {
        final Queue<SignalEvent> signalQueue = new ConcurrentLinkedQueue<>();
        volatile boolean hasPendingSignal;
    }

    private static State state() {
        return PerlRuntime.current().signalState;
    }

    /**
     * Enqueue a signal for processing in the main thread.
     *
     * @param signal  The signal name (e.g., "ALRM")
     * @param handler The signal handler to execute
     */
    public static void enqueue(String signal, RuntimeScalar handler) {
        State state = state();
        state.signalQueue.offer(new SignalEvent(signal, handler));
        state.hasPendingSignal = true;  // Set flag for fast checking
    }

    /**
     * Lightweight signal check - called frequently at safe execution points.
     * If no signals are pending, this is just a volatile boolean read (~2 CPU cycles).
     * Signal handlers may throw PerlCompilerException which will propagate.
     */
    public static void checkPendingSignals() {
        if (!state().hasPendingSignal) {
            return;  // Fast path: no signals pending
        }
        // Slow path: process signals (rare)
        processSignalsImpl();
    }

    /**
     * Process all pending signals in the current thread.
     * This method should be called at safe execution points.
     * Signal handlers may throw PerlDieException which will propagate.
     */
    public static void processSignals() {
        processSignalsImpl();
    }

    /**
     * Internal implementation of signal processing.
     */
    private static void processSignalsImpl() {
        State state = state();
        Thread.interrupted();
        SignalEvent event;
        while ((event = state.signalQueue.poll()) != null) {
            state.hasPendingSignal = !state.signalQueue.isEmpty();
            RuntimeArray args = new RuntimeArray();
            args.push(new RuntimeScalar(event.signal));
            RuntimeCode.apply(event.handler, args, RuntimeContextType.VOID);
        }
        state.hasPendingSignal = false;
    }

    /**
     * Check if there are any pending signals.
     *
     * @return true if signals are pending
     */
    public static boolean hasPendingSignals() {
        return !state().signalQueue.isEmpty();
    }

    /**
     * Clear all pending signals (used for cleanup).
     */
    public static void clearSignals() {
        State state = state();
        state.signalQueue.clear();
        state.hasPendingSignal = false;
    }

    static class SignalEvent {
        String signal;
        RuntimeScalar handler;

        SignalEvent(String signal, RuntimeScalar handler) {
            this.signal = signal;
            this.handler = handler;
        }
    }
}

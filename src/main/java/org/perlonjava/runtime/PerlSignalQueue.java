package org.perlonjava.runtime;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe queue for pending Perl signals.
 * This allows signal handlers to execute in the original thread context
 * rather than the timer thread context.
 */
public class PerlSignalQueue {
    private static final Queue<SignalEvent> signalQueue = new ConcurrentLinkedQueue<>();
    
    // Volatile flag for ultra-fast signal checking in hot paths
    // Reading a volatile boolean is ~2 CPU cycles, much faster than queue.isEmpty()
    private static volatile boolean hasPendingSignal = false;

    /**
     * Enqueue a signal for processing in the main thread.
     *
     * @param signal  The signal name (e.g., "ALRM")
     * @param handler The signal handler to execute
     */
    public static void enqueue(String signal, RuntimeScalar handler) {
        signalQueue.offer(new SignalEvent(signal, handler));
        hasPendingSignal = true;  // Set flag for fast checking
    }

    /**
     * Lightweight signal check - called frequently at safe execution points.
     * If no signals are pending, this is just a volatile boolean read (~2 CPU cycles).
     * Signal handlers may throw PerlCompilerException which will propagate.
     */
    public static void checkPendingSignals() {
        if (!hasPendingSignal) {
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
        SignalEvent event;
        while ((event = signalQueue.poll()) != null) {
            // Execute the handler - this may throw PerlCompilerException (from die)
            RuntimeArray args = new RuntimeArray();
            args.push(new RuntimeScalar(event.signal));
            RuntimeCode.apply(event.handler, args, RuntimeContextType.VOID);
            // Note: If the handler throws an exception, it will propagate immediately
            // and we won't process remaining signals in the queue
        }
        hasPendingSignal = false;  // Clear flag after processing all signals
    }

    /**
     * Check if there are any pending signals.
     *
     * @return true if signals are pending
     */
    public static boolean hasPendingSignals() {
        return !signalQueue.isEmpty();
    }

    /**
     * Clear all pending signals (used for cleanup).
     */
    public static void clearSignals() {
        signalQueue.clear();
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

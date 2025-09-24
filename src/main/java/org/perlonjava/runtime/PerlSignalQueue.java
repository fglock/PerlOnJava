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
    
    static class SignalEvent {
        String signal;
        RuntimeScalar handler;
        
        SignalEvent(String signal, RuntimeScalar handler) {
            this.signal = signal;
            this.handler = handler;
        }
    }
    
    /**
     * Enqueue a signal for processing in the main thread.
     * 
     * @param signal The signal name (e.g., "ALRM")
     * @param handler The signal handler to execute
     */
    public static void enqueue(String signal, RuntimeScalar handler) {
        signalQueue.offer(new SignalEvent(signal, handler));
    }
    
    /**
     * Process all pending signals in the current thread.
     * This method should be called at safe execution points.
     * Signal handlers may throw PerlDieException which will propagate.
     */
    public static void processSignals() {
        SignalEvent event;
        while ((event = signalQueue.poll()) != null) {
            try {
                // Execute the handler - this may throw PerlDieException
                RuntimeArray args = new RuntimeArray();
                args.push(new RuntimeScalar(event.signal));
                RuntimeCode.apply(event.handler, args, RuntimeContextType.VOID);
            } catch (Exception e) {
                // Let PerlCompilerException propagate, but catch other exceptions
                if (e instanceof PerlCompilerException) {
                    throw e;
                }
                System.err.println("Signal handler error for " + event.signal + ": " + e.getMessage());
            }
        }
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
}

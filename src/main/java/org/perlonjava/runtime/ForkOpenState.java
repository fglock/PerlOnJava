package org.perlonjava.runtime;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Thread-local state for fork-open emulation.
 * 
 * <p>This class manages the state needed to emulate Perl's fork-open pattern
 * ({@code open FH, "-|"}) on the JVM, which doesn't support fork().
 * 
 * <h2>How Fork-Open Emulation Works</h2>
 * 
 * <p>In Perl, {@code my $pid = open FH, "-|"} forks the process:
 * <ul>
 *   <li>Parent gets child's PID, reads from FH (child's stdout)</li>
 *   <li>Child gets 0, typically calls exec to run a command</li>
 * </ul>
 * 
 * <p>Since JVM can't fork, we emulate this pattern:
 * <ol>
 *   <li>When {@code open FH, "-|"} is called without a command, we store a
 *       pending state and return 0 (pretending to be the "child")</li>
 *   <li>When {@code exec @cmd} is called, we detect the pending state,
 *       create the pipe using 3-arg semantics, and signal to return to
 *       the "parent" code path with the pipe ready</li>
 * </ol>
 * 
 * <h2>Supported Patterns</h2>
 * <pre>{@code
 * # Classic if/else
 * my $pid = open FH, "-|";
 * if ($pid) { ... } else { exec @cmd }
 * 
 * # or-exec idiom
 * open FH, "-|" or exec @cmd;
 * }</pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>State is stored in ThreadLocal, so each thread has its own pending state.
 * 
 * @see org.perlonjava.runtime.operators.IOOperator#open
 * @see org.perlonjava.runtime.operators.SystemOperator#exec
 */
public class ForkOpenState {
    
    /**
     * Thread-local storage for pending fork-open state.
     */
    private static final ThreadLocal<PendingForkOpen> pendingState = new ThreadLocal<>();
    
    /**
     * Represents a pending fork-open operation waiting for exec to complete it.
     */
    public static class PendingForkOpen {
        /** The filehandle scalar that will receive the pipe */
        public final RuntimeScalar fileHandle;
        
        /** Token index for error messages */
        public final int tokenIndex;
        
        /** I/O layers to apply (e.g., ":utf8") */
        public final String ioLayers;
        
        public PendingForkOpen(RuntimeScalar fileHandle, int tokenIndex, String ioLayers) {
            this.fileHandle = fileHandle;
            this.tokenIndex = tokenIndex;
            this.ioLayers = ioLayers != null ? ioLayers : "";
        }
    }
    
    /**
     * Sets a pending fork-open state.
     * 
     * <p>Called by {@code open FH, "-|"} when no command is provided (fork mode).
     * The state will be consumed by the next {@code exec} call.
     * 
     * @param fileHandle The filehandle scalar to set up when exec is called
     * @param tokenIndex Token index for error reporting
     * @param ioLayers Optional I/O layers (e.g., ":utf8")
     */
    public static void setPending(RuntimeScalar fileHandle, int tokenIndex, String ioLayers) {
        pendingState.set(new PendingForkOpen(fileHandle, tokenIndex, ioLayers));
    }
    
    /**
     * Gets the current pending fork-open state.
     * 
     * @return The pending state, or null if none
     */
    public static PendingForkOpen getPending() {
        return pendingState.get();
    }
    
    /**
     * Clears any pending fork-open state.
     * 
     * <p>Called by:
     * <ul>
     *   <li>{@code open} - at the start of any open operation</li>
     *   <li>{@code close} - when closing filehandles</li>
     *   <li>{@code exec} - after successfully completing a fork-open</li>
     *   <li>Error handlers - to prevent stale state</li>
     * </ul>
     */
    public static void clear() {
        pendingState.remove();
    }
    
    /**
     * Checks if there's a pending fork-open waiting for exec.
     * 
     * @return true if a fork-open is pending
     */
    public static boolean hasPending() {
        return pendingState.get() != null;
    }
}

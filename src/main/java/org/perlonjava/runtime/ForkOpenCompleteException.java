package org.perlonjava.runtime;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Exception thrown when a fork-open emulation completes successfully.
 * 
 * <p>This exception is used for control flow in fork-open emulation. When
 * {@code exec @cmd} is called with a pending fork-open, we:
 * <ol>
 *   <li>Create the pipe using 3-arg semantics</li>
 *   <li>Throw this exception to unwind the "child" code path</li>
 *   <li>The exception carries the PID and signals successful completion</li>
 * </ol>
 * 
 * <p>This exception should be caught at the subroutine/eval boundary and
 * converted to a normal return with the output from the pipe.
 * 
 * @see ForkOpenState
 */
public class ForkOpenCompleteException extends RuntimeException {
    
    /**
     * The process ID of the spawned process.
     */
    public final long pid;
    
    /**
     * The output captured from the command (for _backticks style usage).
     */
    public final String capturedOutput;
    
    /**
     * The filehandle that was set up with the pipe.
     */
    public final RuntimeScalar fileHandle;
    
    /**
     * Creates a new ForkOpenCompleteException.
     * 
     * @param pid The process ID
     * @param capturedOutput The output from the command (may be null if not captured)
     * @param fileHandle The configured filehandle
     */
    public ForkOpenCompleteException(long pid, String capturedOutput, RuntimeScalar fileHandle) {
        super("Fork-open completed successfully");
        this.pid = pid;
        this.capturedOutput = capturedOutput;
        this.fileHandle = fileHandle;
    }
}

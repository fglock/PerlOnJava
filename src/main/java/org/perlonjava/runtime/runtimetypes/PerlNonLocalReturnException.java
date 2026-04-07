package org.perlonjava.runtime.runtimetypes;

import java.io.Serial;

/**
 * Exception used to implement non-local return from map/grep blocks.
 * <p>
 * When 'return' is used inside a map or grep block, the return should exit
 * the enclosing subroutine, not just the block. Since map/grep blocks are
 * compiled as separate methods and called through Java method calls
 * (ListOperators.map/grep), return-value-based control flow markers can't
 * propagate through the Java call stack. This exception provides the
 * stack-unwinding mechanism needed.
 * <p>
 * The exception is thrown by ListOperators when it detects a RETURN
 * control flow marker, and caught by RuntimeCode.apply() at the first
 * non-map/grep subroutine boundary.
 */
public class PerlNonLocalReturnException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public final RuntimeBase returnValue;

    public PerlNonLocalReturnException(RuntimeBase returnValue) {
        // Suppress stack trace for performance - this is control flow, not an error
        super(null, null, true, false);
        this.returnValue = returnValue;
    }
}

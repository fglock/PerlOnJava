package org.perlonjava.runtime;

/**
 * Exception for Perl control flow operations that can cross subroutine boundaries.
 * These are not error conditions but control flow mechanisms.
 */
public class PerlControlFlowException extends PerlCompilerException {
    public final String label;   // Target label (null for unlabeled)
    public final String reason;  // "next", "last", "redo", or "goto"

    public PerlControlFlowException(String label, String reason, String errorMessage) {
        super(errorMessage);
        this.label = label;
        this.reason = reason;
    }

    public static void throwControlFlowException(String label, String reason, String errorMessage) {
        throw new PerlControlFlowException(label, reason, errorMessage);
    }
}
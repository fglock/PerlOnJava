package org.perlonjava.runtime.runtimetypes;

/** Security failure raised when tainted source is passed to eval under -T. */
public final class TaintedEvalException extends PerlCompilerException {
    public TaintedEvalException() {
        super("Insecure dependency in eval while running with -T switch");
    }
}

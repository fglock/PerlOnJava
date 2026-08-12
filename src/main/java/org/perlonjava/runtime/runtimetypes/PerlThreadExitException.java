package org.perlonjava.runtime.runtimetypes;

/** Internal non-error unwind used by {@code threads->exit}. */
public final class PerlThreadExitException extends RuntimeException {
    private final RuntimeArray values;

    public PerlThreadExitException(RuntimeArray values) {
        super("Perl thread exited", null, false, false);
        this.values = values;
    }

    public RuntimeArray values() {
        return values;
    }
}

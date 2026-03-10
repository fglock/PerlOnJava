package org.perlonjava.runtime.runtimetypes;

import java.io.Serial;

/**
 * Exception used to implement Perl's exit() semantics for embedded/library use.
 * <p>
 * When Perl code calls exit(), this exception is thrown instead of calling
 * System.exit(), allowing the calling Java application to handle the exit
 * gracefully and continue execution.
 * <p>
 * The CLI (Main.main()) catches this exception and converts it to a real
 * System.exit() call, while library users can catch it and handle the exit
 * code as needed.
 */
public class PerlExitException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int exitCode;

    public PerlExitException(int exitCode) {
        super("exit " + exitCode);
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }
}

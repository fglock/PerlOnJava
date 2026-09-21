package org.perlonjava.runtime.io;

/** Converts Java process exit codes to Perl's wait-status representation. */
public final class ProcessExitStatus {
    private ProcessExitStatus() {}

    public static int toPerlWaitStatus(int exitCode) {
        // Unix shells conventionally report signal termination as 128+signal.
        // Perl exposes the signal number in the low wait-status byte instead.
        if (exitCode >= 128 && exitCode <= 255) {
            return exitCode - 128;
        }
        return exitCode << 8;
    }
}

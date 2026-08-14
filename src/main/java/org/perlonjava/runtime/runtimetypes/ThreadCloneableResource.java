package org.perlonjava.runtime.runtimetypes;

/**
 * Adapter for runtime-owned magical/native resources that need ithread-specific
 * behavior without adding type-specific branches to {@link RuntimeGraphCloner}.
 * DBI and future extension resources can implement this contract directly.
 */
public interface ThreadCloneableResource {
    /** Return the child-runtime representation, or {@code null} to become undef. */
    Object cloneForThread(ThreadCloneContext context);

    interface ThreadCloneContext {
        PerlRuntime sourceRuntime();
        PerlRuntime targetRuntime();
        RuntimeBase clonePerlValue(RuntimeBase value);
    }
}

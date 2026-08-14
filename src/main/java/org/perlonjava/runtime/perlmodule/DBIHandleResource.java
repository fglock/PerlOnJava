package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.ThreadCloneableResource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime ownership envelope for a JDBC object stored behind a Perl DBI
 * handle.  The JDBC object itself never crosses an ithread boundary: graph
 * cloning installs a permanently invalid token carrying only enough identity
 * for Perl-compatible diagnostics.
 */
final class DBIHandleResource<T> implements ThreadCloneableResource {
    private static final AtomicLong NEXT_HANDLE_ID = new AtomicLong(1);

    private final PerlRuntime owner;
    private final long ownerThreadId;
    private final long handleId;
    private final T resource;

    private DBIHandleResource(
            PerlRuntime owner, long ownerThreadId, long handleId, T resource) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ownerThreadId = ownerThreadId;
        this.handleId = handleId;
        this.resource = resource;
    }

    static <T> DBIHandleResource<T> owned(T resource) {
        PerlRuntime runtime = PerlRuntime.current();
        return new DBIHandleResource<>(runtime, runtime.perlThreadId(),
                NEXT_HANDLE_ID.getAndIncrement(), Objects.requireNonNull(resource, "resource"));
    }

    <R> R requireCurrentOwner(String handleClass, String operation, Class<R> expectedType) {
        PerlRuntime current = PerlRuntime.current();
        if (current != owner || resource == null) {
            throw new DBIThreadOwnershipException(handleClass, operation, handleId,
                    ownerThreadId, current.perlThreadId());
        }
        return expectedType.cast(resource);
    }

    boolean isOwnedByCurrentRuntime() {
        return resource != null && PerlRuntime.currentOrNull() == owner;
    }

    @Override
    public Object cloneForThread(ThreadCloneContext context) {
        // Never retain the native/JDBC object in the target graph.  Keeping the
        // original owner metadata makes inherited and join-returned handles fail
        // deterministically, even if a value travels back to its original runtime.
        return new DBIHandleResource<>(owner, ownerThreadId, handleId, null);
    }
}

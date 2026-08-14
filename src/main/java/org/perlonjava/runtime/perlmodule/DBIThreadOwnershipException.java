package org.perlonjava.runtime.perlmodule;

/** Internal signal for the unconditional DBI cross-thread ownership error. */
final class DBIThreadOwnershipException extends RuntimeException {
    DBIThreadOwnershipException(
            String handleClass, String operation, long handleId,
            long ownerThreadId, long currentThreadId) {
        super(handleClass + " " + operation + " failed: handle " + handleId
                + " is owned by thread " + ownerThreadId
                + " not current thread " + currentThreadId
                + " (handles can't be shared between threads and your driver may need a CLONE method added)");
    }
}

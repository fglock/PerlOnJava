package org.perlonjava.runtime.runtimetypes;

/**
 * A transient lvalue returned when a reference is fetched from shared aggregate
 * storage. Perl exposes a fresh proxy referent on each FETCH, while assignment
 * through the scalar must still update the canonical shared slot.
 */
final class SharedElementProxy extends RuntimeScalar {
    private final RuntimeScalar storage;

    SharedElementProxy(RuntimeScalar storage, RuntimeBase localView) {
        super(storage);
        this.storage = storage;
        this.value = localView;
        // A fetched shared reference is a real, short-lived Perl proxy owner.
        // Register that owner so releasing the proxy suppresses DESTROY on its
        // runtime-local view while the canonical shared slot still exists.
        RuntimeScalar.incrementRefCountForContainerStore(this);
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        SharedPerlStorage.publishBlessing(value);
        // The proxy is a transient FETCH view, not a second Perl scalar slot.
        // Updating it through RuntimeScalar.set() would acquire a second
        // refCount owner for the replacement.  That owner survives after the
        // proxy is discarded and delays DESTROY when the shared container is
        // subsequently cleared or shrunk.
        return storage.set(value);
    }

    @Override
    public RuntimeScalar undefine() {
        return storage.undefine();
    }

    @Override
    public RuntimeScalar createReference() {
        return storage.createReference();
    }
}

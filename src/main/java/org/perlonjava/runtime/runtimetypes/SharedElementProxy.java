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
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        SharedPerlStorage.publishBlessing(value);
        RuntimeScalar result = storage.set(value);
        super.set(value);
        return result;
    }

    @Override
    public RuntimeScalar undefine() {
        RuntimeScalar result = storage.undefine();
        super.undefine();
        return result;
    }

    @Override
    public RuntimeScalar createReference() {
        return storage.createReference();
    }
}

package org.perlonjava.runtime.runtimetypes;

/** Helpers for tests that model a concrete counted Perl reference slot. */
final class CountedOwnerFixture {
    private CountedOwnerFixture() { }

    static RuntimeScalar reference(RuntimeBase referent, PerlOwnerSlot.Kind kind) {
        RuntimeScalar owner = new RuntimeScalar();
        owner.type = RuntimeScalarType.REFERENCE;
        owner.value = referent;
        RuntimeScalar.incrementRefCountForContainerStore(owner, kind);
        return owner;
    }

    static RuntimeScalar temporaryReference(RuntimeBase referent) {
        return reference(referent, PerlOwnerSlot.Kind.RETURN_VALUE);
    }

    static void queueRelease(RuntimeScalar owner) {
        MortalList.deferDecrementIfTracked(owner);
    }
}

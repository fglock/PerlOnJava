package org.perlonjava.runtime.runtimetypes;

import java.util.function.Consumer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class DeferredReleaseRejectsStaleAggregateSlotTest {
    @Test
    void detachedArrayElementIdentityDoesNotRestoreTheReferentCount() {
        RuntimeArray container = new RuntimeArray();
        assertDetachedElementDoesNotRestore(
                owner -> container.push(owner),
                owner -> container.elements.remove(0),
                PerlOwnerSlot.Kind.ARRAY_ELEMENT);
    }

    @Test
    void detachedHashElementIdentityDoesNotRestoreTheReferentCount() {
        RuntimeHash container = new RuntimeHash();
        assertDetachedElementDoesNotRestore(
                owner -> container.put("owner", owner),
                owner -> container.elements.remove("owner"),
                PerlOwnerSlot.Kind.HASH_ELEMENT);
    }

    private static void assertDetachedElementDoesNotRestore(
            Consumer<RuntimeScalar> store, Consumer<RuntimeScalar> detach,
            PerlOwnerSlot.Kind ownerKind) {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId(
                    "DeferredReleaseRejectsStaleAggregateSlotTest::Target");
            RuntimeScalar released = new RuntimeScalar();
            released.set(target.createAnonymousReference());
            RuntimeScalar staleOwner = new RuntimeScalar();
            staleOwner.set(target.createAnonymousReference());
            store.accept(staleOwner);
            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(1, target.ownerSlotCount(ownerKind));
                target.refCount = 1; // Model a transient undercount before deferred release.
                detach.accept(staleOwner); // Deliberately leave the old identity active.

                MortalList.deferDecrementIfTracked(released);
                MortalList.flushAboveMark();

                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "a detached aggregate slot is not a current Perl owner");
                assertEquals(Integer.MIN_VALUE, target.refCount,
                        "a stale aggregate slot cannot restore the compatibility count");
            } finally {
                released.set(new RuntimeScalar());
                staleOwner.set(new RuntimeScalar());
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
            }
        }
    }
}

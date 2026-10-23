package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeHashElementOwnershipTest {
    @Test
    void hashMapMutationsTrackEveryDirectReferentSlot() {
        RuntimeArray first = new RuntimeArray();
        RuntimeArray second = new RuntimeArray();
        RuntimeHash hash = new RuntimeHash();

        hash.elements.put("first", first.createReference());
        PerlOwnerSlot firstSlot = hash.hashEntryOwnerSlot("first");
        assertEquals(1, first.hashSlotOwnerCount());
        assertSame(first, firstSlot.referent());

        hash.elements.put("first", second.createReference());
        assertEquals(0, first.hashSlotOwnerCount());
        assertEquals(1, second.hashSlotOwnerCount());
        assertSame(firstSlot, hash.hashEntryOwnerSlot("first"),
                "replacing a value transfers the stable key slot");
        assertSame(second, firstSlot.referent());

        hash.elements.putAll(Map.of("second", first.createReference()));
        PerlOwnerSlot secondSlot = hash.hashEntryOwnerSlot("second");
        assertEquals(1, first.hashSlotOwnerCount());
        assertEquals(1, second.hashSlotOwnerCount());

        hash.elements.putAll(Map.of("second", second.createReference()));
        assertEquals(0, first.hashSlotOwnerCount());
        assertEquals(2, second.hashSlotOwnerCount());
        assertSame(secondSlot, hash.hashEntryOwnerSlot("second"));

        hash.elements.remove("first");
        assertEquals(1, second.hashSlotOwnerCount());
        assertNull(hash.hashEntryOwnerSlot("first"));

        hash.elements.clear();
        assertEquals(0, second.hashSlotOwnerCount());
        assertNull(hash.hashEntryOwnerSlot("second"));
    }

    @Test
    void aliasedHashScalarTransfersEachEntrySlotWhenItsReferentChanges() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            RuntimeHash otherHash = new RuntimeHash();
            var cell = first.createReference();

            hash.elements.put("left", cell);
            hash.elements.put("right", cell);
            otherHash.elements.put("third", cell);
            PerlOwnerSlot leftSlot = hash.hashEntryOwnerSlot("left");
            PerlOwnerSlot rightSlot = hash.hashEntryOwnerSlot("right");
            PerlOwnerSlot thirdSlot = otherHash.hashEntryOwnerSlot("third");

            cell.set(second.createReference());

            assertEquals(0, first.hashSlotOwnerCount());
            assertEquals(3, second.hashSlotOwnerCount());
            assertSame(second, leftSlot.referent());
            assertSame(second, rightSlot.referent());
            assertSame(second, thirdSlot.referent());
        }
    }

    @Test
    void proxyStoreCreatesAndTransfersItsHashEntryOwnerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray first = new RuntimeArray();
            RuntimeArray second = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            RuntimeHashProxyEntry proxy = new RuntimeHashProxyEntry(hash, "value");

            proxy.set(first.createReference());

            PerlOwnerSlot slot = hash.hashEntryOwnerSlot("value");
            assertEquals(1, first.hashSlotOwnerCount(),
                    "a proxy store into a new hash must register its referent owner");
            assertSame(first, slot.referent());

            proxy.set(second.createReference());

            assertSame(slot, hash.hashEntryOwnerSlot("value"),
                    "replacing through the proxy preserves the key's slot identity");
            assertEquals(0, first.hashSlotOwnerCount());
            assertEquals(1, second.hashSlotOwnerCount());
            assertSame(second, slot.referent());

            hash.elements.remove("value");
            assertEquals(0, second.hashSlotOwnerCount(),
                    "deleting the proxy-written entry releases its owner slot");
            assertNull(hash.hashEntryOwnerSlot("value"));
        }
    }

    @Test
    void integerAssignmentReleasesHashEntryReferentOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeHash hash = new RuntimeHash();
            RuntimeScalar cell = referent.createReference();
            hash.elements.put("value", cell);
            PerlOwnerSlot slot = hash.hashEntryOwnerSlot("value");
            assertEquals(1, referent.hashSlotOwnerCount());

            cell.set(new RuntimeScalar(7));

            assertSame(slot, hash.hashEntryOwnerSlot("value"),
                    "the hash key retains its stable slot identity after scalar replacement");
            assertEquals(0, referent.hashSlotOwnerCount(),
                    "replacing the reference with an integer releases the hash owner slot");
            assertNull(slot.referent(), "the active key slot no longer names the old referent");
        }
    }

    @Test
    void hashScopeExitReleasesEntrySlotsWhenNoContainerOwnerRemains() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            MortalList.setActive(true);
            try {
                RuntimeArray referent = new RuntimeArray();
                RuntimeHash hash = new RuntimeHash();
                hash.elements.put("value", referent.createReference());
                assertEquals(1, referent.hashSlotOwnerCount());

                MortalList.scopeExitCleanupHash(hash);

                assertEquals(0, referent.hashSlotOwnerCount(),
                        "a dead lexical hash must release its semantic entry slots");
            } finally {
                MortalList.setActive(previousActive);
            }
        }
    }
}

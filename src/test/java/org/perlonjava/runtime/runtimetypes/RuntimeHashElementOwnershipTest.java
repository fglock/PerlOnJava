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

    @Test
    void hashEntrySlotPreservesReferentAcrossDeferredLegacyDecrement() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            MortalList.setActive(true);
            try {
                RuntimeArray referent = new RuntimeArray();
                referent.refCount = 1;
                RuntimeHash hash = new RuntimeHash();
                hash.elements.put("value", referent.createReference());

                MortalList.deferDecrement(referent);
                MortalList.flush();

                assertEquals(1, referent.refCount,
                        "a live hash-entry slot bridges a deferred selective-count decrement");
                assertEquals(1, referent.hashSlotOwnerCount());

                hash.elements.remove("value");
                assertEquals(0, referent.hashSlotOwnerCount());
                MortalList.deferDecrement(referent);
                MortalList.flush();
                assertEquals(0, referent.refCount,
                        "after its final slot is removed, the deferred decrement is applied");
            } finally {
                MortalList.setActive(previousActive);
            }
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

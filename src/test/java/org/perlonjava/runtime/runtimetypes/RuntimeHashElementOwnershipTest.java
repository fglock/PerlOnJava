package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RuntimeHashElementOwnershipTest {
    @Test
    void hashMapMutationsTrackEveryDirectReferentSlot() {
        RuntimeArray first = new RuntimeArray();
        RuntimeArray second = new RuntimeArray();
        RuntimeHash hash = new RuntimeHash();

        hash.elements.put("first", first.createReference());
        assertEquals(1, first.hashSlotOwnerCount);

        hash.elements.put("first", second.createReference());
        assertEquals(0, first.hashSlotOwnerCount);
        assertEquals(1, second.hashSlotOwnerCount);

        hash.elements.putAll(Map.of("second", first.createReference()));
        assertEquals(1, first.hashSlotOwnerCount);
        assertEquals(1, second.hashSlotOwnerCount);

        hash.elements.putAll(Map.of("second", second.createReference()));
        assertEquals(0, first.hashSlotOwnerCount);
        assertEquals(2, second.hashSlotOwnerCount);

        hash.elements.remove("first");
        assertEquals(1, second.hashSlotOwnerCount);

        hash.elements.clear();
        assertEquals(0, second.hashSlotOwnerCount);
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class HostSideHashOwnerReleaseTest {
    @Test
    void replacementRetiresTheDisplacedSlotAndItsCountTogether() {
        assertNull(PerlRuntime.currentOrNull());

        RuntimeHash hash = new RuntimeHash();
        RuntimeHash previous = new RuntimeHash();
        RuntimeHash replacement = new RuntimeHash();
        hash.put("value", previous.createAnonymousReference());
        RuntimeScalar previousCell = hash.elements.get("value");
        PerlOwnerSlot previousSlot = previousCell.countedOwnerSlot();

        assertEquals(1, previous.refCount);
        assertEquals(1, previous.countedOwnerSlotCount());

        hash.put("value", replacement.createAnonymousReference());
        RuntimeScalar replacementCell = hash.elements.get("value");

        assertEquals(0, previous.refCount);
        assertEquals(0, previous.countedOwnerSlotCount());
        assertEquals(1, replacement.refCount);
        assertEquals(1, replacement.countedOwnerSlotCount());
        assertSame(replacement, replacementCell.countedOwnerSlot().referent());
        assertEquals(PerlOwnerSlot.Kind.HASH_ELEMENT,
                replacementCell.countedOwnerSlot().kind());
        assertNull(previousSlot.referent());
    }
}

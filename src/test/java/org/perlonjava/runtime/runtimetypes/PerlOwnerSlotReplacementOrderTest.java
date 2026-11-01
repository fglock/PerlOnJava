package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class PerlOwnerSlotReplacementOrderTest {
    @Test
    void countedSlotAcquiresReplacementBeforeReleasingPreviousReferent() {
        List<String> events = new ArrayList<>();
        RecordingArray previous = new RecordingArray("previous", events);
        RecordingArray replacement = new RecordingArray("replacement", events);
        PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CELL);
        slot.acquireCountedIdentity(previous);
        events.clear();

        slot.acquireCountedIdentity(replacement);

        assertEquals(List.of("replacement:add", "previous:remove"), events);
        assertSame(replacement, slot.referent());
        assertEquals(0, previous.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
        assertEquals(1, replacement.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));
    }

    private static final class RecordingArray extends RuntimeArray {
        private final String name;
        private final List<String> events;

        private RecordingArray(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        synchronized void addOwnerSlot(PerlOwnerSlot slot) {
            events.add(name + ":add");
            super.addOwnerSlot(slot);
        }

        @Override
        synchronized void removeOwnerSlot(PerlOwnerSlot slot) {
            events.add(name + ":remove");
            super.removeOwnerSlot(slot);
        }
    }
}

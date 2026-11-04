package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class UntrackedReferenceOwnerReplacementOrderTest {
    @Test
    void replacementOwnerIsInstalledBeforePreviousOwnerIsReleased() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            List<String> events = new ArrayList<>();
            RecordingReferent previous = new RecordingReferent("previous", events);
            RecordingReferent replacement = new RecordingReferent("replacement", events);
            RuntimeArray storage = new RuntimeArray();
            storage.addClonedElement(previous.createReference());
            RuntimeScalar element = storage.elements.get(0);
            PerlOwnerSlot owner = element.untrackedReferenceOwnerSlot();
            events.clear();

            element.set(replacement.createReference());

            assertEquals(List.of("replacement:add", "previous:remove"), events);
            assertSame(owner, element.untrackedReferenceOwnerSlot());
            assertSame(replacement, owner.referent());
            MortalList.scopeExitCleanupArray(storage);
        }
    }

    private static final class RecordingReferent extends RuntimeScalar {
        private final String name;
        private final List<String> events;

        private RecordingReferent(String name, List<String> events) {
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

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class CapturedCellOwnerReplacementOrderTest {
    @Test
    void capturedValueOwnerTransfersBeforeRetiringThePreviousReferent() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            List<String> events = new ArrayList<>();
            RecordingReferent previous = new RecordingReferent("previous", events);
            previous.refCount = 0;
            previous.localBindingExists = true;
            RecordingReferent replacement = new RecordingReferent("replacement", events);
            replacement.refCount = 0;
            replacement.blessId = NameNormalizer.getBlessId(
                    "Issue1649::CapturedCellReplacement");

            RuntimeScalar cell = new RuntimeScalar();
            cell.type = RuntimeScalarType.REFERENCE;
            cell.value = previous;
            PerlOwnerSlot capture = cell.retainClosureCaptureSlot();
            events.clear();

            cell.set(replacement.createReference());

            assertEquals(List.of("replacement:CLOSURE_PAD:add",
                            "previous:CLOSURE_PAD:remove"),
                    events.stream().filter(event -> event.contains("CLOSURE_PAD")).toList());
            assertSame(replacement, cell.value);
            cell.releaseClosureCaptureSlot(capture);
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
            if (slot.kind() == PerlOwnerSlot.Kind.CLOSURE_PAD) {
                events.add(name + ":CLOSURE_PAD:add");
            }
            super.addOwnerSlot(slot);
        }

        @Override
        synchronized void removeOwnerSlot(PerlOwnerSlot slot) {
            if (slot.kind() == PerlOwnerSlot.Kind.CLOSURE_PAD) {
                events.add(name + ":CLOSURE_PAD:remove");
            }
            super.removeOwnerSlot(slot);
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            int mark = MyVarCleanupStack.pushMark();
            RuntimeScalar element = null;
            try {
                storage.addClonedElement(referenceTo(previous));
                element = storage.elements.get(0);
                MyVarCleanupStack.register(element);
                PerlOwnerSlot owner = element.untrackedReferenceOwnerSlot();
                assertNotNull(owner,
                        "registering the scalar makes its uncounted value edge durable");
                events.clear();

                element.set(referenceTo(replacement));

                assertEquals(List.of("replacement:add", "previous:remove"), events);
                assertSame(owner, element.untrackedReferenceOwnerSlot());
                assertSame(replacement, owner.referent());
            } finally {
                if (element != null) {
                    RuntimeScalar.scopeExitCleanup(element);
                    MyVarCleanupStack.unregister(element);
                }
                MortalList.scopeExitCleanupArray(storage);
                MyVarCleanupStack.popMark(mark);
            }
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

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar reference = new RuntimeScalar();
        reference.type = RuntimeScalarType.REFERENCE;
        reference.value = referent;
        return reference;
    }
}

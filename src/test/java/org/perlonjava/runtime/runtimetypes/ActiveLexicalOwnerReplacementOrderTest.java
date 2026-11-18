package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ActiveLexicalOwnerReplacementOrderTest {
    @Test
    void activeFrameRetargetsItsLexicalOwnerSlotBeforeReleasingThePreviousCell() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            MortalList.setActive(true);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            int mark = MyVarCleanupStack.pushMark();
            List<String> events = new ArrayList<>();
            RecordingArray previous = new RecordingArray("previous", events);
            RecordingArray replacement = new RecordingArray("replacement", events);
            try {
                MyVarCleanupStack.register(previous);
                MyVarCleanupStack.register(replacement);
                RuntimeCode.registerCurrentActiveLexical("@pad", previous);
                PerlOwnerSlot slot = activeLexicalSlot(previous);
                events.clear();

                RuntimeCode.registerCurrentActiveLexical("@pad", replacement);

                assertEquals(List.of("replacement:add", "previous:remove"), events);
                assertSame(replacement, slot.referent());
                assertSame(slot, activeLexicalSlot(replacement));
                assertEquals(0, previous.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                assertEquals(1, replacement.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
            } finally {
                MyVarCleanupStack.unregister(previous);
                MyVarCleanupStack.unregister(replacement);
                MyVarCleanupStack.popMark(mark);
                RuntimeCode.popActiveCode(code);
            }
        }
    }

    @Test
    void topLevelPadRetargetsItsLexicalOwnerSlotBeforeReleasingThePreviousCell() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            MortalList.setActive(true);
            int mark = MyVarCleanupStack.pushMark();
            List<String> events = new ArrayList<>();
            RecordingArray previous = new RecordingArray("previous", events);
            RecordingArray replacement = new RecordingArray("replacement", events);
            try {
                MyVarCleanupStack.register(previous);
                MyVarCleanupStack.register(replacement);
                RuntimeCode.registerCurrentActiveLexical("@pad", previous);
                PerlOwnerSlot slot = activeLexicalSlot(previous);
                events.clear();

                RuntimeCode.registerCurrentActiveLexical("@pad", replacement);

                assertEquals(List.of("replacement:add", "previous:remove"), events);
                assertSame(replacement, slot.referent());
                assertSame(slot, activeLexicalSlot(replacement));
                assertEquals(0, previous.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                assertEquals(1, replacement.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
            } finally {
                MyVarCleanupStack.unregister(previous);
                MyVarCleanupStack.unregister(replacement);
                MyVarCleanupStack.popMark(mark);
            }
        }
    }

    private static PerlOwnerSlot activeLexicalSlot(RuntimeBase cell) {
        return cell.ownerSlotSnapshot().stream()
                .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ACTIVE_LEXICAL)
                .findFirst()
                .orElseThrow();
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

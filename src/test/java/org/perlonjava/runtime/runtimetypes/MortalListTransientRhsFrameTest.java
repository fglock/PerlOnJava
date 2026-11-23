package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalListTransientRhsFrameTest {
    @Test
    void returnedRhsIsHeldForCallerAndDrainedInLifoOrder() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            List<String> released = new ArrayList<>();
            RuntimeCode caller = code();
            RuntimeCode callee = code();
            RuntimeCode.pushActiveCode(caller);
            RuntimeCode.pushActiveCode(callee);

            RuntimeArray first = transientRhs("first", released);
            RuntimeArray second = transientRhs("second", released);
            MortalList.deferTransientListAssignmentRhs(first);
            MortalList.deferTransientListAssignmentRhs(second);

            MortalList.flushStatementBoundary();
            assertTrue(first.transientListAssignmentRhs,
                    "a callee boundary must not retire the caller's returned-list copies");
            assertTrue(second.transientListAssignmentRhs,
                    "the second returned-list copy must also reach the caller");
            assertTrue(released.isEmpty(), "no cells are released while the callee is active");

            RuntimeCode.popActiveCode(callee);
            MortalList.flushStatementBoundary();

            assertEquals(List.of("second", "first"), released,
                    "the caller drains returned-list copies in LIFO order");
            assertTrue(first.elements.isEmpty());
            assertTrue(second.elements.isEmpty());
            RuntimeCode.popActiveCode(caller);
        }
    }

    @Test
    void pendingCallerBucketHandsOffWhenThatFrameReturnsBeforeItsBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            List<String> released = new ArrayList<>();
            RuntimeCode outer = code();
            RuntimeCode middle = code();
            RuntimeCode inner = code();
            RuntimeCode.pushActiveCode(outer);
            RuntimeCode.pushActiveCode(middle);
            RuntimeCode.pushActiveCode(inner);

            RuntimeArray rhs = transientRhs("nested", released);
            MortalList.deferTransientListAssignmentRhs(rhs);
            RuntimeCode.popActiveCode(inner);
            RuntimeCode.popActiveCode(middle);

            assertTrue(rhs.transientListAssignmentRhs,
                    "the middle frame's returned value remains held during handoff");
            MortalList.flushStatementBoundary();

            assertEquals(List.of("nested"), released,
                    "the outer frame releases a handoff after its next statement");
            RuntimeCode.popActiveCode(outer);
        }
    }

    @Test
    void appendBufferPreservesAscendingBoundaryGroupsAndLifoWithinEachGroup() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            List<String> released = new ArrayList<>();
            RuntimeCode caller = code();
            RuntimeCode callee = code();
            RuntimeCode.pushActiveCode(caller);
            RuntimeCode.pushActiveCode(callee);

            RuntimeArray first = transientRhs("first", released);
            RuntimeArray second = transientRhs("second", released);
            RuntimeArray third = transientRhs("third", released);
            RuntimeArray fourth = transientRhs("fourth", released);
            MortalList.deferTransientListAssignmentRhs(first);
            MortalList.deferTransientListAssignmentRhs(second);

            MortalList.flushStatementBoundary();
            MortalList.deferTransientListAssignmentRhs(third);
            MortalList.deferTransientListAssignmentRhs(fourth);
            RuntimeCode.popActiveCode(callee);
            MortalList.flushStatementBoundary();

            assertEquals(List.of("fourth", "third", "second", "first"), released,
                    "newer boundaries drain first, with LIFO order inside each boundary");
            RuntimeCode.popActiveCode(caller);
        }
    }

    @Test
    void outOfOrderBoundaryFallsBackToOrderedQueue() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            List<String> released = new ArrayList<>();
            LifecycleRuntimeState.TransientListAssignmentQueue queue =
                    new LifecycleRuntimeState.TransientListAssignmentQueue();
            queue.append(transientRhs("later", released), 1, 2);
            queue.append(transientRhs("earlier", released), 2, 1);

            queue.drainAtBoundary(2);

            assertEquals(List.of("later", "earlier"), released,
                    "an out-of-order boundary must preserve the ordered-map behavior");
            assertTrue(queue.isEmpty());
        }
    }

    private static RuntimeCode code() {
        return new RuntimeCode((args, context) -> new RuntimeScalar().getList(), null);
    }

    private static RuntimeArray transientRhs(String label, List<String> released) {
        RuntimeArray array = new RuntimeArray();
        array.transientListAssignmentRhs = true;
        RecordingScalar cell = new RecordingScalar(label, released);
        cell.markContainerOwner(array);
        array.elements.add(cell);
        return array;
    }

    private static final class RecordingScalar extends RuntimeScalar {
        private final String label;
        private final List<String> released;

        RecordingScalar(String label, List<String> released) {
            this.label = label;
            this.released = released;
        }

        @Override
        void releaseContainerOwner(RuntimeBase expectedOwner) {
            released.add(label);
            super.releaseContainerOwner(expectedOwner);
        }
    }
}

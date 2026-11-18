package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class DynamicVariableManagerSuspendedOwnerFailureTest {
    @Test
    void resumeFailureStopsLaterRestoresAndReleasesAllSuspendedOwners() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash firstReferent = new RuntimeHash();
            RuntimeHash failingReferent = new RuntimeHash();
            RuntimeHash lastReferent = new RuntimeHash();
            TestState first = new TestState(firstReferent, false, false);
            TestState failing = new TestState(failingReferent, true, false);
            TestState last = new TestState(lastReferent, false, false);

            DynamicVariableManager.pushLocalVariable(first);
            DynamicVariableManager.pushLocalVariable(failing);
            DynamicVariableManager.pushLocalVariable(last);
            List<DynamicVariableManager.SuspendedState> suspended =
                    DynamicVariableManager.suspendAbove(0);

            assertEquals(1, firstReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(1, failingReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(1, lastReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> DynamicVariableManager.resumeSuspended(suspended));

            assertEquals("resume failed", failure.getMessage());
            assertEquals(0, DynamicVariableManager.getLocalLevel());
            assertEquals(0, firstReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(0, failingReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(0, lastReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(1, first.resumeCount);
            assertEquals(1, failing.resumeCount);
            assertEquals(0, last.resumeCount);
            assertEquals(1, first.restoreAfterResumeCount);
            assertEquals(1, failing.restoreAfterResumeCount);
            assertEquals(0, last.restoreAfterResumeCount);
        }
    }

    @Test
    void suspendFailureReleasesEarlierOwnersAndUnwindsRemainingStates() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash firstReferent = new RuntimeHash();
            RuntimeHash failingReferent = new RuntimeHash();
            RuntimeHash lastReferent = new RuntimeHash();
            TestState first = new TestState(firstReferent, false, false);
            TestState failing = new TestState(failingReferent, false, true);
            TestState last = new TestState(lastReferent, false, false);

            DynamicVariableManager.pushLocalVariable(first);
            DynamicVariableManager.pushLocalVariable(failing);
            DynamicVariableManager.pushLocalVariable(last);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> DynamicVariableManager.suspendAbove(0));

            assertEquals("suspend failed", failure.getMessage());
            assertEquals(0, DynamicVariableManager.getLocalLevel());
            assertEquals(0, firstReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(0, failingReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(0, lastReferent.ownerSlotCount(PerlOwnerSlot.Kind.SUSPENDED_FRAME));
            assertEquals(0, first.suspendCount);
            assertEquals(1, failing.suspendCount);
            assertEquals(1, last.suspendCount);
            assertEquals(1, first.restoreCount);
            assertEquals(1, failing.restoreCount);
            assertEquals(1, last.restoreCount);
        }
    }

    private static final class TestState implements DynamicState {
        private final RuntimeHash referent;
        private final boolean failOnResume;
        private final boolean failOnSuspend;
        private int resumeCount;
        private int suspendCount;
        private int restoreCount;
        private int restoreAfterResumeCount;
        private boolean resumeAttempted;

        TestState(RuntimeHash referent, boolean failOnResume, boolean failOnSuspend) {
            this.referent = referent;
            this.failOnResume = failOnResume;
            this.failOnSuspend = failOnSuspend;
        }

        @Override
        public void dynamicSaveState() {}

        @Override
        public void dynamicRestoreState() {
            restoreCount++;
            if (resumeAttempted) restoreAfterResumeCount++;
        }

        @Override
        public RuntimeBase suspendedOwnerValue() {
            return referent;
        }

        @Override
        public Object dynamicSuspendState() {
            suspendCount++;
            if (failOnSuspend) throw new IllegalStateException("suspend failed");
            return DynamicState.super.dynamicSuspendState();
        }

        @Override
        public void dynamicResumeState(Object token) {
            resumeCount++;
            resumeAttempted = true;
            dynamicSaveState();
            if (failOnResume) throw new IllegalStateException("resume failed");
        }
    }
}

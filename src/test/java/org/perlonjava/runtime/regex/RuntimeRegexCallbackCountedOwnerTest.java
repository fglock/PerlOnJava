package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeRegexCallbackCountedOwnerTest {
    @Test
    void countedCallbackOwnerRecordsAndReleasesItsPairedCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode(null, List.of());
            code.refCount = 0;
            RuntimeRegexCallback callback = callback(code);

            callback.retainOwner();

            PerlOwnerSlot slot = callbackSlot(code);
            assertTrue(slot.isCountedNativeOwner());
            assertEquals(1, code.refCount);

            callback.retainOwner();
            assertEquals(1, code.refCount, "multiple regex wrappers share one callback owner");
            callback.releaseOwner();
            assertEquals(1, code.refCount, "the shared callback owner remains active");

            callback.releaseOwner();
            assertEquals(0, code.refCount);
            assertFalse(slot.isActive());
        }
    }

    @Test
    void initiallyUncountedCallbackDoesNotReleaseAnotherOwnersLaterCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode(null, List.of());
            code.refCount = -1;
            RuntimeRegexCallback callback = callback(code);

            callback.retainOwner();

            PerlOwnerSlot slot = callbackSlot(code);
            assertFalse(slot.isCountedNativeOwner());
            code.refCount = 3;

            callback.releaseOwner();

            assertEquals(3, code.refCount,
                    "an uncounted callback must not decrement a later unrelated count");
            assertFalse(slot.isActive());
        }
    }

    private static RuntimeRegexCallback callback(RuntimeCode code) {
        return (RuntimeRegexCallback)
                RuntimeRegexCallback.wrap(new RuntimeScalar(code), "BLOCK").value;
    }

    private static PerlOwnerSlot callbackSlot(RuntimeCode code) {
        PerlOwnerSlot slot = code.ownerSlotSnapshot().stream()
                .filter(candidate -> candidate.kind() == PerlOwnerSlot.Kind.REGEX_CALLBACK)
                .findFirst()
                .orElse(null);
        assertNotNull(slot, "the callback owns a stable CODE slot");
        return slot;
    }
}

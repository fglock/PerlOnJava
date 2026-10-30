package org.perlonjava.runtime.regex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.MortalList;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeRegexCallbackOwnerSlotTest {
    @Test
    void finalCallbackOwnerReleasesCapturesDespiteStaleCodeCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray capturedPad = new RuntimeArray();
            RuntimeCode code = new RuntimeCode(null, List.of());
            code.refCount = 0;
            PerlOwnerSlot padOwner = capturedPad.retainClosureCaptureOwner(code);
            code.capturedAggregates = new RuntimeBase[] {capturedPad};
            code.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {padOwner};
            code.captureFieldsRecorded = true;

            RuntimeRegexCallback callback = (RuntimeRegexCallback)
                    RuntimeRegexCallback.wrap(new RuntimeScalar(code), "BLOCK").value;
            callback.retainOwner();
            assertTrue(code.hasPerlOwnerForCaptureOtherThan(null));

            code.refCount = 8; // compatibility count outlives the callback owner
            MortalList.scopeExitCleanupArray(capturedPad);
            assertTrue(padOwner.isActive(), "the callback owner keeps its captured pad alive");

            callback.releaseOwner();

            assertTrue(code.refCount > 0, "the regression requires a stale positive count");
            assertFalse(code.hasPerlOwnerForCaptureOtherThan(null));
            assertNull(code.capturedAggregates);
            assertNull(code.capturedAggregateOwnerSlots);
            assertFalse(padOwner.isActive());
            assertEquals(0, capturedPad.captureCount);
        }
    }
}

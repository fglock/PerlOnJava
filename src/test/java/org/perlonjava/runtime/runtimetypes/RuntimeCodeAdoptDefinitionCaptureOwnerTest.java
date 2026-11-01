package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeCodeAdoptDefinitionCaptureOwnerTest {
    @Test
    void adoptingDefinitionRetiresReplacedCaptureAndKeepsNewCapture() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode placeholder = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar oldPad = new RuntimeScalar(1);
            PerlOwnerSlot oldCapture = oldPad.retainClosureCaptureSlot(placeholder);
            placeholder.capturedScalars = new RuntimeScalar[] {oldPad};
            placeholder.capturedScalarOwnerSlots = new PerlOwnerSlot[] {oldCapture};

            RuntimeCode definition = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeScalar newPad = new RuntimeScalar(2);
            PerlOwnerSlot newCapture = newPad.retainClosureCaptureSlot(definition);
            definition.capturedScalars = new RuntimeScalar[] {newPad};
            definition.capturedScalarOwnerSlots = new PerlOwnerSlot[] {newCapture};

            assertEquals(1, oldPad.captureCount);
            assertEquals(1, newPad.captureCount);

            placeholder.adoptDefinitionFrom(definition);

            assertFalse(oldCapture.isActive(), "the replaced pad edge is retired");
            assertEquals(0, oldPad.captureCount);
            assertEquals(0, oldPad.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CAPTURE));
            assertSame(newPad, placeholder.capturedScalars[0]);
            assertSame(newCapture, placeholder.capturedScalarOwnerSlots[0]);
            assertSame(placeholder, newCapture.captureOwner());
            assertTrue(newCapture.isActive());
            assertEquals(1, newPad.captureCount);

            placeholder.releaseCaptures();
            assertFalse(newCapture.isActive());
            assertEquals(0, newPad.captureCount);
        }
    }
}

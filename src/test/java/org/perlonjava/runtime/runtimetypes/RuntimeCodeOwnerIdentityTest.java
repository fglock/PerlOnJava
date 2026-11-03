package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class RuntimeCodeOwnerIdentityTest {
    @Test
    void inheritedLocalBindingMarkerIsNotACodeOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.localBindingExists = true;

            assertFalse(code.hasPerlOwnerForCapture(),
                    "CODE capture liveness must come from CODE owners, not aggregate metadata");
            assertFalse(code.hasPerlOwnerForCaptureOtherThan(null),
                    "the inherited marker does not identify a Perl CODE slot");
        }
    }
}

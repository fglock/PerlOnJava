package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RuntimeScalarPrimitivePayloadRegexSnapshotTest {
    @Test
    void regexCallbackRollbackRestoresTheActivePayload() {
        RuntimeScalar scalar = new RuntimeScalar(0);
        scalar.setPrimitiveFlowInteger(91);

        Object snapshot = scalar.snapshotRegexMutationState();
        scalar.setIntegerValue(0);
        scalar.restoreRegexMutationState(snapshot);

        assertEquals(91L, scalar.getLong());
    }
}

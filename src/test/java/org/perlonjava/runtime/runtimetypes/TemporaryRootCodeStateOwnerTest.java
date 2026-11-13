package org.perlonjava.runtime.runtimetypes;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootCodeStateOwnerTest {
    @Test
    void currentScalarAndAggregateStateOwnersProveTemporaryRootWithoutGraphWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode(null, List.of());
            RuntimeScalar stateScalar = new RuntimeScalar();
            RuntimeArray stateArray = new RuntimeArray();
            RuntimeHash stateHash = new RuntimeHash();
            code.bindStateScalar("scalar", stateScalar);
            code.bindStateArray("array", stateArray);
            code.bindStateHash("hash", stateHash);
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(code.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                for (RuntimeBase stateValue : List.of(stateScalar, stateArray, stateHash)) {
                    PerlOwnerSlot stateOwner = stateValue.ownerSlotSnapshot().stream()
                            .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_STATE)
                            .findFirst().orElseThrow();
                    assertTrue(code.hasCurrentStateOwner(stateValue, stateOwner),
                            "the CODE_STATE slot must match the current CV binding");
                }
            } finally {
                MortalList.popTemporaryRoot(root);
                root.elements.clear();
                root.localBindingExists = false;
                MortalList.scopeExitCleanupArray(root);
                code.releaseStateStorage();
            }
        }
    }
}

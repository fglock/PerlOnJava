package org.perlonjava.runtime.runtimetypes;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TemporaryRootCodeStateOwnerTest {
    private static final class CountingHashValues extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return List.of();
        }
    }

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

            CountingHashValues opaqueValues = new CountingHashValues();
            RuntimeHash opaque = new RuntimeHash();
            opaque.elements = opaqueValues;
            RuntimeArray root = new RuntimeArray();
            root.localBindingExists = true;
            root.elements.add(code.createReference());
            root.elements.add(opaque.createReference());

            MortalList.pushTemporaryRoot(root);
            try {
                for (RuntimeBase stateValue : List.of(stateScalar, stateArray, stateHash)) {
                    PerlOwnerSlot stateOwner = stateValue.ownerSlotSnapshot().stream()
                            .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_STATE)
                            .findFirst().orElseThrow();
                    assertTrue(code.hasCurrentStateOwner(stateValue, stateOwner),
                            "the CODE_STATE slot must match the current CV binding");
                    assertFalse(MortalList.temporaryRootDirectlyReferences(stateValue),
                            "the root contains the owning CODE object, not the state value");
                    assertTrue(ReachabilityWalker.isReachableFromTemporaryRoots(stateValue));
                }
                assertTrue(MortalList.temporaryRootDirectlyReferences(code),
                        "the temporary root directly contains the CODE state owner");
                assertEquals(0, opaqueValues.valuesCalls,
                        "validated CODE_STATE paths must avoid the fallback graph walk");
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

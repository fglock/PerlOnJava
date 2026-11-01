package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CodeStateAggregateOwnerSlotTest {
    @Test
    void deferredStateAggregateReleaseUsesCodeOwnerIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode(null, List.of());
            RuntimeScalar codeRoot = new RuntimeScalar(code);
            String codeName = "CodeStateAggregateOwnerSlotTest::holder";
            GlobalVariable.globalCodeRefs.put(codeName, codeRoot);
            RuntimeArray stateArray = StateVariable.retrieveStateArray(codeRoot, "@state", 914_027);
            RuntimeScalar weak = stateArray.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(1, stateArray.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE),
                        "the persistent CODE state entry must own its aggregate directly");

                stateArray.refCount = 1; // Model the last deferred legacy bridge release.
                MortalList.pushMark();
                MortalList.deferDecrement(stateArray);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    assertTrue(WeakRefRegistry.isweak(weak),
                            "the live CODE state binding keeps the weakly observed array alive");
                    assertEquals(1, stateArray.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
                    assertEquals(0, measurement.stats().rootQueries,
                            "deferred release must use the CODE state owner slot");
                }

                code.releaseStateStorage();

                assertEquals(0, stateArray.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
                assertFalse(WeakRefRegistry.isweak(weak),
                        "retiring the CODE state binding releases the final aggregate owner");
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
            } finally {
                code.releaseStateStorage();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
                GlobalVariable.globalCodeRefs.remove(codeName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void stateScalarsAndRefaliasesRetainTheirCodeStateCells() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode(null, List.of());
            RuntimeScalar codeRoot = new RuntimeScalar(code);
            RuntimeScalar stateScalar = StateVariable.retrieveStateScalar(
                    codeRoot, "$scalar", 914_028);
            RuntimeArray stateArray = StateVariable.retrieveStateArray(
                    codeRoot, "@array", 914_029);
            RuntimeHash stateHash = StateVariable.retrieveStateHash(
                    codeRoot, "%hash", 914_030);
            RuntimeArray aliasedArray = new RuntimeArray();
            RuntimeHash aliasedHash = new RuntimeHash();
            StateVariable.aliasStateArray(codeRoot, "@alias", 914_031, aliasedArray);
            StateVariable.aliasStateHash(codeRoot, "%alias", 914_032, aliasedHash);
            RuntimeScalar arrayAliasCell = code.stateAggregateAliasOwners.get("@"
                    + PersistentVariable.beginVariable(914_031, "alias"));
            RuntimeScalar hashAliasCell = code.stateAggregateAliasOwners.get("%"
                    + PersistentVariable.beginVariable(914_032, "alias"));

            assertEquals(1, stateScalar.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(1, stateArray.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(1, stateHash.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(1, arrayAliasCell.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(1, hashAliasCell.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertSame(aliasedArray, arrayAliasCell.countedOwnerSlot().referent());
            assertSame(aliasedHash, hashAliasCell.countedOwnerSlot().referent());

            code.releaseStateStorage();
            MortalList.flush();

            assertEquals(0, stateScalar.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(0, stateArray.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(0, stateHash.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(0, arrayAliasCell.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
            assertEquals(0, hashAliasCell.ownerSlotCount(PerlOwnerSlot.Kind.CODE_STATE));
        }
    }
}

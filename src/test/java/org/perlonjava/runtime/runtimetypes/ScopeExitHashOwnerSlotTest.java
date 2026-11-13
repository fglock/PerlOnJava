package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ScopeExitHashOwnerSlotTest {
    @Test
    void schemaHashElementOwnsEscapedSourceRegistrationsWithoutRootWalk() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);

            RuntimeArray unrelatedPackageRoot = new RuntimeArray();
            for (int i = 0; i < 256; i++) {
                RuntimeArray.push(unrelatedPackageRoot,
                        new RuntimeHash().createAnonymousReference());
            }
            String rootName = "ScopeExitHashOwnerSlotTest::root";
            String schemaName = "ScopeExitHashOwnerSlotTest::schema";
            GlobalVariable.globalArrays.put(rootName, unrelatedPackageRoot);

            RuntimeHash schema = new RuntimeHash();
            GlobalVariable.globalHashes.put(schemaName, schema);
            RuntimeHash sourceRegistrations = new RuntimeHash();
            try {
                MyVarCleanupStack.register(sourceRegistrations);
                RuntimeCode.registerCurrentActiveLexical("%source_registrations", sourceRegistrations);

                RuntimeScalar sourceRegistrationsReference =
                        sourceRegistrations.createAnonymousReference();
                schema.put("source_registrations", sourceRegistrationsReference);
                assertEquals(1, sourceRegistrations.refCount);
                assertEquals(1,
                        sourceRegistrations.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
                assertEquals(1,
                        sourceRegistrations.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.scopeExitCleanupHash(sourceRegistrations);

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries,
                            "the schema's hash-element owner proves the escaped aggregate is live");
                    assertEquals(0, stats.externalRootSnapshotsBuilt,
                            "unrelated package roots do not add scope-cleanup work");
                    assertEquals(1,
                            sourceRegistrations.ownerSlotCount(PerlOwnerSlot.Kind.HASH_ELEMENT));
                    assertEquals(0,
                            sourceRegistrations.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
                    assertTrue(sourceRegistrations.refCount > 0);
                }
            } finally {
                MyVarCleanupStack.unregister(sourceRegistrations);
                schema.undefine();
                MortalList.flush();
                GlobalVariable.globalHashes.remove(schemaName);
                GlobalVariable.globalArrays.remove(rootName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }
}

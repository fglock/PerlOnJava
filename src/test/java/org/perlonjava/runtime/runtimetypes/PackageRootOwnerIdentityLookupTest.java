package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PackageRootOwnerIdentityLookupTest {
    @Test
    void packageAggregateRootLookupUsesTheCurrentRootSlotIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            assertRootSlotControlsLookup(new RuntimeArray());
            assertRootSlotControlsLookup(new RuntimeHash());
        }
    }

    private static void assertRootSlotControlsLookup(RuntimeBase aggregate) {
        assertFalse(GlobalVariable.isPackageRootedAggregate(aggregate));

        PerlOwnerSlot rootSlot = new PerlOwnerSlot(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT);
        rootSlot.acquirePackageAggregateIdentity(aggregate);
        try {
            assertTrue(GlobalVariable.isPackageRootedAggregate(aggregate),
                    "the package slot identity proves the aggregate is rooted");
        } finally {
            rootSlot.release();
        }

        assertFalse(GlobalVariable.isPackageRootedAggregate(aggregate),
                "releasing the final package slot removes the root proof");
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalListScalarReferenceContentsRecoveryTest {
    @Test
    void aggregateRecoveryCountsDistinctRootedScalarContentsOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 1;

            RuntimeHash packageRoot = new RuntimeHash();
            packageRoot.isPackageGlobalRoot = true;
            RuntimeScalar hashCell = referenceTo(referent);
            hashCell.setRefCountOwned(true);
            packageRoot.elements.put("target", hashCell);

            RuntimeScalar contentsCell = referenceTo(referent);
            RuntimeScalar outerReference = new RuntimeScalar();
            outerReference.type = RuntimeScalarType.REFERENCE;
            outerReference.value = contentsCell;
            PerlOwnerSlot contentsOwner = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS,
                    null, null, outerReference);
            contentsOwner.acquireScalarReferenceContents(referent, contentsCell);
            outerReference.scalarReferenceContentsOwner = contentsOwner;
            MyVarCleanupStack.register(outerReference);

            try {
                assertEquals(2, referent.refCount,
                        "the hash value and scalar-reference contents own distinct edges");

                // Model the selective counter having lost both live owners
                // before recovery. The recovery contract must rebuild the
                // count from each independently rooted owner edge.
                referent.refCount = 0;

                assertTrue(MortalList.restoreReachableAggregateOwnerCount(referent));
                assertEquals(2, referent.refCount,
                        "recovery must include the counted scalar-reference contents edge");
            } finally {
                MyVarCleanupStack.unregister(outerReference);
                referent.refCount = 2;
                outerReference.releaseOwnedScalarReferenceContents();
                packageRoot.elements.remove("target");
                hashCell.releaseScalarValueOwnerCount(
                        referent, "MortalListScalarReferenceContentsRecoveryTest cleanup");
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.REFERENCE;
        scalar.value = referent;
        return scalar;
    }
}

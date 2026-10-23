package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class MortalListAggregateOwnerSlotRootChainTest {
    @Test
    void nestedHashOwnerSlotsProveRootWithoutWholeRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        String rootName = "MortalListAggregateOwnerSlotRootChainTest::root";
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            RuntimeHash target = new RuntimeHash();
            RuntimeHash nested = new RuntimeHash();
            RuntimeHash root = new RuntimeHash();
            root.isPackageGlobalRoot = true;
            root.elements.put("nested", nested.createReference());
            RuntimeScalar nestedValue = referenceTo(target);
            nestedValue.refCountOwned = true;
            nested.elements.put("target", nestedValue);
            GlobalVariable.globalHashes.put(rootName, root);

            RuntimeScalar unrelatedLegacyOwner = referenceTo(target);
            unrelatedLegacyOwner.refCountOwned = true;
            target.refCount = 1;

            try {
                MortalList.setActive(true);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.deferDecrementIfTracked(unrelatedLegacyOwner);
                    MortalList.flush();

                    assertEquals(1, target.refCount,
                            "the live nested hash slot restores the displaced legacy count");
                    assertEquals(0, measurement.stats().rootQueries,
                            "typed owner slots prove the path to the package root");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "the nested owner chain avoids building a graph snapshot");
                }
            } finally {
                MortalList.setActive(previousActive);
                root.elements.clear();
                nested.elements.clear();
                GlobalVariable.globalHashes.remove(rootName);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void scalarValueOwnerSlotProvesRootWithoutWholeRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            RuntimeArray target = new RuntimeArray();
            RuntimeHash nested = new RuntimeHash();
            RuntimeArray root = new RuntimeArray();
            RuntimeScalar rootCell = new RuntimeScalar();
            root.elementsOwned = true;
            root.elements.add(nested.createReference());
            root.reconcileArrayElementOwnerSlots();
            RuntimeScalar nestedValue = referenceTo(target);
            nestedValue.refCountOwned = true;
            nested.elements.put("target", nestedValue);
            rootCell.set(root.createReference());
            MyVarCleanupStack.register(rootCell);

            RuntimeScalar unrelatedLegacyOwner = referenceTo(target);
            unrelatedLegacyOwner.refCountOwned = true;
            target.refCount = 1;

            try {
                MortalList.setActive(true);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.deferDecrementIfTracked(unrelatedLegacyOwner);
                    MortalList.flush();

                    assertEquals(1, target.refCount,
                            "the scalar-owned nested array keeps the hash slot live");
                    assertEquals(0, measurement.stats().rootQueries,
                            "the scalar owner slot proves the path to its lexical root");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "the scalar owner chain avoids a graph snapshot");
                }
            } finally {
                MortalList.setActive(previousActive);
                MyVarCleanupStack.unregister(rootCell);
                rootCell.scalarValueOwnerSlot().release();
                root.elements.clear();
                nested.elements.clear();
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    @Test
    void scalarReferenceContentsOwnerSlotProvesRootWithoutWholeRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousActive = MortalList.isActive();
            RuntimeArray target = new RuntimeArray();
            RuntimeHash nested = new RuntimeHash();
            RuntimeArray root = new RuntimeArray();
            root.elementsOwned = true;
            root.elements.add(nested.createReference());
            root.reconcileArrayElementOwnerSlots();
            RuntimeScalar nestedValue = referenceTo(target);
            nestedValue.refCountOwned = true;
            nested.elements.put("target", nestedValue);

            root.refCount = 1;
            RuntimeScalar contentsCell = referenceTo(root);
            RuntimeScalar outerReference = new RuntimeScalar();
            outerReference.type = RuntimeScalarType.REFERENCE;
            outerReference.value = contentsCell;
            PerlOwnerSlot contentsOwner = new PerlOwnerSlot(
                    PerlOwnerSlot.Kind.SCALAR_REFERENCE_CONTENTS,
                    null, null, outerReference);
            contentsOwner.acquireScalarReferenceContents(root, contentsCell);
            outerReference.scalarReferenceContentsOwner = contentsOwner;
            MyVarCleanupStack.register(outerReference);

            RuntimeScalar unrelatedLegacyOwner = referenceTo(target);
            unrelatedLegacyOwner.refCountOwned = true;
            target.refCount = 1;

            try {
                MortalList.setActive(true);
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.deferDecrementIfTracked(unrelatedLegacyOwner);
                    MortalList.flush();

                    assertEquals(1, target.refCount,
                            "the scalar-reference contents owner keeps the hash slot live");
                    assertEquals(0, measurement.stats().rootQueries,
                            "the contents owner slot proves the path to its lexical root");
                    assertEquals(0, measurement.stats().snapshotsBuilt,
                            "the contents owner chain avoids a graph snapshot");
                }
            } finally {
                MortalList.setActive(previousActive);
                MyVarCleanupStack.unregister(outerReference);
                outerReference.releaseOwnedScalarReferenceContents();
                root.elements.clear();
                nested.elements.clear();
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }
}

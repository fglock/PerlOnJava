package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class UntrackedReferenceOwnerSlotTest {
    @Test
    void untrackedElementOwnersReleaseAtAggregateScopeExitWithoutWeakRefs() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeScalar arrayTarget = new RuntimeScalar("array target");
            RuntimeScalar arrayElement = referenceTo(arrayTarget);
            RuntimeArray array = new RuntimeArray();
            array.addClonedElement(arrayElement);
            PerlOwnerSlot arrayValueOwner = arrayElement.untrackedReferenceOwnerSlot();
            assertNotNull(arrayValueOwner);

            RuntimeScalar hashTarget = new RuntimeScalar("hash target");
            RuntimeScalar hashElement = referenceTo(hashTarget);
            RuntimeHash hash = new RuntimeHash();
            hash.put("value", hashElement);
            RuntimeScalar storedHashElement = hash.elements.get("value");
            PerlOwnerSlot hashValueOwner = storedHashElement.untrackedReferenceOwnerSlot();
            assertNotNull(hashValueOwner);
            assertSame(hashTarget, hashValueOwner.referent());

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                MortalList.scopeExitCleanupArray(array);
                MortalList.scopeExitCleanupHash(hash);

                assertFalse(arrayValueOwner.isActive());
                assertFalse(hashValueOwner.isActive());
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
                assertEquals(0, measurement.stats().scalarReachabilityQueries);

                MortalList.scopeExitCleanupArray(array);
                MortalList.scopeExitCleanupHash(hash);
                assertEquals(0, arrayTarget.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertEquals(0, hashTarget.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            }
        }
    }

    @Test
    void aggregateCollectionHooksRefreshUntrackedOwnersAfterInsertion() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeScalar arrayTarget = new RuntimeScalar("raw array target");
            RuntimeScalar arrayElement = referenceTo(arrayTarget);
            RuntimeArray array = new RuntimeArray();
            array.elements.add(arrayElement);

            PerlOwnerSlot arrayValueOwner = arrayElement.untrackedReferenceOwnerSlot();
            assertNotNull(arrayValueOwner);
            assertSame(arrayTarget, arrayValueOwner.referent());
            assertEquals(-1, arrayTarget.refCount);

            RuntimeScalar hashTarget = new RuntimeScalar("raw hash target");
            RuntimeScalar hashElement = referenceTo(hashTarget);
            RuntimeHash hash = new RuntimeHash();
            hash.elements.put("value", hashElement);

            PerlOwnerSlot hashValueOwner = hashElement.untrackedReferenceOwnerSlot();
            assertNotNull(hashValueOwner);
            assertSame(hashTarget, hashValueOwner.referent());
            assertEquals(-1, hashTarget.refCount);

            array.elements.remove(arrayElement);
            hash.elements.remove("value");
            MortalList.deferDestroyForContainerClear(
                    java.util.List.of(arrayElement, hashElement));
            assertFalse(arrayValueOwner.isActive());
            assertFalse(hashValueOwner.isActive());
        }
    }

    @Test
    void arrayCopyConstructionRefreshesOwnersAfterInstallingItsElementList() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("copied array target");
            RuntimeScalar element = referenceTo(target);

            RuntimeArray copy = new RuntimeArray(java.util.List.of(element));

            PerlOwnerSlot owner = element.untrackedReferenceOwnerSlot();
            assertNotNull(owner);
            assertSame(target, owner.referent());
            assertTrue(copy.elements.contains(element));
            assertEquals(-1, target.refCount);
        }
    }

    @Test
    void untrackedContainerReferentsGetSlotIdentityWithoutCountPromotion() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeScalar firstTarget = new RuntimeScalar("first");
            RuntimeScalar secondTarget = new RuntimeScalar("second");
            RuntimeArray targetStorage = new RuntimeArray();
            targetStorage.addClonedElement(firstTarget);
            targetStorage.addClonedElement(secondTarget);
            RuntimeArray array = new RuntimeArray();
            array.addClonedElement(firstTarget.createReference());

            RuntimeScalar element = array.elements.get(0);
            PerlOwnerSlot owner = element.untrackedReferenceOwnerSlot();
            assertNotNull(owner);
            assertEquals(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE, owner.kind());
            assertSame(firstTarget, owner.referent());
            assertEquals(-1, firstTarget.refCount,
                    "slot tracking must not promote an arbitrary referent into the legacy count");

            element.set(secondTarget.createReference());
            assertSame(owner, element.untrackedReferenceOwnerSlot());
            assertSame(secondTarget, owner.referent());
            assertFalse(firstTarget.hasOwnerSlots());
            assertEquals(-1, secondTarget.refCount);

            array.elements.remove(element);
            MortalList.deferDestroyForContainerClear(java.util.List.of(element));
            assertFalse(owner.isActive());
            assertFalse(secondTarget.hasOwnerSlots());

            RuntimeScalar weaklyTrackedTarget = new RuntimeScalar("weakly tracked");
            targetStorage.addClonedElement(weaklyTrackedTarget);
            RuntimeScalar weakObserver = weaklyTrackedTarget.createReference();
            WeakRefRegistry.weaken(weakObserver);
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, weaklyTrackedTarget.refCount);

            RuntimeArray weaklyTrackedArray = new RuntimeArray();
            weaklyTrackedArray.addClonedElement(weaklyTrackedTarget.createReference());
            RuntimeScalar weaklyTrackedElement = weaklyTrackedArray.elements.get(0);
            PerlOwnerSlot weaklyTrackedOwner = weaklyTrackedElement.untrackedReferenceOwnerSlot();
            assertNotNull(weaklyTrackedOwner);
            assertSame(weaklyTrackedTarget, weaklyTrackedOwner.referent());
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, weaklyTrackedTarget.refCount,
                    "a later strong store must not promote the legacy weakly-tracked sentinel");

            WeakRefRegistry.unweaken(weakObserver);
            weaklyTrackedArray.elements.remove(weaklyTrackedElement);
            MortalList.deferDestroyForContainerClear(java.util.List.of(weaklyTrackedElement));
            assertFalse(weaklyTrackedOwner.isActive());
        }
    }

    @Test
    void untrackedLexicalValueSlotSurvivesCaptureAndReleasesAtFinalOwnerExit() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("weakly tracked target");
            RuntimeScalar weakObserver = new RuntimeScalar();
            weakObserver.type = RuntimeScalarType.REFERENCE;
            weakObserver.value = target;
            WeakRefRegistry.weaken(weakObserver);
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);

            RuntimeScalar lexical = new RuntimeScalar();
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(lexical);

            RuntimeScalar untrackedTarget = new RuntimeScalar("untracked lexical target");
            RuntimeScalar untrackedReference = new RuntimeScalar();
            untrackedReference.type = RuntimeScalarType.REFERENCE;
            untrackedReference.value = untrackedTarget;
            lexical.set(untrackedReference);
            PerlOwnerSlot valueSlot = lexical.untrackedReferenceOwnerSlot();
            assertNotNull(valueSlot);
            assertSame(untrackedTarget, valueSlot.referent());
            assertEquals(-1, untrackedTarget.refCount);

            RuntimeScalar targetReference = new RuntimeScalar();
            targetReference.type = RuntimeScalarType.REFERENCE;
            targetReference.value = target;
            lexical.set(targetReference);
            assertSame(valueSlot, lexical.untrackedReferenceOwnerSlot());
            assertFalse(untrackedTarget.hasOwnerSlots());
            assertSame(target, valueSlot.referent());
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount,
                    "a lexical owner slot must not promote the weakly-tracked sentinel");

            PerlOwnerSlot capture = lexical.retainClosureCaptureSlot();
            RuntimeScalar.scopeExitCleanup(lexical);
            MyVarCleanupStack.unregister(lexical);
            assertTrue(valueSlot.isActive(), "the closure must keep the lexical value slot alive");
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

            lexical.releaseClosureCaptureSlot(capture);
            assertFalse(valueSlot.isActive(), "the final cell owner must retire the value slot");
            assertFalse(target.hasOwnerSlots());
            assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);
            MyVarCleanupStack.popMark(mark);
        }
    }

    @Test
    void packageScalarAliasesOwnDistinctUntrackedReferenceSlotsUntilEachKeyLeaves() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("package-rooted referent");
            RuntimeScalar packageCell = new RuntimeScalar();
            packageCell.type = RuntimeScalarType.REFERENCE;
            packageCell.value = target;
            String firstName = "RuntimeScalarOwnerSlotTest::rooted_one";
            String secondName = "RuntimeScalarOwnerSlotTest::rooted_two";
            RuntimeScalar observer = new RuntimeScalar();
            observer.type = RuntimeScalarType.REFERENCE;
            observer.value = target;

            try {
                GlobalVariable.globalVariables.put(firstName, packageCell);
                PerlOwnerSlot valueOwner = packageCell.untrackedReferenceOwnerSlot();
                assertNotNull(valueOwner,
                        "a package scalar cell owns its untracked reference value");
                assertEquals(-1, target.refCount,
                        "package ownership must not promote an untracked referent");
                assertEquals(1, packageCell.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));

                GlobalVariable.globalVariables.put(secondName, packageCell);
                assertEquals(2, packageCell.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT),
                        "each package key has an independent scalar-cell owner identity");

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(observer);
                    assertEquals(0, measurement.stats().rootQueries,
                            "explicit package owners avoid a root walk during weaken");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }
                assertEquals(1, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                GlobalVariable.globalVariables.keySet().remove(firstName);
                assertTrue(valueOwner.isActive(),
                        "removing one alias keeps the scalar's value owner alive");
                assertEquals(1, packageCell.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));

                GlobalVariable.globalVariables.remove(secondName);
                assertFalse(valueOwner.isActive(),
                        "the final package key releases the untracked value edge");
                assertEquals(0, packageCell.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));
                assertEquals(0, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            } finally {
                GlobalVariable.globalVariables.remove(firstName);
                GlobalVariable.globalVariables.remove(secondName);
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
            }
        }
    }

    @Test
    void clonedPackageScalarTableRebuildsOwnerIdentities() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("thread snapshot referent");
            RuntimeScalar packageCell = new RuntimeScalar();
            packageCell.type = RuntimeScalarType.REFERENCE;
            packageCell.value = target;
            runtime.globalState.scalarValues().put(
                    "RuntimeScalarOwnerSlotTest::snapshot_root", packageCell);

            runtime.globalState.rebuildPackageScalarOwnerSlots();

            assertEquals(1, packageCell.ownerSlotCount(
                    PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));
            assertEquals(1, target.ownerSlotCount(
                    PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            assertEquals(-1, target.refCount,
                    "reconstructing a package root must not promote its untracked value");
        }
    }

    @Test
    void packageScalarSlotTracksDestroyedLegacyCellWithoutRevivingIt() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar legacyDestroyedCell = new RuntimeScalar("retired");
            legacyDestroyedCell.refCount = Integer.MIN_VALUE;
            String key = "RuntimeScalarOwnerSlotTest::retired_cell";

            try {
                GlobalVariable.globalVariables.put(key, legacyDestroyedCell);

                assertEquals(Integer.MIN_VALUE, legacyDestroyedCell.refCount,
                        "identity tracking must not revive the legacy lifecycle count");
                assertEquals(1, legacyDestroyedCell.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));

                GlobalVariable.globalVariables.remove(key);
                assertEquals(Integer.MIN_VALUE, legacyDestroyedCell.refCount);
                assertEquals(0, legacyDestroyedCell.ownerSlotCount(
                        PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));
            } finally {
                GlobalVariable.globalVariables.remove(key);
            }
        }
    }

    @Test
    void tiedHandlerOwnerTracksAndReleasesAnUntrackedValue() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("tied handler target");
            RuntimeScalar handlerReference = new RuntimeScalar();
            handlerReference.type = RuntimeScalarType.REFERENCE;
            handlerReference.value = target;
            RuntimeScalar handlerOwner = RuntimeScalar.retainTiedHandlerValue(handlerReference);
            PerlOwnerSlot valueOwner = handlerOwner.untrackedReferenceOwnerSlot();
            RuntimeScalar observer = new RuntimeScalar();
            observer.type = RuntimeScalarType.REFERENCE;
            observer.value = target;

            assertNotNull(valueOwner);
            assertSame(target, valueOwner.referent());
            assertEquals(-1, target.refCount,
                    "the tie handler slot must not promote the legacy referent count");

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(observer);
                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);
                    assertEquals(0, measurement.stats().rootQueries,
                            "the tied handler identity avoids a root walk during weaken");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                RuntimeScalar.releaseTiedHandlerValue(handlerOwner);
                assertFalse(valueOwner.isActive());
                assertEquals(0, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                RuntimeScalar.releaseTiedHandlerValue(handlerOwner);
                assertEquals(0, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                        "repeated release must not retire another owner's identity");
            } finally {
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
            }
        }
    }

    @Test
    void stagedArgumentOwnerTracksAndReleasesAnUntrackedValue() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("staged argument target");
            RuntimeScalar reference = new RuntimeScalar();
            reference.type = RuntimeScalarType.REFERENCE;
            reference.value = target;
            RuntimeScalar stagedOwner = RuntimeScalar.retainStagedArgumentValue(reference);
            PerlOwnerSlot valueOwner = stagedOwner.untrackedReferenceOwnerSlot();

            assertNotNull(valueOwner);
            assertSame(target, valueOwner.referent());
            assertEquals(-1, target.refCount,
                    "the staged owner must not promote the legacy referent count");

            RuntimeScalar.releaseStagedArgumentValue(stagedOwner);
            assertFalse(valueOwner.isActive());
            assertEquals(0, target.ownerSlotCount(
                    PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            RuntimeScalar.releaseStagedArgumentValue(stagedOwner);
            assertEquals(0, target.ownerSlotCount(
                    PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                    "repeated release must be idempotent");
        }
    }

    @Test
    void explicitUntrackedValueOwnerAvoidsGlobalCorrectiveSweep() {
        Assumptions.assumeTrue(ReachabilityWalker.correctiveSweepsEnabled(),
                "this scheduling regression requires corrective sweeps to be enabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar("untracked referent");
            RuntimeScalar owner = new RuntimeScalar();
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(owner);
            RuntimeScalar untrackedReference = new RuntimeScalar();
            untrackedReference.type = RuntimeScalarType.REFERENCE;
            untrackedReference.value = target;
            owner.set(untrackedReference);
            PerlOwnerSlot ownerSlot = owner.untrackedReferenceOwnerSlot();
            assertNotNull(ownerSlot);
            assertEquals(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE, ownerSlot.kind());
            assertSame(target, ownerSlot.referent());
            assertEquals(-1, target.refCount,
                    "the durable slot identity must not promote the compatibility count");

            RuntimeScalar observer = new RuntimeScalar();
            observer.type = RuntimeScalarType.REFERENCE;
            observer.value = target;
            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(observer);

                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(observer));
                    assertFalse(runtime.lifecycleState.immediateWeakSweepRequested,
                            "the explicit owner slot avoids a registry-wide correction");
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                boolean flushWasSuppressed = MortalList.suppressFlush(true);
                try {
                    owner.set(new RuntimeScalar());
                } finally {
                    MortalList.suppressFlush(flushWasSuppressed);
                }
                assertFalse(ownerSlot.isActive());
                assertTrue(runtime.lifecycleState.targetedWeakSweepReferents.contains(target),
                        "the final untracked owner schedules targeted cleanup");
            } finally {
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
                MyVarCleanupStack.unregister(owner);
                MyVarCleanupStack.popMark(mark);
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar reference = new RuntimeScalar();
        reference.type = RuntimeScalarType.REFERENCE;
        reference.value = referent;
        return reference;
    }

}

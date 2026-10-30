package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class UntrackedAggregateOwnerSlotTest {
    @Test
    void scalarCellsOwnUntrackedAggregateValuesAndReleaseEachIdentityOnce() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar firstOwner = referenceTo(target);
            RuntimeScalar secondOwner = referenceTo(target);
            RuntimeScalar weakObserver = referenceTo(target);
            int mark = MyVarCleanupStack.pushMark();
            try {
                MyVarCleanupStack.register(firstOwner);
                MyVarCleanupStack.register(secondOwner);

                PerlOwnerSlot firstSlot = firstOwner.untrackedReferenceOwnerSlot();
                PerlOwnerSlot secondSlot = secondOwner.untrackedReferenceOwnerSlot();
                assertNotNull(firstSlot);
                assertNotNull(secondSlot);
                assertNotSame(firstSlot, secondSlot,
                        "each durable scalar cell contributes its own owner identity");
                assertEquals(2,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertEquals(-1, target.refCount,
                        "explicit owner slots must not promote the legacy count");

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);
                    assertEquals(WeakRefRegistry.WEAKLY_TRACKED, target.refCount);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(0, measurement.stats().rootQueries,
                            "surviving owner identities avoid a root walk at first weaken");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                firstOwner.set(new RuntimeScalar());
                assertFalse(firstSlot.isActive());
                assertTrue(secondSlot.isActive());
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "one remaining scalar owner keeps the weak observer alive");
                assertNotEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());

                firstOwner.set(new RuntimeScalar());
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE),
                        "replacing an already-cleared cell must not release another identity");

                secondOwner.set(new RuntimeScalar());
                assertFalse(secondSlot.isActive());
                assertEquals(0,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final owner release clears the weak observer immediately");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState(),
                        "the final owner release cascades aggregate cleanup");
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                RuntimeScalar.scopeExitCleanup(firstOwner);
                RuntimeScalar.scopeExitCleanup(secondOwner);
                MyVarCleanupStack.unregister(firstOwner);
                MyVarCleanupStack.unregister(secondOwner);
                MyVarCleanupStack.popMark(mark);
            }
        }
    }

    @Test
    void activeLexicalAggregateOwnerAvoidsTheFirstWeakRootWalk() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            RuntimeCode.pushActiveCode(code);
            int mark = MyVarCleanupStack.pushMark();
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar weakObserver = referenceTo(target);
            try {
                MyVarCleanupStack.register(target);
                RuntimeCode.registerCurrentActiveLexical("@aggregate", target);
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(0, measurement.stats().rootQueries,
                            "an active lexical owner identity replaces the root proof");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                MortalList.scopeExitCleanupArray(target);
                MyVarCleanupStack.unregister(target);
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(0,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                MyVarCleanupStack.unregister(target);
                MyVarCleanupStack.popMark(mark);
                RuntimeCode.popActiveCode(code);
            }
        }
    }

    @Test
    void packageAggregateOwnerAvoidsTheFirstWeakRootWalkAndFinalizesOnRemoval() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            RuntimeScalar weakObserver = referenceTo(target);
            String globalName = "UntrackedAggregateOwnerSlotTest::package_root";

            try {
                GlobalVariable.globalArrays.put(globalName, target);
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);
                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertEquals(0, measurement.stats().rootQueries,
                            "a package aggregate slot replaces the root proof");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                GlobalVariable.globalArrays.remove(globalName);
                assertEquals(0,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_AGGREGATE_SLOT));
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
            }
        }
    }

    @Test
    void finalUntrackedBlessedAggregateOwnerRunsDestroyBeforeClearingWeakObserver() {
        Assumptions.assumeFalse(ReachabilityWalker.correctiveSweepsEnabled(),
                "this lifecycle regression requires corrective sweeps to be disabled");

        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "UntrackedAggregateOwnerSlotTest::BlessedArray";
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId(className);
            RuntimeScalar owner = referenceTo(target);
            RuntimeScalar weakObserver = referenceTo(target);
            AtomicInteger destroyCalls = new AtomicInteger();
            AtomicBoolean weakDuringDestroy = new AtomicBoolean();
            int mark = MyVarCleanupStack.pushMark();
            RuntimeCode destroy = new RuntimeCode((args, context) -> {
                destroyCalls.incrementAndGet();
                weakDuringDestroy.set(WeakRefRegistry.isweak(weakObserver));
                return new RuntimeList();
            }, null);
            destroy.packageName = "UntrackedAggregateOwnerSlotTest";
            destroy.subName = "DESTROY";
            GlobalVariable.getGlobalCodeRef(className + "::DESTROY")
                    .set(new RuntimeScalar(destroy));

            try {
                MyVarCleanupStack.register(owner);
                assertEquals(1,
                        target.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                WeakRefRegistry.weaken(weakObserver);
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                owner.set(new RuntimeScalar());

                assertEquals(1, destroyCalls.get());
                assertTrue(weakDuringDestroy.get(),
                        "Perl keeps weak observers valid while DESTROY runs");
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "weak observers clear after a non-resurrecting DESTROY");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
            } finally {
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
                RuntimeScalar.scopeExitCleanup(owner);
                MyVarCleanupStack.unregister(owner);
                MyVarCleanupStack.popMark(mark);
                GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
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

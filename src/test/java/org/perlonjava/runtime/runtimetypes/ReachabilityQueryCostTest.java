package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ReachabilityQueryCostTest {
    private static final class CountingStashMap extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return Collections.emptyList();
        }
    }

    @Test
    void repeatedContainerScopeCleanupDoesNotEnumerateNestedStashRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeStash stash = new RuntimeStash("ReachabilityQueryCostTest::");
            CountingStashMap stashView = new CountingStashMap();
            stash.elements = stashView;
            ((RuntimeHash) stash).elements = stashView;

            for (int i = 0; i < 3; i++) {
                RuntimeArray container = new RuntimeArray();
                container.addClonedElement(stash.createReference());
                MortalList.scopeExitCleanupArray(container);
            }

            assertEquals(0, stashView.valuesCalls,
                    "scope cleanup must not enumerate dynamic stash entries on each container walk");
        }
    }

    @Test
    void scopeExitedAggregateCapturesReleaseThroughOwnerSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray[] referents = new RuntimeArray[8];
            RuntimeCode[] closures = new RuntimeCode[referents.length];
            for (int i = 0; i < 8; i++) {
                RuntimeArray referent = new RuntimeArray();
                referent.scopeExited = true;
                RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
                PerlOwnerSlot capture = referent.retainClosureCaptureOwner(closure);
                closure.capturedAggregates = new RuntimeBase[] {referent};
                closure.capturedAggregateOwnerSlots = new PerlOwnerSlot[] {capture};
                referents[i] = referent;
                closures[i] = closure;
            }

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                for (RuntimeCode closure : closures) closure.releaseCaptures();

                ReachabilityQueryStats stats = measurement.stats();
                assertEquals(0, stats.rootQueries,
                        "captured aggregate release follows its exact owner slot");
                assertEquals(0, stats.snapshotsBuilt,
                        "capture release does not build a root snapshot");
                for (RuntimeArray referent : referents) {
                    assertEquals(0, referent.semanticCaptureOwnerCount());
                }
            }
        }
    }

    @Test
    void packageArrayOwnerKeepsWeakObserversThroughOrdinaryFlush() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName =
                    "ReachabilityQueryCostTest::targetedWeakReleaseSnapshot";
            GlobalVariable.globalArrays.put(globalName, global);

            RuntimeHash[] targets = new RuntimeHash[8];
            RuntimeScalar[] weakRefs = new RuntimeScalar[targets.length];
            for (int i = 0; i < targets.length; i++) {
                RuntimeHash target = new RuntimeHash();
                RuntimeScalar strong = target.createReference();
                RuntimeScalar weak = target.createReference();
                WeakRefRegistry.weaken(weak);
                global.addClonedElement(strong);
                targets[i] = target;
                weakRefs[i] = weak;
            }

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.flush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries,
                            "ordinary owner cleanup does not query the Perl root graph");
                    for (int i = 0; i < targets.length; i++) {
                        assertTrue(WeakRefRegistry.isweak(weakRefs[i]));
                        assertTrue(targets[i].refCount > 0,
                                "the package array's counted element retains its target");
                    }
                }
            } finally {
                for (RuntimeScalar weak : weakRefs) {
                    if (weak != null) WeakRefRegistry.unweaken(weak);
                }
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void expressionTemporaryContributesOneCountedStrongOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            target.refCount = 1; // One pre-existing owner survives the temporary scope.
            MortalList.pushTemporaryRoot(target);

            try {
                assertEquals(2, target.refCount,
                        "the temporary expression root is an ordinary strong owner");
                assertEquals(1, target.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_TEMPORARY_ROOT));
                MortalList.flush();
                assertEquals(2, target.refCount,
                        "FREETMPS has not run while the root lease is active");
            } finally {
                MortalList.popTemporaryRoot(target);
            }
            assertEquals(1, target.refCount,
                    "releasing the expression root drops its one strong-owner count");
        }
    }

    @Test
    void scalarOnlyMutationLeavesAggregateOwnerSlotsIntact() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            RuntimeHash first = new RuntimeHash();
            first.refCount = 0;
            RuntimeHash second = new RuntimeHash();
            second.refCount = 0;
            global.addClonedElement(first.createAnonymousReference());
            global.addClonedElement(second.createAnonymousReference());
            global.elements.add(new RuntimeScalar("before"));
            String globalName = "ReachabilityQueryCostTest::scalarOnlyMutation";
            GlobalVariable.globalArrays.put(globalName, global);

            try {
                PerlOwnerSlot firstOwner = global.elements.get(0).countedOwnerSlot();
                PerlOwnerSlot secondOwner = global.elements.get(1).countedOwnerSlot();
                assertTrue(firstOwner.isActive());
                assertTrue(secondOwner.isActive());

                global.elements.set(2, new RuntimeScalar("after"));

                assertTrue(firstOwner.isActive());
                assertTrue(secondOwner.isActive());
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void capturedAggregateOwnerReleaseDoesNotScanUnrelatedRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "ReachabilityQueryCostTest::captureGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }

                RuntimeArray captured = new RuntimeArray();
                PerlOwnerSlot owner = captured.retainClosureCaptureOwner();
                captured.scopeExited = true;

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    captured.releaseClosureCaptureOwner(owner);

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(0, stats.rootQueries,
                            "native captured-pad release must use its owner slot, not a root proof");
                    assertEquals(0, stats.snapshotsBuilt);
                    assertEquals(0, stats.externalRootSnapshotsBuilt);
                    assertEquals(0, stats.edgesInspected,
                            "unrelated package roots must not add cleanup work");
                    assertEquals(0, stats.externalRootSnapshotEdgesInspected);
                    assertEquals(0, captured.semanticCaptureOwnerCount());
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void repeatedAggregateScopeCleanupDoesNotBuildExternalRootSnapshots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            for (int i = 0; i < 256; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            String globalName = "ReachabilityQueryCostTest::global";
            GlobalVariable.globalArrays.put(globalName, global);

            RuntimeHash[] targets = new RuntimeHash[3];
            RuntimeScalar[] weakRefs = new RuntimeScalar[targets.length];
            for (int i = 0; i < targets.length; i++) {
                RuntimeHash target = new RuntimeHash();
                MyVarCleanupStack.register(target);
                RuntimeScalar weak = target.createReference();
                WeakRefRegistry.weaken(weak);
                target.refCount = 0;
                target.clearedOwnedAggregateElement = true;
                targets[i] = target;
                weakRefs[i] = weak;
            }

            try (MortalList.ReachabilityQueryMeasurement measurement =
                         MortalList.measureReachabilityQueries()) {
                for (RuntimeHash target : targets) {
                    MyVarCleanupStack.unregister(target);
                    assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                            target.perlLifecycleState());
                }

                ReachabilityQueryStats stats = measurement.stats();
                assertEquals(0, stats.rootQueries);
                assertEquals(0, stats.externalRootSnapshotsBuilt,
                        "scope cleanup uses owner slots rather than package-root traversal");
                assertEquals(0, stats.externalRootSnapshotEdgesInspected);
                assertEquals(0, stats.externalRootSnapshotNodesVisited);
            } finally {
                for (RuntimeScalar weak : weakRefs) {
                    if (weak != null) WeakRefRegistry.unweaken(weak);
                }
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void repeatedDeferredCleanupUsesCountedOwnerSlotsWithoutRootTraversal() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "ReachabilityQueryCostTest::deferredGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < 3; i++) {
                        RuntimeHash target = new RuntimeHash();
                        target.blessId = NameNormalizer.getBlessId(
                                "Issue1649::DeferredRootTarget");
                        RuntimeScalar packageOwner = CountedOwnerFixture.reference(
                                target, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
                        global.elements.add(packageOwner);
                        RuntimeScalar weak = target.createAnonymousReference();
                        WeakRefRegistry.weaken(weak);
                        RuntimeScalar temporaryOwner =
                                CountedOwnerFixture.temporaryReference(target);

                        CountedOwnerFixture.queueRelease(temporaryOwner);
                        MortalList.flush();

                        assertEquals(1, target.refCount,
                                "the active package element slot retains the weak target");
                        assertTrue(WeakRefRegistry.isweak(weak));
                    }

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(0, stats.rootQueries);
                    assertEquals(0, stats.externalRootSnapshotsBuilt);
                    assertEquals(0, stats.externalRootSnapshotEdgesInspected);
                    assertEquals(0, stats.externalRootSnapshotNodesVisited);
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void packageRootOwnerSlotsNeedNoSnapshotAcrossDrains() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "ReachabilityQueryCostTest::stableDeferredGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            RuntimeHash[] targets = new RuntimeHash[4];
            RuntimeScalar[] weakRefs = new RuntimeScalar[targets.length];

            try {
                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < targets.length; i++) {
                        RuntimeHash target = new RuntimeHash();
                        target.blessId = NameNormalizer.getBlessId(
                                "Issue1642::StableDeferredRootTarget");
                        targets[i] = target;
                        RuntimeScalar packageOwner = CountedOwnerFixture.reference(
                                target, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
                        global.elements.add(packageOwner);
                        weakRefs[i] = target.createAnonymousReference();
                        WeakRefRegistry.weaken(weakRefs[i]);

                        RuntimeScalar temporaryOwner =
                                CountedOwnerFixture.temporaryReference(target);
                        CountedOwnerFixture.queueRelease(temporaryOwner);
                        MortalList.flush();

                        assertEquals(1, target.refCount,
                                "the package aggregate's current slot retains its target");
                        assertTrue(WeakRefRegistry.isweak(weakRefs[i]));
                    }

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(4, stats.deferredBasesProcessed);
                    assertEquals(0, stats.rootQueries);
                    assertEquals(0, stats.externalRootSnapshotsBuilt);
                    assertEquals(0, stats.externalRootSnapshotEdgesInspected);
                }
            } finally {
                for (RuntimeScalar weakRef : weakRefs) {
                    if (weakRef != null) WeakRefRegistry.unweaken(weakRef);
                }
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void unrootedAggregateCleanupDoesNotScanPackageOrLexicalRoots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            String globalName = "ReachabilityQueryCostTest::stableFullSnapshotGlobal";
            GlobalVariable.globalArrays.put(globalName, global);
            try {
                for (int i = 0; i < 256; i++) {
                    global.elements.add(new RuntimeHash().createReference());
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    for (int i = 0; i < 3; i++) {
                        RuntimeArray target = new RuntimeArray();
                        target.localBindingExists = false;
                        target.clearedOwnedAggregateElement = true;
                        RuntimeScalar temporaryOwner =
                                CountedOwnerFixture.temporaryReference(target);

                        CountedOwnerFixture.queueRelease(temporaryOwner);
                        MortalList.flush();

                        assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                                target.perlLifecycleState(),
                                "an unowned aggregate is reclaimed at its final exact release");
                    }

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(0, stats.rootQueries);
                    assertEquals(0, stats.snapshotsBuilt);
                    assertEquals(0, stats.liveRootSnapshotsBuilt);
                    assertEquals(0, stats.externalRootSnapshotsBuilt);
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }

    @Test
    void deferredOwnerChecksUseCurrentCountedSlotsWithoutRootSnapshots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray global = new RuntimeArray();
            RuntimeArray nestedOwners = new RuntimeArray();
            global.elements.add(nestedOwners.createReference());
            for (int i = 0; i < 256; i++) {
                global.elements.add(new RuntimeHash().createReference());
            }
            String globalName = "ReachabilityQueryCostTest::nestedOwnerGlobal";
            GlobalVariable.globalArrays.put(globalName, global);

            try {
                MortalList.pushMark();
                RuntimeHash[] targets = new RuntimeHash[3];
                for (int i = 0; i < targets.length; i++) {
                    RuntimeHash target = new RuntimeHash();
                    RuntimeScalar owner = CountedOwnerFixture.reference(
                            target, PerlOwnerSlot.Kind.ARRAY_ELEMENT);
                    nestedOwners.elements.add(owner);
                    RuntimeScalar weak = target.createAnonymousReference();
                    WeakRefRegistry.weaken(weak);
                    targets[i] = target;
                    RuntimeScalar temporaryOwner =
                            CountedOwnerFixture.temporaryReference(target);
                    CountedOwnerFixture.queueRelease(temporaryOwner);
                }

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    MortalList.popAndFlush();

                    ReachabilityQueryStats stats = measurement.stats();
                    assertEquals(3, stats.deferredBasesProcessed);
                    assertEquals(0, stats.rootQueries);
                    assertEquals(0, stats.scalarReachabilityQueries);
                    assertEquals(0, stats.scalarReachabilityRootWalks);
                    assertEquals(0, stats.snapshotsBuilt,
                            "current owner slots decide cleanup directly");
                    for (RuntimeHash target : targets) {
                        assertEquals(1, target.refCount,
                                "the nested current slot retains its weakly observed target");
                    }
                }
            } finally {
                GlobalVariable.globalArrays.remove(globalName);
            }
        }
    }


}

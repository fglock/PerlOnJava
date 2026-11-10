package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RescuedWeakRefsBeforeEndTest {
    @Test
    void preEndBoundaryClearsObserversAndPreservesRescuedCycle() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash schema = blessedHash("Issue1649::RescuedSchema");
            RuntimeHash storage = blessedHash("Issue1649::RescuedStorage");
            RuntimeHash dbh = blessedHash("Issue1649::RescuedDbh");

            RuntimeScalar storageEdge = storage.createAnonymousReference();
            schema.elements.put("storage", storageEdge);
            RuntimeScalar dbhEdge = dbh.createAnonymousReference();
            storage.elements.put("_dbh", dbhEdge);
            RuntimeScalar schemaBackref = schema.createAnonymousReference();
            dbh.elements.put("schema", schemaBackref);

            RuntimeScalar weakSchema = schema.createAnonymousReference();
            RuntimeScalar weakStorage = storage.createAnonymousReference();
            RuntimeScalar weakDbh = dbh.createAnonymousReference();
            WeakRefRegistry.weaken(weakSchema);
            WeakRefRegistry.weaken(weakStorage);
            WeakRefRegistry.weaken(weakDbh);
            runtime.lifecycleState.rescuedObjects.add(schema);

            assertTrue(WeakRefRegistry.isweak(weakSchema));
            assertTrue(WeakRefRegistry.isweak(weakStorage));
            assertTrue(WeakRefRegistry.isweak(weakDbh));

            MortalList.flushDeferredCapturesBeforeEnd();

            assertFalse(WeakRefRegistry.isweak(weakSchema), "rescued root observer clears");
            assertFalse(WeakRefRegistry.isweak(weakStorage), "nested Storage observer clears");
            assertFalse(WeakRefRegistry.isweak(weakDbh), "nested DBH observer clears");
            assertSame(schema, schemaBackref.value, "strong cycle remains available to END");
            assertSame(storage, storageEdge.value);
            assertSame(dbh, dbhEdge.value);
            assertTrue(DestroyDispatch.isRescued(schema), "END still has the rescued root");
            assertFalse(schema.destroyFired, "boundary sweep does not run DESTROY on a live rescue");
        }
    }

    @Test
    void preEndBoundaryPreservesWeakObserversWithIndependentPackageRoot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = blessedHash("Issue1649::GloballyRescued");
            RuntimeScalar weak = rescued.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeHash globalRoot = new RuntimeHash();
            globalRoot.elements.put("rescued", rescued.createAnonymousReference());
            String globalName = "RescuedWeakRefsBeforeEndTest::globalRoot";
            GlobalVariable.globalHashes.put(globalName, globalRoot);
            runtime.lifecycleState.rescuedObjects.add(rescued);

            try {
                assertTrue(WeakRefRegistry.isweak(weak));
                MortalList.flushDeferredCapturesBeforeEnd();

                assertTrue(WeakRefRegistry.isweak(weak),
                        "a package-rooted rescue keeps its weak observer through END");
                assertSame(rescued, weak.value);
            } finally {
                GlobalVariable.globalHashes.remove(globalName);
            }
        }
    }

    @Test
    void preEndBoundaryPreservesWeakObserverForOwnerSlotOutsideRescuedGraph() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = blessedHash("Issue1649::OwnerSlotRescue");
            RuntimeScalar weak = rescued.createAnonymousReference();
            WeakRefRegistry.weaken(weak);

            RuntimeScalar lexicalOwner = rescued.createAnonymousReference();
            lexicalOwner.localBindingExists = true;
            MyVarCleanupStack.register(lexicalOwner);
            RuntimeScalar.incrementRefCountForContainerStore(
                    lexicalOwner, PerlOwnerSlot.Kind.SCALAR_CELL);
            runtime.lifecycleState.rescuedObjects.add(rescued);

            assertTrue(DestroyDispatch.hasLivePerlOwnerIdentity(rescued));
            assertTrue(WeakRefRegistry.isweak(weak));
            MortalList.flushDeferredCapturesBeforeEnd();

            assertTrue(WeakRefRegistry.isweak(weak),
                    "a current semantic owner slot outside the rescued graph keeps its observer");
            assertSame(rescued, weak.value);
        }
    }

    @Test
    void preEndBoundaryPreservesWeakObserverForIndependentNestedRoot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = blessedHash("Issue1649::NestedRescueRoot");
            RuntimeHash nested = blessedHash("Issue1649::NestedIndependentRoot");
            RuntimeScalar nestedEdge = nested.createAnonymousReference();
            rescued.elements.put("nested", nestedEdge);

            RuntimeScalar weakRescued = rescued.createAnonymousReference();
            RuntimeScalar weakNested = nested.createAnonymousReference();
            WeakRefRegistry.weaken(weakRescued);
            WeakRefRegistry.weaken(weakNested);

            RuntimeHash globalRoot = new RuntimeHash();
            globalRoot.elements.put("nested", nested.createAnonymousReference());
            String globalName = "RescuedWeakRefsBeforeEndTest::nestedGlobalRoot";
            GlobalVariable.globalHashes.put(globalName, globalRoot);
            runtime.lifecycleState.rescuedObjects.add(rescued);

            try {
                assertTrue(WeakRefRegistry.isweak(weakRescued));
                assertTrue(WeakRefRegistry.isweak(weakNested));
                MortalList.flushDeferredCapturesBeforeEnd();

                assertFalse(WeakRefRegistry.isweak(weakRescued),
                        "the rescue-only parent observer clears");
                assertTrue(WeakRefRegistry.isweak(weakNested),
                        "an independently rooted descendant keeps its observer");
                assertSame(nested, nestedEdge.value);
                assertSame(nested, weakNested.value);
            } finally {
                GlobalVariable.globalHashes.remove(globalName);
            }
        }
    }

    @Test
    void preEndBoundaryPreservesObjectsCapturedByQueuedEndBlocks() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = blessedHash("Issue1649::EndCapturedRescue");
            RuntimeScalar weak = rescued.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeCode endBlock = new RuntimeCode((args, context) -> new RuntimeList(), null);
            endBlock.closedOverVariables = new java.util.LinkedHashMap<>();
            endBlock.closedOverVariables.put("rescued", rescued.createAnonymousReference());
            SpecialBlock.saveEndBlock(new RuntimeScalar(endBlock));
            runtime.lifecycleState.rescuedObjects.add(rescued);

            assertTrue(WeakRefRegistry.isweak(weak));
            MortalList.flushDeferredCapturesBeforeEnd();

            assertTrue(WeakRefRegistry.isweak(weak),
                    "a queued END callback keeps its captured object alive");
            assertSame(rescued, weak.value);
        }
    }

    @Test
    void preEndBoundaryPreservesObjectsHeldByActiveCode() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = blessedHash("Issue1649::ActiveCodeRescue");
            RuntimeScalar weak = rescued.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeCode activeCode = new RuntimeCode(
                    (args, context) -> new RuntimeList(), null);
            activeCode.capturedScalars = new RuntimeScalar[]{rescued.createAnonymousReference()};
            runtime.executionState().activeCodeStack.add(activeCode);
            runtime.lifecycleState.rescuedObjects.add(rescued);

            assertTrue(WeakRefRegistry.isweak(weak));
            MortalList.flushDeferredCapturesBeforeEnd();

            assertTrue(WeakRefRegistry.isweak(weak),
                    "an active Perl callback keeps its captured object alive");
            assertSame(rescued, weak.value);
        }
    }

    @Test
    void postEndFlushClearsRescuedObserversWithoutDeferredCaptures() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = blessedHash("Issue1649::RescuedAfterEnd");
            RuntimeScalar weak = rescued.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            runtime.lifecycleState.rescuedObjects.add(rescued);

            MortalList.flushDeferredCaptures();

            assertFalse(WeakRefRegistry.isweak(weak));
            assertTrue(DestroyDispatch.isRescued(rescued));
        }
    }

    private static RuntimeHash blessedHash(String className) {
        RuntimeHash hash = new RuntimeHash();
        hash.blessId = NameNormalizer.getBlessId(className);
        return hash;
    }
}

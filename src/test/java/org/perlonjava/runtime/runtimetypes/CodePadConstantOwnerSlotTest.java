package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CodePadConstantOwnerSlotTest {
    @Test
    void compiledPadTreeTransfersToThePublishedCvOwner() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeScalarReadOnly constant = new RuntimeScalarReadOnly("owned");
        RuntimeCode compiledCode = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeCode publishedCv = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeScalar weakObserver = referenceTo(constant);
        String className = "org/perlonjava/test/PadConstantTransfer";

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            try {
                WeakRefRegistry.weaken(weakObserver);
                compiledCode.installPadConstantTree(
                        className, new RuntimeBase[]{constant}, java.util.Map.of(), java.util.Map.of());
                PerlOwnerSlot compiledOwner = constant.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_PAD_CONSTANT)
                        .findFirst().orElseThrow();

                compiledCode.transferPadConstantTreeTo(publishedCv);

                assertFalse(compiledOwner.isActive(),
                        "the temporary compiler wrapper gives up its owner slot");
                assertEquals(1, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT),
                        "the published CV replaces the temporary owner exactly once");
                assertSame(constant, RuntimeCode.getPadConstant(className, 0),
                        "generated code resolves the constant through the published CV tree");
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "transferring the owner does not clear a live constant");

                publishedCv.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(publishedCv);
                assertEquals(0, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the published CV's final release reaps its constant");
            } finally {
                if (compiledCode.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    compiledCode.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(compiledCode);
                }
                if (publishedCv.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    publishedCv.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(publishedCv);
                }
                WeakRefRegistry.clearWeakRefsTo(constant);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    @Test
    void untrackedCallerOwnerIsFinalizedAfterItsCvReleasesThePadConstant() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeScalarReadOnly constant = new RuntimeScalarReadOnly("owned");
        RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeScalar strongOwner = new RuntimeScalar();
        RuntimeScalar weakObserver = referenceTo(constant);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(strongOwner);
            try {
                code.installPadConstants(new RuntimeBase[]{constant});
                strongOwner.set(referenceTo(constant));
                assertTrue(strongOwner.untrackedReferenceOwnerSlot().isActive());
                WeakRefRegistry.weaken(weakObserver);

                code.clearPadConstantWeakRefs();

                assertEquals(0, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                assertEquals(1, constant.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the caller's strong scalar keeps the constant alive after CV release");

                strongOwner.undefine();

                assertEquals(0, constant.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final untracked caller slot triggers immediate weak cleanup");
            } finally {
                RuntimeScalar.scopeExitCleanup(strongOwner);
                MyVarCleanupStack.unregister(strongOwner);
                MyVarCleanupStack.popMark(mark);
                if (code.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    code.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(code);
                }
                WeakRefRegistry.clearWeakRefsTo(constant);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    @Test
    void finalUntrackedCodeReferenceReleasesItsPadTreeDespiteStaleLegacyCount() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeScalarReadOnly constant = new RuntimeScalarReadOnly("owned");
        RuntimeCode closure = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeScalar codeSlot = new RuntimeScalar();
        RuntimeScalar weakObserver = referenceTo(constant);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(codeSlot);
            try {
                closure.installPadConstants(new RuntimeBase[]{constant});
                codeSlot.set(new RuntimeScalar(closure));
                WeakRefRegistry.weaken(weakObserver);

                assertEquals(1,
                        closure.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
                assertEquals(1,
                        constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                // The scalar slot is the actual CODE owner. Its old
                // compatibility count can remain positive after that owner
                // identity is the only remaining proof of Perl liveness.
                closure.refCount = 7;

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    codeSlot.set(new RuntimeScalar());

                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                assertEquals(0,
                        constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                assertEquals(0, closure.padConstantOwnerReferents().length);
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final uncounted CV slot releases the optree constants");
            } finally {
                RuntimeScalar.scopeExitCleanup(codeSlot);
                MyVarCleanupStack.unregister(codeSlot);
                MyVarCleanupStack.popMark(mark);
                closure.clearPadConstantWeakRefs();
                WeakRefRegistry.clearWeakRefsTo(constant);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
            }
        }
    }

    @Test
    void eachOptreeConstantEdgeLivesUntilItsCvOwnerIsDestroyed() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeScalarReadOnly constant = new RuntimeScalarReadOnly("owned");
        RuntimeCode firstCode = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeCode secondCode = new RuntimeCode((args, context) -> new RuntimeList(), null);
        RuntimeScalar weakObserver = referenceTo(constant);

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            MortalList.setActive(true);
            try {
                firstCode.installPadConstants(new RuntimeBase[]{constant, constant});
                secondCode.installPadConstants(new RuntimeBase[]{constant});

                PerlOwnerSlot[] owners = constant.ownerSlotSnapshot().stream()
                        .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.CODE_PAD_CONSTANT)
                        .toArray(PerlOwnerSlot[]::new);
                assertEquals(3, owners.length,
                        "each constant occurrence in each CV has a distinct strong slot");
                assertEquals(3, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    WeakRefRegistry.weaken(weakObserver);

                    assertTrue(WeakRefRegistry.isweak(weakObserver));
                    assertTrue(RuntimeCode.isInstalledPadConstant(constant));
                    assertEquals(0, measurement.stats().rootQueries,
                            "optree owner identities replace the global CODE scan");
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                // A returned reference contributes a real temporary count,
                // but once it leaves the mortal boundary only the CODE pad
                // slots remain. Preserve those slots without inventing a
                // replacement refCount owner.
                constant.refCount = 1;
                MortalList.deferDecrement(constant);
                MortalList.flush();
                assertEquals(WeakRefRegistry.WEAKLY_TRACKED, constant.refCount,
                        "pad ownership defers cleanup without fabricating a counted owner");
                assertTrue(WeakRefRegistry.isweak(weakObserver));

                firstCode.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(firstCode);
                assertEquals(1, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "one surviving CV still owns the shared optree constant");

                secondCode.refCount = Integer.MIN_VALUE;
                DestroyDispatch.callDestroy(secondCode);
                assertEquals(0, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT));
                assertFalse(WeakRefRegistry.isweak(weakObserver),
                        "the final CV release clears the constant's weak observers");
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
                for (PerlOwnerSlot owner : owners) assertFalse(owner.isActive());

                DestroyDispatch.callDestroy(secondCode);
                assertEquals(0, constant.ownerSlotCount(PerlOwnerSlot.Kind.CODE_PAD_CONSTANT),
                        "repeated destruction cannot release an owner twice");
            } finally {
                if (firstCode.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    firstCode.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(firstCode);
                }
                if (secondCode.perlLifecycleState() != RuntimeBase.PerlLifecycleState.DESTROYED) {
                    secondCode.refCount = Integer.MIN_VALUE;
                    DestroyDispatch.callDestroy(secondCode);
                }
                WeakRefRegistry.clearWeakRefsTo(constant);
                MortalList.invalidateAllRootSnapshots();
                MortalList.setActive(previousMortalListState);
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

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ArgumentAliasOwnerSlotTest {
    @Test
    void removingOneDuplicateArgumentSlotRetiresOnlyItsFrameIdentity() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            cell.isPackageGlobalRoot = true;
            cell.set(target.createAnonymousReference());
            RuntimeArray args = new RuntimeArray();
            args.elements.add(cell);
            args.elements.add(cell);
            boolean argsActive = false;
            try {
                RuntimeCode.pushArgs(args);
                argsActive = true;

                assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
                assertSame(cell, RuntimeArray.shift(args));
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "removing one duplicate @_ slot retires exactly one frame identity");

                assertSame(cell, RuntimeArray.pop(args));
                assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "removing the last duplicate retires its remaining identity");

                RuntimeCode.popArgs();
                argsActive = false;
                assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
            } finally {
                if (argsActive) RuntimeCode.popArgs();
            }
        }
    }

    @Test
    void replacingAndClearingArgumentSlotsRetiresTheirFrameIdentities() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();
            RuntimeScalar firstCell = new RuntimeScalar();
            firstCell.isPackageGlobalRoot = true;
            firstCell.set(first.createAnonymousReference());
            RuntimeScalar secondCell = new RuntimeScalar();
            secondCell.isPackageGlobalRoot = true;
            secondCell.set(second.createAnonymousReference());
            RuntimeArray args = new RuntimeArray();
            args.elements.add(firstCell);
            args.elements.add(secondCell);
            boolean argsActive = false;
            try {
                RuntimeCode.pushArgs(args);
                argsActive = true;

                args.elements.set(0, new RuntimeScalar());
                assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
                assertEquals(1, second.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));

                args.elements.clear();
                assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));

                RuntimeCode.popArgs();
                argsActive = false;
                assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
                assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
            } finally {
                if (argsActive) RuntimeCode.popArgs();
            }
        }
    }

    @Test
    void stagedAndActiveArgumentAliasesTrackCellAssignmentsWithoutChangingRefCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash first = new RuntimeHash();
            RuntimeHash second = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            cell.isPackageGlobalRoot = true;
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);
            RuntimeArray args = null;
            boolean argsActive = false;

            try {
                cell.set(first.createAnonymousReference());
                int initialRuntimeRoots = runtime.positiveOwnerSlotReferentCount(first);
                int firstRefCount = first.refCount;
                staged.add(cell);
                args = staged.toRuntimeArray();
                RuntimeCode.pushArgs(args);
                argsActive = true;

                assertEquals(firstRefCount, first.refCount,
                        "argument alias identities must not add Perl-visible references");
                assertEquals(initialRuntimeRoots, runtime.positiveOwnerSlotReferentCount(first),
                        "an active alias frame must not register another runtime root");
                assertEquals(2, first.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "staging and active @_ each have their own frame identity");

                cell.set(new RuntimeScalar(7));
                assertEquals(0, first.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "assigning a primitive releases the old referent identities");

                RuntimeScalar secondReference = second.createAnonymousReference();
                int secondRefCountBeforeStore = second.refCount;
                cell.set(secondReference);
                assertEquals(2, second.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "a primitive argument cell can acquire a later reference value");
                assertEquals(secondRefCountBeforeStore + 1, second.refCount,
                        "the two alias identities do not add to the normal scalar-store count");

                cell.set(new RuntimeScalar());
                assertEquals(0, second.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
            } finally {
                if (argsActive) RuntimeCode.popArgs();
                staged.finish();
            }
        }
    }

    @Test
    void overwrittenOwnerWaitsForAllArgumentAliasFramesBeforeFinalRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            MortalList.pushMark();
            boolean markActive = true;
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::ArgumentAlias");
            RuntimeScalar released = new RuntimeScalar();
            released.set(target.createAnonymousReference());
            RuntimeScalar argumentCell = new RuntimeScalar();
            argumentCell.isPackageGlobalRoot = true;
            argumentCell.set(target.createAnonymousReference());
            PerlOwnerSlot argumentCellOwner = argumentCell.countedOwnerSlot();
            argumentCellOwner.release();
            argumentCell.refCountOwned = false;
            target.refCount = 1; // The released scalar is the only counted owner.

            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);
            RuntimeArray args = null;
            boolean argsActive = false;
            try {
                staged.add(argumentCell);
                args = staged.toRuntimeArray();
                RuntimeCode.pushArgs(args);
                argsActive = true;

                try (MortalList.ReachabilityQueryMeasurement measurement =
                             MortalList.measureReachabilityQueries()) {
                    released.set(new RuntimeScalar());
                    MortalList.flushAboveMark();

                    assertEquals(1, target.refCount);
                    assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
                    assertTrue(WeakRefRegistry.isweak(weak));
                    assertEquals(0, measurement.stats().rootQueries);
                    assertEquals(0, measurement.stats().snapshotsBuilt);
                }

                RuntimeCode.popArgs();
                argsActive = false;
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "the staged call identity remains until CallArguments.finish()");
                assertTrue(WeakRefRegistry.isweak(weak));

                staged.finish();
                assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());

                MortalList.popAndFlush();
                markActive = false;
            } finally {
                if (argsActive) RuntimeCode.popArgs();
                staged.finish();
                if (markActive) MortalList.popAndFlush();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void explicitUndefKeepsAWeaklyObservedTargetUntilArgumentAliasesExit() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            MortalList.pushMark();
            boolean markActive = true;
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId("Issue1649::ArgumentAliasUndef");
            RuntimeScalar released = new RuntimeScalar();
            released.set(target.createAnonymousReference());
            RuntimeScalar argumentCell = new RuntimeScalar();
            argumentCell.isPackageGlobalRoot = true;
            argumentCell.set(target.createAnonymousReference());
            argumentCell.countedOwnerSlot().release();
            argumentCell.refCountOwned = false;
            target.refCount = 1;

            RuntimeScalar weak = target.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);
            RuntimeArray args = null;
            boolean argsActive = false;
            try {
                staged.add(argumentCell);
                args = staged.toRuntimeArray();
                RuntimeCode.pushArgs(args);
                argsActive = true;

                released.undefine();
                assertEquals(1, target.refCount);
                assertTrue(WeakRefRegistry.isweak(weak));
                assertEquals(2, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));

                RuntimeCode.popArgs();
                argsActive = false;
                assertTrue(WeakRefRegistry.isweak(weak));
                staged.finish();

                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        target.perlLifecycleState());
                MortalList.popAndFlush();
                markActive = false;
            } finally {
                if (argsActive) RuntimeCode.popArgs();
                staged.finish();
                if (markActive) MortalList.popAndFlush();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void transientArgumentValuesDoNotGetPersistentCellIdentities() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar transientValue = target.createAnonymousReference();
            RuntimeCode.CallArguments staged = RuntimeCode.newCallArguments(1);
            RuntimeArray args = null;
            boolean argsActive = false;

            try {
                staged.add(transientValue);
                args = staged.toRuntimeArray();
                RuntimeCode.pushArgs(args);
                argsActive = true;

                assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "temporary expression wrappers are not caller storage cells");
            } finally {
                if (argsActive) RuntimeCode.popArgs();
                staged.finish();
            }
        }
    }

    @Test
    void staleCellReleasesAliasIdentityWhenReferentWasAlreadyDestroyed() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash target = new RuntimeHash();
            RuntimeScalar cell = new RuntimeScalar();
            cell.isPackageGlobalRoot = true;
            cell.set(target.createAnonymousReference());
            RuntimeScalar.ArgumentAliasOwner alias = cell.retainArgumentAliasOwner();
            assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));

            target.refCount = Integer.MIN_VALUE;
            cell.refreshArgumentAliasOwnerSlots();

            assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
            alias.release();
        }
    }
}

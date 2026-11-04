package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PendingMortalArgumentAliasOwnerTest {
    @Test
    void stagedArgumentAliasKeepsQueuedMortalReferentAliveThroughTheCall() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            MortalList.pushMark();
            boolean markActive = true;
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(
                    "Issue1649::PendingMortalArgumentAlias");
            RuntimeScalar weak = referent.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeScalar argument = referent.createAnonymousReference();
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(1);

            try {
                MortalList.deferBlessTemporary(referent, false);
                arguments.add(argument);

                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS),
                        "staging the strong value records its live scalar alias");

                MortalList.flushAboveMark();

                assertTrue(WeakRefRegistry.isweak(weak),
                        "the queued mortal decrement cannot destroy a live call argument");
                assertEquals(RuntimeBase.PerlLifecycleState.LIVE,
                        referent.perlLifecycleState());

                arguments.finish();

                assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_ALIAS));
                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "the referent is released when the staged argument exits");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState());
                MortalList.popAndFlush();
                markActive = false;
            } finally {
                arguments.finish();
                if (markActive) MortalList.popAndFlush();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
            }
        }
    }

    @Test
    void finalListResultArgumentRetainsItsReturnValueThroughTheCall() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            MortalList.setActive(true);
            MortalList.pushMark();
            boolean markActive = true;
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(
                    "Issue1649::FinalListArgumentAlias");
            RuntimeScalar weak = referent.createAnonymousReference();
            WeakRefRegistry.weaken(weak);
            RuntimeScalar argumentCell = referent.createAnonymousReference();
            RuntimeList finalListResult = new RuntimeList(argumentCell);
            RuntimeCode.CallArguments arguments = RuntimeCode.newCallArguments(1);

            try {
                MortalList.deferBlessTemporary(referent, false);
                arguments.add(finalListResult);

                assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE),
                        "a final list result's return value is retained through the call");

                MortalList.flushAboveMark();

                assertTrue(WeakRefRegistry.isweak(weak),
                        "the staged argument value survives the queued temporary release");
                arguments.finish();

                assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "the final list argument releases its referent after the call");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState());
                MortalList.popAndFlush();
                markActive = false;
            } finally {
                arguments.finish();
                if (markActive) MortalList.popAndFlush();
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
            }
        }
    }
}

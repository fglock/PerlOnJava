package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class MethodInvocantOwnerTest {
    private static RuntimeHash invocant() {
        RuntimeHash value = new RuntimeHash();
        value.refCount = 0;
        value.blessId = NameNormalizer.getBlessId("Issue1649::MethodOwner");
        return value;
    }

    @Test
    void eachCallAcquiresItsOwnArgumentIdentityAndRetiresItOnce() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash value = invocant();
            RuntimeScalar source = new RuntimeScalar();
            source.set(value.createAnonymousReference());
            RuntimeBase first = RuntimeCode.acquireMethodInvocantHold(source);
            RuntimeBase second = RuntimeCode.acquireMethodInvocantHold(source);
            assertNotSame(first, second);
            assertEquals(2, value.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(2, value.ownerSlotSnapshot().stream()
                    .filter(slot -> slot.kind() == PerlOwnerSlot.Kind.ARGUMENT_VALUE)
                    .map(PerlOwnerSlot::identity).distinct().count());
            assertEquals(3, value.refCount);
            RuntimeCode.releaseMethodInvocantHold(first);
            RuntimeCode.releaseMethodInvocantHold(first);
            assertEquals(2, value.refCount);
            RuntimeCode.releaseAbandonedMethodInvocantHold(second);
            RuntimeCode.releaseAbandonedMethodInvocantHold(second);
            assertEquals(1, value.refCount);
            assertEquals(0, value.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
            assertEquals(0, runtime.positiveOwnerSlotReferentCount(value));
        }
    }

    @Test
    void finalNormalHoldCarriesItsIdentityUntilTheReceivingBoundaryDrains() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash value = invocant();
            RuntimeScalar source = new RuntimeScalar();
            source.set(value.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(source);
            WeakRefRegistry.weaken(weak);
            RuntimeBase hold = RuntimeCode.acquireMethodInvocantHold(source);
            source.set(RuntimeScalarCache.scalarUndef);
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                RuntimeCode.releaseMethodInvocantHold(hold);
                RuntimeCode.releaseMethodInvocantHold(hold);
                assertEquals(RuntimeScalarType.HASHREFERENCE, weak.type);
                assertEquals(0, value.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                assertEquals(1, runtime.positiveOwnerSlotReferentCount(value));
                MortalList.flush();
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(0, runtime.positiveOwnerSlotReferentCount(value));
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }

    @Test
    void abandonedFinalHoldReleasesImmediatelyWithoutAReachabilityReplacement() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash value = invocant();
            RuntimeScalar source = new RuntimeScalar();
            source.set(value.createAnonymousReference());
            RuntimeScalar weak = new RuntimeScalar();
            weak.set(source);
            WeakRefRegistry.weaken(weak);
            RuntimeBase hold = RuntimeCode.acquireMethodInvocantHold(source);
            source.set(RuntimeScalarCache.scalarUndef);
            try (MortalList.ReachabilityQueryMeasurement measurement = MortalList.measureReachabilityQueries()) {
                RuntimeCode.releaseAbandonedMethodInvocantHold(hold);
                RuntimeCode.releaseAbandonedMethodInvocantHold(hold);
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, value.perlLifecycleState());
                assertFalse(value.hasOwnerSlots());
                assertEquals(0, runtime.positiveOwnerSlotReferentCount(value));
                assertEquals(0, measurement.stats().rootQueries);
                assertEquals(0, measurement.stats().snapshotsBuilt);
            }
        }
    }
}

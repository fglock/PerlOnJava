package org.perlonjava.backend.bytecode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.MortalList;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;
import org.perlonjava.runtime.runtimetypes.WeakRefRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class InterpreterSuspensionOwnerTest {
    @Test
    void awaitedValueUsesSuspendedFrameOwnerUntilCompletionOrCancellation() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash awaitedFuture = new RuntimeHash();
            awaitedFuture.blessId = NameNormalizer.getBlessId(
                    "InterpreterSuspensionOwnerTest::AwaitedFuture");
            RuntimeScalar strongFuture = new RuntimeScalar();
            strongFuture.set(awaitedFuture.createAnonymousReference());
            RuntimeScalar weakObserver = new RuntimeScalar();
            weakObserver.set(awaitedFuture.createAnonymousReference());
            WeakRefRegistry.weaken(weakObserver);

            InterpreterSuspension suspension = new InterpreterSuspension(
                    null, strongFuture, 0, RuntimeContextType.SCALAR);
            try {
                assertEquals(1, awaitedFuture.ownerSlotCount(
                        PerlOwnerSlot.Kind.SUSPENDED_FRAME));
                strongFuture.set(new RuntimeScalar());
                MortalList.flush();
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the suspended frame keeps the awaited value alive after its source slot clears");

                suspension.releaseAwaitedOwner();
                MortalList.flush();

                assertEquals(0, awaitedFuture.ownerSlotCount(
                        PerlOwnerSlot.Kind.SUSPENDED_FRAME));
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
            } finally {
                suspension.releaseAwaitedOwner();
                strongFuture.set(new RuntimeScalar());
                if (WeakRefRegistry.isweak(weakObserver)) {
                    WeakRefRegistry.unweaken(weakObserver);
                }
            }
        }
    }
}

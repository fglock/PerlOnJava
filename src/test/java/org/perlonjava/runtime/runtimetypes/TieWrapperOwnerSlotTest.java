package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class TieWrapperOwnerSlotTest {
    @Test
    void tiedScalarWrapperOwnsAndReleasesDistinctHandlerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar handler = trackedHandler();
            RuntimeBase referent = (RuntimeBase) handler.value;
            TieScalar wrapper = new TieScalar(
                    "TieWrapperOwnerSlotTest", new RuntimeScalar(), handler);
            assertWrapperRelease(referent, handler, wrapper::releaseTiedObject);
        }
    }

    @Test
    void tiedArrayWrapperOwnsAndReleasesDistinctHandlerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar handler = trackedHandler();
            RuntimeBase referent = (RuntimeBase) handler.value;
            TieArray wrapper = new TieArray(
                    "TieWrapperOwnerSlotTest", new RuntimeArray(), handler, new RuntimeArray());
            assertWrapperRelease(referent, handler, wrapper::releaseTiedObject);
        }
    }

    @Test
    void tiedHashWrapperOwnsAndReleasesDistinctHandlerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar handler = trackedHandler();
            RuntimeBase referent = (RuntimeBase) handler.value;
            TieHash wrapper = new TieHash(
                    "TieWrapperOwnerSlotTest", new RuntimeHash(), handler);
            assertWrapperRelease(referent, handler, wrapper::releaseTiedObject);
        }
    }

    @Test
    void tiedHandleWrapperOwnsAndReleasesDistinctHandlerSlot() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar handler = trackedHandler();
            RuntimeBase referent = (RuntimeBase) handler.value;
            TieHandle wrapper = new TieHandle(
                    "TieWrapperOwnerSlotTest", new RuntimeIO(), handler);
            assertWrapperRelease(referent, handler, wrapper::releaseTiedObject);
        }
    }

    @Test
    void wrapperCreatedBeforeHandlerTrackingDoesNotReleaseAnotherOwnersCount() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray referent = new RuntimeArray();
            RuntimeScalar handler = new RuntimeScalar();
            handler.type = RuntimeScalarType.ARRAYREFERENCE;
            handler.value = referent;
            TieArray wrapper = new TieArray(
                    "TieWrapperOwnerSlotTest", new RuntimeArray(), handler, new RuntimeArray());
            assertEquals(-1, referent.refCount);
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.TIE_WRAPPER));

            referent.activateOwnerTracking();
            RuntimeScalar countedOwner = new RuntimeScalar();
            countedOwner.set(referent.createAnonymousReference());
            assertEquals(1, referent.refCount);

            wrapper.releaseTiedObject();

            assertEquals(1, referent.refCount,
                    "a wrapper that never acquired a count cannot release another scalar's count");
            assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.TIE_WRAPPER));
            countedOwner.undefine();
        }
    }

    private static RuntimeScalar trackedHandler() {
        RuntimeArray referent = new RuntimeArray();
        referent.activateOwnerTracking();
        RuntimeScalar handler = new RuntimeScalar();
        handler.set(referent.createAnonymousReference());
        return handler;
    }

    private static void assertWrapperRelease(
            RuntimeBase referent, RuntimeScalar handler, Runnable release) {
        // Constructor increments a second count for the wrapper-held edge.
        assertEquals(2, referent.refCount);
        assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.TIE_WRAPPER));

        release.run();
        assertEquals(1, referent.refCount);
        assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.TIE_WRAPPER));
        assertEquals(1, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));

        release.run();
        assertEquals(1, referent.refCount,
                "repeated wrapper release does not consume the handler cell's count");
        handler.undefine();
        assertEquals(0, referent.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_VALUE));
    }
}

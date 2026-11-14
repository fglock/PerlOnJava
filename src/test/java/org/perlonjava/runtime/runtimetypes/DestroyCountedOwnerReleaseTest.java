package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class DestroyCountedOwnerReleaseTest {
    @Test
    void argumentContainerReleaseBalancesCountInsideDestroy() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.setTrackedRefCount(0);
            RuntimeScalar owner = RuntimeScalar.retainArgumentContainerValue(referent);
            assertEquals(1, referent.refCount);

            referent.currentlyDestroying = true;
            RuntimeScalar.releaseArgumentContainerValue(owner);

            assertEquals(0, referent.refCount);
            assertEquals(0, referent.countedOwnerSlotCount());
            assertFalse(referent.destroyFired,
                    "releasing a temporary owner must not re-enter DESTROY");
        }
    }

    @Test
    void methodInvocantReleaseBalancesCountInsideDestroy() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.setTrackedRefCount(0);
            PerlOwnerSlot owner = new PerlOwnerSlot(PerlOwnerSlot.Kind.ARGUMENT_VALUE);
            owner.acquireCountedMethodInvocantOwner(referent,
                    "DestroyCountedOwnerReleaseTest method hold");
            assertEquals(1, referent.refCount);

            referent.currentlyDestroying = true;
            MortalList.releaseCountedMethodInvocantOwner(referent, owner,
                    "DestroyCountedOwnerReleaseTest method release", false);

            assertEquals(0, referent.refCount);
            assertEquals(0, referent.countedOwnerSlotCount());
            assertFalse(referent.destroyFired,
                    "releasing a method hold must not re-enter DESTROY");
        }
    }
}

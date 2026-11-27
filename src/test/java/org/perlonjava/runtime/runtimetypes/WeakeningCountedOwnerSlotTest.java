package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class WeakeningCountedOwnerSlotTest {
    @Test
    void weakeningReleasesOnlyTheSelectedCountedOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId("FinalWeakOwner::Undercounted");
            RuntimeScalar observer = referent.createAnonymousReference();
            WeakRefRegistry.weaken(observer);

            RuntimeScalar weakenedOwner = new RuntimeScalar();
            RuntimeScalar survivingOwner = new RuntimeScalar();
            MyVarCleanupStack.register(weakenedOwner);
            MyVarCleanupStack.register(survivingOwner);
            weakenedOwner.set(referent.createAnonymousReference());
            survivingOwner.set(referent.createAnonymousReference());
            try {
                assertEquals(2, referent.countedOwnerSlotCount());
                assertEquals(2, referent.refCount);

                {
                    WeakRefRegistry.weaken(weakenedOwner);

                    assertEquals(1, referent.refCount);
                    assertEquals(1, referent.countedOwnerSlotCount());
                    assertTrue(WeakRefRegistry.isweak(observer));
                }
            } finally {
                if (WeakRefRegistry.isweak(weakenedOwner)) {
                    WeakRefRegistry.unweaken(weakenedOwner);
                }
                if (WeakRefRegistry.isweak(observer)) WeakRefRegistry.unweaken(observer);
                MyVarCleanupStack.unregister(weakenedOwner);
                MyVarCleanupStack.unregister(survivingOwner);
                weakenedOwner.set(new RuntimeScalar());
                survivingOwner.set(new RuntimeScalar());
                observer.set(new RuntimeScalar());
            }
        }
    }

}

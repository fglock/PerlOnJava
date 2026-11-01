package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.io.StandardIO;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakGlobIoOwnerSlotTest {
    @Test
    void weakGlobAliasReleasesIoOwnerAndUnweakenRestoresIt() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeGlob glob = new RuntimeGlob(null).setIO(new RuntimeIO(
                    new StandardIO(new ByteArrayOutputStream(), true)));
            glob.refCount = 2;
            RuntimeScalar strong = globAlias(glob);
            RuntimeScalar weak = globAlias(glob);
            retainCountedAlias(glob, strong);
            retainCountedAlias(glob, weak);
            MyVarCleanupStack.register(strong);
            MyVarCleanupStack.register(weak);
            RuntimeScalar.retainUnstashedIoForDurableSlot(strong);
            RuntimeScalar.retainUnstashedIoForDurableSlot(weak);

            try {
                assertEquals(2, glob.ioHolderCount());

                WeakRefRegistry.weaken(weak);

                assertTrue(WeakRefRegistry.isweak(weak));
                assertEquals(1, glob.refCount);
                assertEquals(1, glob.ioHolderCount());
                assertFalse(weak.hasIoOwner());
                assertTrue(strong.hasIoOwner());

                RuntimeScalar.retainUnstashedIoForDurableSlot(weak);
                assertEquals(1, glob.ioHolderCount(),
                        "a durable weak scalar must not reacquire an IO owner");

                WeakRefRegistry.unweaken(weak);

                assertFalse(WeakRefRegistry.isweak(weak));
                assertEquals(2, glob.refCount);
                assertEquals(2, glob.ioHolderCount(),
                        "strengthening the scalar restores its IO owner");
                assertTrue(weak.hasIoOwner());

                WeakRefRegistry.weaken(weak);
                assertEquals(1, glob.refCount);
                assertEquals(1, glob.ioHolderCount());
                assertFalse(weak.hasIoOwner());
            } finally {
                WeakRefRegistry.clearWeakRefsTo(glob);
                MyVarCleanupStack.unregister(weak);
                MyVarCleanupStack.unregister(strong);
                RuntimeScalar.releaseIoOwner(weak);
                RuntimeScalar.releaseIoOwner(strong);
                MortalList.flush();
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    private static RuntimeScalar globAlias(RuntimeGlob glob) {
        RuntimeScalar alias = new RuntimeScalar();
        alias.type = RuntimeScalarType.GLOBREFERENCE;
        alias.value = glob;
        return alias;
    }

    private static void retainCountedAlias(RuntimeGlob glob, RuntimeScalar alias) {
        alias.refCountOwned = true;
        glob.recordOwner(alias, "weak IO owner regression");
        glob.recordActiveOwner(alias);
    }
}

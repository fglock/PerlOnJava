package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class NetSSLeayRuntimeOwnershipTest {
    @Test
    void handleRegistriesAreRuntimeOwned() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();

        first.netSslState.providerHandles.put(41L, "first");
        second.netSslState.providerHandles.put(41L, "second");

        assertEquals("first", first.netSslState.providerHandles.get(41L));
        assertEquals("second", second.netSslState.providerHandles.get(41L));
        first.netSslState.providerHandles.remove(41L);
        assertFalse(first.netSslState.providerHandles.containsKey(41L));
        assertEquals("second", second.netSslState.providerHandles.get(41L));
    }

    @Test
    void storedNativeCallbackRestoresItsOwningRuntimeOnForeignThread() throws Exception {
        PerlRuntime owner = new PerlRuntime();
        RuntimeScalar callback;
        try (PerlRuntime.Binding ignored = owner.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> {
                assertSame(owner, PerlRuntime.current());
                return new RuntimeScalar(42).getList();
            }, null);
            callback = NetSSLeay.bindNativeCallback(new RuntimeScalar(code));
        }

        FutureTask<Integer> task = new FutureTask<>(() -> {
            assertNull(PerlRuntime.currentOrNull());
            int result = RuntimeCode.apply(callback, new RuntimeArray(),
                    RuntimeContextType.SCALAR).scalar().getInt();
            assertNull(PerlRuntime.currentOrNull());
            return result;
        });
        Thread worker = Thread.ofPlatform().name("netssleay-callback-owner").unstarted(task);
        worker.start();
        assertEquals(42, task.get(10, TimeUnit.SECONDS));
        worker.join(10_000);
        assertFalse(worker.isAlive());
    }
}

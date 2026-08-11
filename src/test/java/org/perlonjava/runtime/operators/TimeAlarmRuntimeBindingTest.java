package org.perlonjava.runtime.operators;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.PerlSignalQueue;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TimeAlarmRuntimeBindingTest {

    @Test
    void alarmCallbackUsesOwningRuntimeAndRestoresWorkerBinding() throws Exception {
        PerlRuntime owner = new PerlRuntime();
        RuntimeScalar handler = new RuntimeScalar("handler");
        try (PerlRuntime.Binding ignored = owner.bind()) {
            GlobalVariable.getGlobalHash("main::SIG").put("ALRM", handler);
        }

        PerlSignalQueue.clearSignals();
        Thread target = new Thread(() -> { });
        FutureTask<PerlRuntime> callback = new FutureTask<>(() -> {
            Time.dispatchAlarm(owner, target);
            return PerlRuntime.currentOrNull();
        });
        Thread worker = Thread.ofPlatform().name("alarm-binding-test").start(callback);

        try {
            assertNull(callback.get(10, TimeUnit.SECONDS));
            worker.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(worker.isAlive());
            assertTrue(PerlSignalQueue.hasPendingSignals());
            assertTrue(target.isInterrupted());
        } finally {
            PerlSignalQueue.clearSignals();
        }
    }
}

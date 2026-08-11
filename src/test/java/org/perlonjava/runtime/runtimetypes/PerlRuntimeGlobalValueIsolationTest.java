package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimeGlobalValueIsolationTest {

    @Test
    void scalarArrayAndHashSlotsBelongToTheBoundRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();

        try (PerlRuntime.Binding ignored = first.bind()) {
            GlobalVariable.getGlobalVariable("Phase8a::value").set("first");
            GlobalVariable.getGlobalArray("Phase8a::values").push(new RuntimeScalar("a"));
            GlobalVariable.getGlobalHash("Phase8a::values").put("key", new RuntimeScalar("h1"));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertFalse(GlobalVariable.existsGlobalVariable("Phase8a::value"));
            assertFalse(GlobalVariable.existsGlobalArray("Phase8a::values"));
            assertFalse(GlobalVariable.existsGlobalHash("Phase8a::values"));
            GlobalVariable.getGlobalVariable("Phase8a::value").set("second");
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            assertEquals("first", GlobalVariable.getGlobalVariable("Phase8a::value").toString());
            assertEquals("a", GlobalVariable.getGlobalArray("Phase8a::values").get(0).toString());
            assertEquals("h1", GlobalVariable.getGlobalHash("Phase8a::values").get("key").toString());
        }
    }

    @Test
    void aliasesAndLocalizationRestoreOnlyTheOwningRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();

        try (PerlRuntime.Binding ignored = first.bind()) {
            GlobalVariable.getGlobalVariable("Phase8a::source").set("outer");
            GlobalVariable.aliasGlobalVariable("Phase8a::alias", "Phase8a::source");
            int level = DynamicVariableManager.getLocalLevel();
            GlobalRuntimeScalar.makeLocal("Phase8a::source").set("local");
            assertEquals("local", GlobalVariable.getGlobalVariable("Phase8a::source").toString());
            DynamicVariableManager.popToLocalLevel(level);
            assertEquals("outer", GlobalVariable.getGlobalVariable("Phase8a::source").toString());
            assertSame(GlobalVariable.getGlobalVariable("Phase8a::source"),
                    GlobalVariable.getGlobalVariable("Phase8a::alias"));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertFalse(GlobalVariable.existsGlobalVariable("Phase8a::source"));
            assertFalse(GlobalVariable.existsGlobalVariable("Phase8a::alias"));
        }
    }

    @Test
    void arrayAndHashLocalizationUseRuntimeOwnedSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            GlobalVariable.getGlobalArray("Phase8a::array").push(new RuntimeScalar("outer"));
            GlobalVariable.getGlobalHash("Phase8a::hash").put("key", new RuntimeScalar("outer"));
            int level = DynamicVariableManager.getLocalLevel();
            GlobalRuntimeArray.makeLocal("Phase8a::array").push(new RuntimeScalar("local"));
            GlobalRuntimeHash.makeLocal("Phase8a::hash").put("key", new RuntimeScalar("local"));
            assertEquals("local", GlobalVariable.getGlobalArray("Phase8a::array").get(0).toString());
            assertEquals("local", GlobalVariable.getGlobalHash("Phase8a::hash").get("key").toString());
            DynamicVariableManager.popToLocalLevel(level);
            assertEquals("outer", GlobalVariable.getGlobalArray("Phase8a::array").get(0).toString());
            assertEquals("outer", GlobalVariable.getGlobalHash("Phase8a::hash").get("key").toString());
        }
    }

    @Test
    void resetAndMapViewMutationAffectOnlyTheBoundRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        long firstVersion;

        try (PerlRuntime.Binding ignored = first.bind()) {
            GlobalVariable.globalVariables.put("Phase8a::value", new RuntimeScalar("first"));
            firstVersion = GlobalVariable.stashEnumerationVersion();
            assertTrue(GlobalVariable.globalVariables.keySet().remove("Phase8a::value"));
            assertTrue(GlobalVariable.stashEnumerationVersion() > firstVersion);
            GlobalVariable.getGlobalVariable("Phase8a::value").set("first-again");
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            GlobalVariable.getGlobalVariable("Phase8a::value").set("second");
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            GlobalVariable.resetAllGlobals();
            assertFalse(GlobalVariable.existsGlobalVariable("Phase8a::value"));
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertEquals("second", GlobalVariable.getGlobalVariable("Phase8a::value").toString());
        }
    }

    @Test
    void concurrentRuntimeBindingsDoNotCrossGlobalSlots() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<String> firstTask = globalTask(first, "first", ready, start);
        FutureTask<String> secondTask = globalTask(second, "second", ready, start);
        Thread firstThread = Thread.ofPlatform().start(firstTask);
        Thread secondThread = Thread.ofPlatform().start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());
        assertEquals("first:first:first", firstTask.get(1, TimeUnit.SECONDS));
        assertEquals("second:second:second", secondTask.get(1, TimeUnit.SECONDS));
    }

    private static FutureTask<String> globalTask(
            PerlRuntime runtime, String value, CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                GlobalVariable.getGlobalVariable("Phase8a::shared").set(value);
                GlobalVariable.getGlobalArray("Phase8a::shared").push(new RuntimeScalar(value));
                GlobalVariable.getGlobalHash("Phase8a::shared").put("key", new RuntimeScalar(value));
                return GlobalVariable.getGlobalVariable("Phase8a::shared") + ":"
                        + GlobalVariable.getGlobalArray("Phase8a::shared").get(0) + ":"
                        + GlobalVariable.getGlobalHash("Phase8a::shared").get("key");
            }
        });
    }
}

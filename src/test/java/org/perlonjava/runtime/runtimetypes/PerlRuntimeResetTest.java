package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PerlRuntimeResetTest {
    @Test
    void resetRestoresRepresentativeDomainsAndRemainsExecutable() throws Exception {
        PerlRuntime runtime = new PerlRuntime().initialize();
        PerlRuntime fresh = new PerlRuntime().initialize();

        runtime.globalState.scalarValues().put("ResetContract::tenant", new RuntimeScalar("first"));
        runtime.regexState.optimizedRegexCache.put(7, new RuntimeScalar("compiled"));
        runtime.executionState.taintMode = true;
        runtime.setDefaultPerlThreadStackSize(12345);
        RuntimeIO oldStdout = runtime.ioStdout;

        assertSame(runtime, runtime.reset());

        assertTrue(runtime.isInitialized());
        assertFalse(runtime.isClosed());
        assertFalse(runtime.globalState.scalarValues().containsKey("ResetContract::tenant"));
        assertEquals(fresh.globalState.scalarValues().keySet(), runtime.globalState.scalarValues().keySet());
        assertTrue(runtime.regexState.optimizedRegexCache.isEmpty());
        assertFalse(runtime.executionState.taintMode);
        assertEquals(0, runtime.defaultPerlThreadStackSize());
        assertNotSame(oldStdout, runtime.ioStdout);
        assertSame(runtime.ioStdout, runtime.ioSelectedHandle);
        assertEquals("reused", runtime.execute(() -> "reused"));
    }

    @Test
    void resetRejectsAnOutstandingBindingWithoutPoisoningRuntime() {
        PerlRuntime runtime = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::reset);
            assertTrue(failure.getMessage().contains("bindings"));
        }
        assertFalse(runtime.isClosed());
        assertSame(runtime, runtime.reset());
    }

    @Test
    void perlWorkloadAfterResetMatchesFreshRuntimeOnBothBackends() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime used = new PerlRuntime();
            PerlRuntime fresh = new PerlRuntime();

            run(used,
                    "package ResetTenant; our $value = 41; sub answer { 42 };"
                            + " $INC{'Reset/Tenant.pm'} = __FILE__; 'abc' =~ /(b)/; 1",
                    interpreter);
            used.reset();

            String probe = "no strict 'refs'; join ':',"
                    + " defined($ResetTenant::value) ? 1 : 0,"
                    + " defined(&ResetTenant::answer) ? 1 : 0,"
                    + " exists($INC{'Reset/Tenant.pm'}) ? 1 : 0,"
                    + " defined($1) ? 1 : 0";
            assertEquals(run(fresh, probe, interpreter), run(used, probe, interpreter));
            assertEquals("0:0:0:0", run(used, probe, interpreter));
        }
    }

    @Test
    void resetRejectsActiveChildAndSucceedsAfterJoin() throws Exception {
        PerlRuntime runtime = new PerlRuntime().initialize();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PerlThreadControlBlock child;
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            child = PerlThreadControlBlock.create(runtime, childRuntime -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new RuntimeScalar(1);
            }).start();
        }

        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::reset);
            assertTrue(failure.getMessage().contains("child threads"));
        } finally {
            release.countDown();
            child.join();
        }
        assertSame(runtime, runtime.reset());
        assertEquals(0, runtime.threadRegistry().size());
    }

    @Test
    void resetRejectsAnOutstandingSharedLock() {
        PerlRuntime runtime = new PerlRuntime().initialize();
        int level;
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar shared = new RuntimeScalar(1);
            SharedPerlStorage.shareValue(shared);
            level = DynamicVariableManager.getLocalLevel();
            SharedPerlStorage.lock(shared.createReference());
        }
        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::reset);
        assertTrue(failure.getMessage().contains("shared locks"));
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            DynamicVariableManager.popToLocalLevel(level);
        }
        assertSame(runtime, runtime.reset());
    }

    @Test
    void resetDrainsPendingEndWorkBeforeReinitializing() {
        PerlRuntime runtime = new PerlRuntime().initialize();
        AtomicInteger endRuns = new AtomicInteger();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode end = new RuntimeCode((args, context) -> {
                endRuns.incrementAndGet();
                return new RuntimeList();
            }, null);
            SpecialBlock.saveEndBlock(new RuntimeScalar(end));
        }

        runtime.reset();

        assertEquals(1, endRuns.get());
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            assertTrue(SpecialBlock.getEndBlocks().isEmpty());
        }
    }

    @Test
    void resetFailurePoisonsRuntime() {
        PerlRuntime runtime = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode failingEnd = new RuntimeCode((args, context) -> {
                throw new IllegalStateException("reset END failed");
            }, null);
            SpecialBlock.saveEndBlock(new RuntimeScalar(failingEnd));
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::reset);
        assertEquals("reset END failed", failure.getMessage());
        assertTrue(runtime.isClosed());
        assertThrows(IllegalStateException.class, runtime::bind);
    }

    private static String run(PerlRuntime runtime, String source, boolean interpreter)
            throws Exception {
        return runtime.execute(() -> {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<runtime-reset-contract>";
            options.code = source;
            options.useInterpreter = interpreter;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        });
    }
}

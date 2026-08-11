package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class GlobalGlobStashRuntimeIsolationTest {

    @Test
    void stashObjectsAliasesAndEnumerationCachesFollowNestedRuntimeBindings() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeHash firstStash;

        try (PerlRuntime.Binding ignored = first.bind()) {
            firstStash = GlobalVariable.getGlobalHash("Phase8dIdentity::");
            GlobalVariable.setStashAlias("Phase8dAlias", "Phase8dFirst");
            GlobalVariable.setGlobAlias("Phase8dGlobAlias", "Phase8dFirst::slot");
            GlobalVariable.getGlobalVariable("Phase8dCache::first").set("first");

            assertEquals("Phase8dFirst", GlobalVariable.resolveStashAlias("Phase8dAlias"));
            assertEquals("Phase8dFirst::slot",
                    GlobalVariable.resolveGlobAlias("Phase8dGlobAlias"));
            assertEquals(Set.of("first"), stashKeys("Phase8dCache::"));

            try (PerlRuntime.Binding nested = second.bind()) {
                RuntimeHash secondStash = GlobalVariable.getGlobalHash("Phase8dIdentity::");
                assertNotSame(firstStash, secondStash);
                assertEquals("Phase8dAlias", GlobalVariable.resolveStashAlias("Phase8dAlias"));
                assertEquals("Phase8dGlobAlias",
                        GlobalVariable.resolveGlobAlias("Phase8dGlobAlias"));

                GlobalVariable.setStashAlias("Phase8dAlias", "Phase8dSecond");
                GlobalVariable.setGlobAlias("Phase8dGlobAlias", "Phase8dSecond::slot");
                GlobalVariable.getGlobalVariable("Phase8dCache::second").set("second");

                // Both runtimes deliberately have the same mutation-version value.
                // A process-wide cache would return the first runtime's key here.
                assertEquals(Set.of("second"), stashKeys("Phase8dCache::"));
            }

            assertEquals("Phase8dFirst", GlobalVariable.resolveStashAlias("Phase8dAlias"));
            assertEquals("Phase8dFirst::slot",
                    GlobalVariable.resolveGlobAlias("Phase8dGlobAlias"));
            assertEquals(Set.of("first"), stashKeys("Phase8dCache::"));
        }
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void simultaneousAliasMutationDoesNotCrossRuntimes() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<AliasSnapshot> firstTask = aliasTask(first, "First", ready, start);
        FutureTask<AliasSnapshot> secondTask = aliasTask(second, "Second", ready, start);
        Thread firstThread = Thread.ofPlatform().name("phase8d-alias-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("phase8d-alias-second").start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());

        assertEquals(new AliasSnapshot("Phase8dFirst::", "Phase8dFirst::slot"),
                firstTask.get(1, TimeUnit.SECONDS));
        assertEquals(new AliasSnapshot("Phase8dSecond::", "Phase8dSecond::slot"),
                secondTask.get(1, TimeUnit.SECONDS));
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void resetClearsOnlyTheBoundRuntimesGlobAndStashIdentity() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        installAliases(first, "First");
        installAliases(second, "Second");

        try (PerlRuntime.Binding ignored = first.bind()) {
            GlobalVariable.resetAllGlobals();
            assertEquals("Phase8dAlias::", GlobalVariable.resolveStashAlias("Phase8dAlias::"));
            assertEquals("Phase8dGlobAlias",
                    GlobalVariable.resolveGlobAlias("Phase8dGlobAlias"));
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertEquals("Phase8dSecond::",
                    GlobalVariable.resolveStashAlias("Phase8dAlias::"));
            assertEquals("Phase8dSecond::slot",
                    GlobalVariable.resolveGlobAlias("Phase8dGlobAlias"));
        }
    }

    @Test
    void conflictingGlobAndStashAliasesStayIsolatedOnJvmAndInterpreter() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime first = new PerlRuntime();
            PerlRuntime second = new PerlRuntime();

            installPerlAliases(first, "First", "first", interpreter);
            installPerlAliases(second, "Second", "second", interpreter);

            assertEquals("first:glob-first", readPerlAliases(first, interpreter));
            assertEquals("second:glob-second", readPerlAliases(second, interpreter));

            try (PerlRuntime.Binding ignored = first.bind()) {
                RuntimeHash firstAliasStash = GlobalVariable.getGlobalHash("Phase8dAlias::");
                try (PerlRuntime.Binding nested = second.bind()) {
                    assertNotSame(firstAliasStash,
                            GlobalVariable.getGlobalHash("Phase8dAlias::"));
                }
            }
        }
    }

    private static Set<String> stashKeys(String namespace) {
        return new HashSpecialVariable(HashSpecialVariable.Id.STASH, namespace).keySet();
    }

    private static FutureTask<AliasSnapshot> aliasTask(
            PerlRuntime runtime, String target, CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                installAliasesInCurrentRuntime(target);
                return new AliasSnapshot(
                        GlobalVariable.resolveStashAlias("Phase8dAlias::"),
                        GlobalVariable.resolveGlobAlias("Phase8dGlobAlias"));
            }
        });
    }

    private static void installAliases(PerlRuntime runtime, String target) {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            installAliasesInCurrentRuntime(target);
        }
    }

    private static void installAliasesInCurrentRuntime(String target) {
        GlobalVariable.setStashAlias("Phase8dAlias::", "Phase8d" + target + "::");
        GlobalVariable.setGlobAlias("Phase8dGlobAlias", "Phase8d" + target + "::slot");
    }

    private static void installPerlAliases(
            PerlRuntime runtime, String target, String value, boolean interpreter) throws Exception {
        run(runtime,
                "$Phase8d" + target + "::value = '" + value + "';"
                        + " $Phase8d" + target + "::glob = 'glob-" + value + "';"
                        + " *Phase8dAlias:: = *Phase8d" + target + "::;"
                        + " *Phase8dGlobAlias = *Phase8d" + target + "::glob; 1",
                interpreter);
    }

    private static String readPerlAliases(PerlRuntime runtime, boolean interpreter)
            throws Exception {
        return run(runtime,
                "no strict 'refs'; ${'Phase8dAlias::value'} . ':' . $Phase8dGlobAlias",
                interpreter);
    }

    private static String run(PerlRuntime runtime, String source, boolean interpreter)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<global-glob-stash-runtime-isolation>";
            options.code = source;
            options.useInterpreter = interpreter;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }

    private record AliasSnapshot(String stash, String glob) {
    }
}

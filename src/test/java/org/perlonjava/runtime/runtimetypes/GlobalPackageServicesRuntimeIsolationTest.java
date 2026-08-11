package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.backend.jvm.CustomClassLoader;
import org.perlonjava.frontend.parser.FieldRegistry;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class GlobalPackageServicesRuntimeIsolationTest {

    @Test
    void declarationsPackageMetadataAndClassLoadersFollowNestedBindings() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CustomClassLoader firstLoader;

        try (PerlRuntime.Binding ignored = first.bind()) {
            installPackageServices("first");
            firstLoader = GlobalVariable.getGlobalClassLoader();
            assertPackageServices("first", firstLoader);

            try (PerlRuntime.Binding nested = second.bind()) {
                assertPackageServicesAbsent(firstLoader);
                installPackageServices("second");
                assertPackageServices("second", GlobalVariable.getGlobalClassLoader());
            }

            assertPackageServices("first", firstLoader);
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertPackageServices("second", GlobalVariable.getGlobalClassLoader());
        }
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void simultaneousPackageServiceMutationDoesNotCrossRuntimes() throws Exception {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<PackageSnapshot> firstTask = packageTask(first, "first", ready, start);
        FutureTask<PackageSnapshot> secondTask = packageTask(second, "second", ready, start);
        Thread firstThread = Thread.ofPlatform().name("phase8e-package-first").start(firstTask);
        Thread secondThread = Thread.ofPlatform().name("phase8e-package-second").start(secondTask);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstThread.join(TimeUnit.SECONDS.toMillis(10));
            secondThread.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(firstThread.isAlive());
        assertFalse(secondThread.isAlive());

        PackageSnapshot firstResult = firstTask.get(1, TimeUnit.SECONDS);
        PackageSnapshot secondResult = secondTask.get(1, TimeUnit.SECONDS);
        assertEquals("first", firstResult.marker);
        assertEquals("second", secondResult.marker);
        assertNotSame(firstResult.loader, secondResult.loader);
        assertNull(PerlRuntime.currentOrNull());
    }

    @Test
    void resetClearsOnlyTheBoundRuntimePackageServicesAndReplacesItsLoader() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        CustomClassLoader firstLoader;
        CustomClassLoader secondLoader;

        try (PerlRuntime.Binding ignored = first.bind()) {
            installPackageServices("first");
            firstLoader = GlobalVariable.getGlobalClassLoader();
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            installPackageServices("second");
            secondLoader = GlobalVariable.getGlobalClassLoader();
        }

        try (PerlRuntime.Binding ignored = first.bind()) {
            GlobalVariable.resetAllGlobals();
            assertPackageServicesAbsent(firstLoader);
            assertNotSame(firstLoader, GlobalVariable.getGlobalClassLoader());
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertPackageServices("second", secondLoader);
        }
    }

    @Test
    void declaredGlobalsAffectOnlyTheirRuntimeOnBothBackends() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime declared = new PerlRuntime();
            PerlRuntime undeclared = new PerlRuntime();

            try (PerlRuntime.Binding ignored = declared.bind()) {
                GlobalVariable.declareGlobalVariable("Phase8eStrict::X");
                // The strict-vars single-letter exception requires both an
                // explicitly declared slot and the package scalar it names.
                GlobalVariable.getGlobalVariable("Phase8eStrict::X");
            }
            assertEquals("42", run(declared,
                    "package Phase8eStrict; use strict 'vars'; $X = 42; $X",
                    interpreter));

            Exception error = assertThrows(Exception.class, () -> run(undeclared,
                    "package Phase8eStrict; use strict 'vars'; $X = 99; $X",
                    interpreter));
            assertTrue(error.getMessage().contains("Global symbol \"$X\""), error::getMessage);
        }
    }

    private static void installPackageServices(String marker) {
        GlobalVariable.packageExistsCache.put("Phase8e::Package", true);
        GlobalVariable.declareGlobalVariable("Phase8e::scalar");
        GlobalVariable.declareGlobalArray("Phase8e::array");
        GlobalVariable.declareGlobalHash("Phase8e::hash");
        ClassRegistry.registerClass("Phase8e::Class");
        FieldRegistry.registerField("Phase8e::Class", marker);
        FieldRegistry.registerParentClass("Phase8e::Class", marker + "::Parent");
        new ScopedSymbolTable().setPackageVersion("Phase8e::Package", marker);
    }

    private static void assertPackageServices(String marker, CustomClassLoader loader) {
        assertTrue(GlobalVariable.packageExistsCache.getOrDefault("Phase8e::Package", false));
        assertTrue(GlobalVariable.isDeclaredGlobalVariable("Phase8e::scalar"));
        assertTrue(GlobalVariable.isDeclaredGlobalArray("Phase8e::array"));
        assertTrue(GlobalVariable.isDeclaredGlobalHash("Phase8e::hash"));
        assertTrue(ClassRegistry.isClass("Phase8e::Class"));
        assertEquals(marker + "::Parent", FieldRegistry.getParentClass("Phase8e::Class"));
        assertTrue(FieldRegistry.getClassFields("Phase8e::Class").contains(marker));
        assertEquals(marker, new ScopedSymbolTable().getPackageVersion("Phase8e::Package"));
        assertSame(loader, GlobalVariable.getGlobalClassLoader());
    }

    private static void assertPackageServicesAbsent(CustomClassLoader otherLoader) {
        assertFalse(GlobalVariable.packageExistsCache.containsKey("Phase8e::Package"));
        assertFalse(GlobalVariable.isDeclaredGlobalVariable("Phase8e::scalar"));
        assertFalse(GlobalVariable.isDeclaredGlobalArray("Phase8e::array"));
        assertFalse(GlobalVariable.isDeclaredGlobalHash("Phase8e::hash"));
        assertFalse(ClassRegistry.isClass("Phase8e::Class"));
        assertNull(FieldRegistry.getParentClass("Phase8e::Class"));
        assertTrue(FieldRegistry.getClassFields("Phase8e::Class").isEmpty());
        assertNull(new ScopedSymbolTable().getPackageVersion("Phase8e::Package"));
        assertNotSame(otherLoader, GlobalVariable.getGlobalClassLoader());
    }

    private static FutureTask<PackageSnapshot> packageTask(
            PerlRuntime runtime, String marker, CountDownLatch ready, CountDownLatch start) {
        return new FutureTask<>(() -> {
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                installPackageServices(marker);
                CustomClassLoader loader = GlobalVariable.getGlobalClassLoader();
                assertPackageServices(marker, loader);
                return new PackageSnapshot(marker, loader);
            }
        });
    }

    private static String run(PerlRuntime runtime, String source, boolean interpreter)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<global-package-services-runtime-isolation>";
            options.code = source;
            options.useInterpreter = interpreter;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }

    private record PackageSnapshot(String marker, CustomClassLoader loader) {
    }
}

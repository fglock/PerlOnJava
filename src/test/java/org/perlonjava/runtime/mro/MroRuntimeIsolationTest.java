package org.perlonjava.runtime.mro;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.perlmodule.Mro;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class MroRuntimeIsolationTest {

    @Test
    void mroPolicyAutoloadAndDerivedCachesBelongToTheBoundRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeScalar firstMethod = new RuntimeScalar("first-method");
        RuntimeScalar secondMethod = new RuntimeScalar("second-method");

        try (PerlRuntime.Binding ignored = first.bind()) {
            InheritanceResolver.setDefaultMRO(InheritanceResolver.MROAlgorithm.C3);
            InheritanceResolver.setPackageMRO("Same::Package", InheritanceResolver.MROAlgorithm.C3);
            InheritanceResolver.setAutoloadEnabled(false);
            InheritanceResolver.cacheMethod("Same::Package::method", firstMethod);
            first.mroState().linearizedClassesCache().put(
                    "Same::Package", List.of("Same::Package", "First::Parent"));
            first.mroState().isaRevCache().put("Base", Set.of("First::Child"));
            Mro.incrementPackageGeneration("Same::Package");
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertEquals(InheritanceResolver.MROAlgorithm.DFS,
                    InheritanceResolver.getPackageMRO("Same::Package"));
            assertTrue(InheritanceResolver.isAutoloadEnabled());
            assertNull(InheritanceResolver.getCachedMethod("Same::Package::method"));
            assertFalse(second.mroState().linearizedClassesCache().containsKey("Same::Package"));
            assertFalse(second.mroState().isaRevCache().containsKey("Base"));
            assertEquals("1", Mro.get_pkg_gen(
                    new org.perlonjava.runtime.runtimetypes.RuntimeArray(
                            new RuntimeScalar("Same::Package")),
                    RuntimeContextType.SCALAR).getFirst().toString());

            InheritanceResolver.cacheMethod("Same::Package::method", secondMethod);
        }

        try (PerlRuntime.Binding ignored = first.bind()) {
            assertEquals(InheritanceResolver.MROAlgorithm.C3,
                    InheritanceResolver.getPackageMRO("Same::Package"));
            assertFalse(InheritanceResolver.isAutoloadEnabled());
            assertSame(firstMethod,
                    InheritanceResolver.getCachedMethod("Same::Package::method"));
            assertEquals(List.of("Same::Package", "First::Parent"),
                    first.mroState().linearizedClassesCache().get("Same::Package"));
            assertEquals(Set.of("First::Child"), first.mroState().isaRevCache().get("Base"));
            assertEquals("2", Mro.get_pkg_gen(
                    new org.perlonjava.runtime.runtimetypes.RuntimeArray(
                            new RuntimeScalar("Same::Package")),
                    RuntimeContextType.SCALAR).getFirst().toString());
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertSame(secondMethod,
                    InheritanceResolver.getCachedMethod("Same::Package::method"));
        }
    }

    @Test
    void runtimeLocalMroPolicyChangesDoNotInvalidateAnotherRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        RuntimeScalar cached = new RuntimeScalar("cached");

        try (PerlRuntime.Binding ignored = second.bind()) {
            InheritanceResolver.cacheMethod("Shared::method", cached);
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            InheritanceResolver.setPackageMRO("Shared", InheritanceResolver.MROAlgorithm.C3);
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertSame(cached, InheritanceResolver.getCachedMethod("Shared::method"));
            assertEquals(InheritanceResolver.MROAlgorithm.DFS,
                    InheritanceResolver.getPackageMRO("Shared"));
        }
    }

    @Test
    void sharedSymbolMutationEpochLazilyInvalidatesEveryRuntime() {
        PerlRuntime mutatingRuntime = new PerlRuntime();
        PerlRuntime observingRuntime = new PerlRuntime();
        RuntimeScalar stale = new RuntimeScalar("stale");

        try (PerlRuntime.Binding ignored = observingRuntime.bind()) {
            InheritanceResolver.cacheMethod("Epoch::target", stale);
        }

        try (PerlRuntime.Binding ignored = mutatingRuntime.bind()) {
            InheritanceResolver.invalidateMethodLookupCachesForStashSubKey("Epoch::target");
        }

        // Observation is deliberately lazy: merely mutating another runtime does
        // not require a registry of every live PerlRuntime.
        assertSame(stale, observingRuntime.mroState().methodCache().get("Epoch::target"));
        try (PerlRuntime.Binding ignored = observingRuntime.bind()) {
            assertNull(InheritanceResolver.getCachedMethod("Epoch::target"));
        }
        assertFalse(observingRuntime.mroState().methodCache().containsKey("Epoch::target"));
    }

    @Test
    void isaMutationGenerationIsObservedLazilyPerRuntime() {
        PerlRuntime mutatingRuntime = new PerlRuntime();
        PerlRuntime observingRuntime = new PerlRuntime();
        long before;

        try (PerlRuntime.Binding ignored = observingRuntime.bind()) {
            before = InheritanceResolver.getIsaGeneration();
            observingRuntime.mroState().isaRevCache().put("Base", Set.of("Old::Child"));
            observingRuntime.mroState().setIsaRevGeneration(before);
        }
        try (PerlRuntime.Binding ignored = mutatingRuntime.bind()) {
            InheritanceResolver.noteIsaMutation();
        }

        assertEquals(before, observingRuntime.mroState().isaGeneration());
        try (PerlRuntime.Binding ignored = observingRuntime.bind()) {
            assertTrue(InheritanceResolver.getIsaGeneration() > before);
            assertTrue(observingRuntime.mroState().isaRevCache().isEmpty());
            assertEquals(-1, observingRuntime.mroState().isaRevGeneration());
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.operators.Random;
import org.perlonjava.runtime.perlmodule.NetSSLeay;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class MiscRuntimeIsolationTest {

    @Test
    void randomSignalsStateVariablesEnvironmentAndRegistriesAreIndependent() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        double firstRandom;
        double firstSecondRandom;
        long firstLibContext;
        RuntimeScalar topLevel = new RuntimeScalar();

        try (PerlRuntime.Binding ignored = first.bind()) {
            Random.srand(new RuntimeScalar(12345));
            firstRandom = Random.rand(new RuntimeScalar(1)).getDouble();
            firstSecondRandom = Random.rand(new RuntimeScalar(1)).getDouble();
            PerlSignalQueue.enqueue("USR1", new RuntimeScalar("first-handler"));
            StateVariable.markInitializedStateVariable(topLevel, "$phase11", 17);
            RuntimeEnvironment.setCurrentDirectory("/tmp/phase11-first");
            first.ioRegistryState.nextFileno.set(41);
            first.lifecycleState.weakRefsExist = true;
            first.diamondIOState.accumulatedLineNumber = 12;
            assertEquals(1, NameNormalizer.getBlessId("First::Class"));
            firstLibContext = NetSSLeay.OSSL_LIB_CTX_get0_global_default(
                    new RuntimeArray(), RuntimeContextType.SCALAR).scalar().getLong();
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertFalse(PerlSignalQueue.hasPendingSignals());
            assertFalse(StateVariable.isInitializedStateVariable(topLevel, "$phase11", 17)
                    .getBoolean());
            assertNotEquals("/tmp/phase11-first", RuntimeEnvironment.currentDirectory());
            assertEquals(3, second.ioRegistryState.nextFileno.get());
            assertFalse(second.lifecycleState.weakRefsExist);
            assertEquals(0, second.diamondIOState.accumulatedLineNumber);
            assertEquals(1, NameNormalizer.getBlessId("Second::Class"));
            assertEquals("Second::Class", NameNormalizer.getBlessStr(1));
            assertNotEquals(firstLibContext,
                    NetSSLeay.OSSL_LIB_CTX_get0_global_default(
                            new RuntimeArray(), RuntimeContextType.SCALAR).scalar().getLong());

            Random.srand(new RuntimeScalar(12345));
            assertEquals(firstRandom, Random.rand(new RuntimeScalar(1)).getDouble());
            assertEquals(firstSecondRandom, Random.rand(new RuntimeScalar(1)).getDouble());
            RuntimeEnvironment.setCurrentDirectory("/tmp/phase11-second");
        }

        try (PerlRuntime.Binding ignored = first.bind()) {
            assertTrue(PerlSignalQueue.hasPendingSignals());
            assertTrue(StateVariable.isInitializedStateVariable(topLevel, "$phase11", 17)
                    .getBoolean());
            assertEquals("/tmp/phase11-first", RuntimeEnvironment.currentDirectory());
            assertEquals(41, first.ioRegistryState.nextFileno.get());
            assertTrue(first.lifecycleState.weakRefsExist);
            assertEquals(12, first.diamondIOState.accumulatedLineNumber);
            assertEquals("First::Class", NameNormalizer.getBlessStr(1));
            assertEquals(firstLibContext,
                    NetSSLeay.OSSL_LIB_CTX_get0_global_default(
                            new RuntimeArray(), RuntimeContextType.SCALAR).scalar().getLong());
            PerlSignalQueue.clearSignals();
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertEquals("/tmp/phase11-second", RuntimeEnvironment.currentDirectory());
        }
    }
}

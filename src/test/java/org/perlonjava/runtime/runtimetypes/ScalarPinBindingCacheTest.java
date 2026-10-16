package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ScalarPinBindingCacheTest {
    @Test
    void cachedPinTracksDynamicAndRuntimeSpecificScalarBindings() {
        String name = "main::__scalar_pin_binding_cache_test";
        PerlRuntime firstRuntime = new PerlRuntime().initialize();
        ScalarPin pin;
        RuntimeScalar firstValue = new RuntimeScalar(1);
        RuntimeScalar temporaryValue = new RuntimeScalar(2);

        try (PerlRuntime.Binding ignored = firstRuntime.bind()) {
            GlobalVariable.globalVariables.put(name, firstValue);
            pin = GlobalVariable.pinScalar(name);
            assertSame(firstValue, pin.scalar());

            GlobalVariable.aliasTemporaryGlobalVariable(name, temporaryValue);
            assertSame(temporaryValue, pin.scalar());

            GlobalVariable.restoreTemporaryGlobalVariable(name, firstValue, false);
            assertSame(firstValue, pin.scalar());
        } finally {
            firstRuntime.close();
        }

        PerlRuntime secondRuntime = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = secondRuntime.bind()) {
            RuntimeScalar secondValue = new RuntimeScalar(3);
            GlobalVariable.globalVariables.put(name, secondValue);
            assertSame(secondValue, pin.scalar());
        } finally {
            secondRuntime.close();
        }
    }
}

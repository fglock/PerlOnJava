package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class ScalarRegistryIsNotPerlOwnerTest {
    @Test
    void jvmLiveRegistryOnlyScalarDoesNotRescueZeroCountReferent() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            runtime.lifecycleState.weakRefsExist = true;

            RuntimeArray referent = new RuntimeArray();
            referent.refCount = 1;

            // This wrapper remains Java-live in the test, but it has no Perl
            // storage slot, lexical binding, or owner identity.
            RuntimeScalar registryOnly = new RuntimeScalar();
            registryOnly.type = RuntimeScalarType.REFERENCE;
            registryOnly.value = referent;
            ScalarRefRegistry.registerRef(registryOnly);
            assertFalse(registryOnly.hasOwnerSlots());

            RuntimeScalar observer = new RuntimeScalar();
            observer.type = RuntimeScalarType.REFERENCE;
            observer.value = referent;
            observer.refCountOwned = true;
            WeakRefRegistry.weaken(observer);

            assertEquals(Integer.MIN_VALUE, referent.refCount,
                    "a JVM registry entry without a Perl owner cannot rescue the referent");
            assertEquals(RuntimeScalarType.UNDEF, observer.type,
                    "the last real owner release clears the weak observer immediately");
            assertFalse(WeakRefRegistry.hasWeakRefsTo(referent));
        }
    }
}

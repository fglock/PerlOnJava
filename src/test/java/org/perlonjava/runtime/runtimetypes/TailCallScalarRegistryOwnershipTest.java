package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TailCallScalarRegistryOwnershipTest {
    @Test
    void javaLiveScalarRegistryDoesNotRescueReleasedTailCallReferent() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash referent = new RuntimeHash();
            RuntimeScalar owner = new RuntimeScalar();
            owner.set(referent.createAnonymousReference());

            RuntimeScalar weak = referent.createReference();
            WeakRefRegistry.weaken(weak);
            ScalarRefRegistry.registerRef(owner);
            assertTrue(ScalarRefRegistry.snapshot().contains(owner));
            assertTrue(WeakRefRegistry.hasWeakRefsTo(referent));

            RuntimeArray tailArguments = new RuntimeArray(1);
            tailArguments.elements.add(owner);
            tailArguments.elementsOwned = true;
            MortalList.releaseTailCallArgs(tailArguments);

            assertFalse(owner.refCountOwned);
            assertTrue(ScalarRefRegistry.snapshot().contains(owner),
                    "the Java-live scalar remains registered after its Perl owner leaves");
            assertEquals(Integer.MIN_VALUE, referent.refCount);
            assertEquals(RuntimeScalarType.UNDEF, weak.type);
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TailCallUncountedOwnerSlotFastPathTest {
    @Test
    void packageScalarOwnerDefersFinalTailCallReleaseWithoutRootWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeScalar target = new RuntimeScalar();
            String globalName =
                    "TailCallUncountedOwnerSlotFastPathTest::packageScalarOwner";
            GlobalVariable.globalVariables.put(globalName, target);
            target.setTrackedRefCount(0);

            RuntimeScalar tailArgument = new RuntimeScalar();
            tailArgument.set(target.createReference());
            RuntimeArray tailArguments = new RuntimeArray(1);
            tailArguments.elements.add(tailArgument);
            tailArguments.elementsOwned = true;

            RuntimeScalar weak = target.createReference();
            WeakRefRegistry.weaken(weak);

            try {
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.PACKAGE_SCALAR_SLOT));

                {
                    MortalList.releaseTailCallArgs(tailArguments);

                    assertEquals(RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED, target.refCountTrackingState());
                    assertTrue(WeakRefRegistry.isweak(weak));
                }

                GlobalVariable.globalVariables.remove(globalName);

                assertFalse(WeakRefRegistry.isweak(weak));
                assertEquals(RuntimeScalarType.UNDEF, weak.type);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, target.perlLifecycleState());
                assertEquals(0, target.refCount);
            } finally {
                if (WeakRefRegistry.isweak(weak)) WeakRefRegistry.unweaken(weak);
                GlobalVariable.globalVariables.remove(globalName);
            }
        }
    }
}

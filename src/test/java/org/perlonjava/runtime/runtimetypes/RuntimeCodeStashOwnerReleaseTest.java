package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeCodeStashOwnerReleaseTest {
    @Test
    void activeCallReleasesStateAfterLastStashOwnerLeavesDespiteStaleBridgeCount() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String name = "RuntimeCodeStashOwnerReleaseTest::active";
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.hadStashRef = true;
            code.stashRefCount = 1;

            RuntimeScalar stashSlot = new RuntimeScalar();
            stashSlot.type = RuntimeScalarType.CODE;
            stashSlot.value = code;
            GlobalVariable.globalCodeRefs.put(name, stashSlot);

            RuntimeScalar weak = new RuntimeScalar();
            weak.type = RuntimeScalarType.CODE;
            weak.value = code;
            WeakRefRegistry.weaken(weak);
            // The bridge count can outlive its Perl owners during the staged
            // migration; the owner ledger must decide final state cleanup.
            code.refCount = 5;

            PerlOwnerSlot activeCall =
                    new PerlOwnerSlot(PerlOwnerSlot.Kind.ACTIVE_CODE);
            activeCall.acquireActiveCodeIdentity(code);

            try {
                GlobalVariable.removeGlobalCodeRefForStashDelete(name);
                assertTrue(WeakRefRegistry.isweak(weak),
                        "the active call keeps CODE state alive until it returns");

                activeCall.release();

                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "the active call releases Perl state despite a stale bridge count");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        code.perlLifecycleState());
            } finally {
                if (activeCall.isActive()) activeCall.release();
                GlobalVariable.globalCodeRefs.remove(name);
            }
        }
    }
}

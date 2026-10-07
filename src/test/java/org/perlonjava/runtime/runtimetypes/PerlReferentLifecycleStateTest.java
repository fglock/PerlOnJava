package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class PerlReferentLifecycleStateTest {
    @Test
    void lifecycleTransitionsAreIndependentFromTheSelectiveCountSentinel() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            assertEquals(RuntimeBase.PerlLifecycleState.LIVE, referent.perlLifecycleState());

            referent.beginPerlDestruction();
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYING, referent.perlLifecycleState());

            referent.markPerlResurrected();
            assertEquals(RuntimeBase.PerlLifecycleState.RESURRECTED, referent.perlLifecycleState());

            referent.beginPerlDestruction();
            referent.markPerlDestroyed();
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, referent.perlLifecycleState());
            assertEquals(-1, referent.refCount,
                    "the explicit lifecycle state does not encode refCount sentinels");
        }
    }

    @Test
    void destroyDispatchRecordsFinalUnblessedLifecycleSeparately() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 0;

            DestroyDispatch.callDestroy(referent);

            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, referent.perlLifecycleState());
            assertEquals(0, referent.refCount,
                    "lifecycle completion is represented without rewriting the selective count");

            DestroyDispatch.callDestroy(referent);
            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, referent.perlLifecycleState());
            assertEquals(0, referent.refCount, "completed lifecycle ignores repeated destroy dispatch");
        }
    }
}

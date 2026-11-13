package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class TailCallActiveLexicalOwnerTest {
    @Test
    void tailCallReleaseUsesActiveLexicalOwnerWithoutPositiveCountPin() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeArray target = new RuntimeArray();
            target.setTrackedRefCount(0);
            target.localBindingExists = true;

            PerlOwnerSlot lexicalOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.ACTIVE_LEXICAL);
            lexicalOwner.acquireActiveLexicalIdentity(target);

            RuntimeScalar tailArgument = new RuntimeScalar();
            tailArgument.set(target.createAnonymousReference());
            RuntimeArray tailArguments = new RuntimeArray(1);
            tailArguments.elements.add(tailArgument);
            tailArguments.elementsOwned = true;

            RuntimeScalar weakObserver = target.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);
            try {
                assertEquals(1, target.refCount);
                assertTrue(tailArgument.refCountOwned);
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ACTIVE_LEXICAL));

                MortalList.releaseTailCallArgs(tailArguments);

                assertEquals(RuntimeBase.RefCountTrackingState.WEAKLY_TRACKED, target.refCountTrackingState(),
                        "the active lexical identity defers cleanup without a fabricated count");
                assertTrue(WeakRefRegistry.isweak(weakObserver),
                        "the surviving lexical owner keeps weak observers live");

                target.localBindingExists = false;
                lexicalOwner.release();

                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, target.perlLifecycleState(),
                        "the final active lexical owner completes cleanup");
                assertEquals(0, target.refCount);
                assertFalse(WeakRefRegistry.isweak(weakObserver));
                assertEquals(RuntimeScalarType.UNDEF, weakObserver.type);
            } finally {
                if (lexicalOwner.isActive()) {
                    target.localBindingExists = false;
                    lexicalOwner.release();
                }
                WeakRefRegistry.clearWeakRefsTo(target);
                MortalList.flush();
            }
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class PerlReferentDestroyLifecycleTest {
    @Test
    void blessedDestroyExceptionStillCompletesLifecycle() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "Issue1649::ThrowingDestroy";
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = Integer.MIN_VALUE;
            installDestroy(className, (args, context) -> {
                throw new PerlDieException(new RuntimeScalar("destructor failed\n"));
            });

            try {
                DestroyDispatch.callDestroy(referent);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState());
                assertFalse(referent.currentlyDestroying);
                assertEquals(Integer.MIN_VALUE, referent.refCount);
            } finally {
                GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
            }
        }
    }

    @Test
    void blessedDestroyCanReenterDispatchWithoutRepeatingTheLifecycle() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "Issue1649::ReentrantDestroy";
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = Integer.MIN_VALUE;
            AtomicReference<RuntimeBase.PerlLifecycleState> observed = new AtomicReference<>();
            installDestroy(className, (args, context) -> {
                observed.set(referent.perlLifecycleState());
                DestroyDispatch.callDestroy(referent);
                return new RuntimeScalar().getList();
            });

            try {
                DestroyDispatch.callDestroy(referent);
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYING, observed.get());
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED, referent.perlLifecycleState());
            } finally {
                GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
            }
        }
    }

    @Test
    void rescuedBlessedReferentMovesFromResurrectedToDestroyed() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "Issue1649::ResurrectedDestroy";
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = Integer.MIN_VALUE;
            installDestroy(className, (args, context) -> {
                referent.refCount++;
                DestroyDispatch.markDestroyTargetRescued();
                return new RuntimeScalar().getList();
            });

            try {
                DestroyDispatch.callDestroy(referent);
                assertEquals(RuntimeBase.PerlLifecycleState.RESURRECTED,
                        referent.perlLifecycleState());
                assertEquals(1, referent.refCount);

                DestroyDispatch.processRescuedObjects();

                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        referent.perlLifecycleState());
            } finally {
                GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
            }
        }
    }

    private static void installDestroy(String className, PerlSubroutine body) {
        RuntimeCode code = new RuntimeCode(body, null);
        int separator = className.lastIndexOf("::");
        code.packageName = className.substring(0, separator);
        code.subName = className.substring(separator + 2);
        GlobalVariable.getGlobalCodeRef(className + "::DESTROY").set(new RuntimeScalar(code));
    }
}

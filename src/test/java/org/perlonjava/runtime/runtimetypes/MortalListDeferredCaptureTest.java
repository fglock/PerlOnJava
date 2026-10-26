package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalListDeferredCaptureTest {
    @Test
    void unreachableHttpBodyCaptureReleasesAtItsScopeBoundaryWithoutWeakRefs() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "HTTP::Body::OctetStream";
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = 1;
            installDestroy(className);

            RuntimeScalar pad = referenceTo(referent);
            pad.refCountOwned = true;
            pad.retainClosureCapture();

            MortalList.flush();

            MortalList.pushMark();
            RuntimeScalar.scopeExitCleanup(pad);
            MortalList.popAndFlush();

            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    referent.perlLifecycleState());
            GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
        }
    }

    @Test
    void unreachableBlessedCaptureWithWeakObserverReleasesAtItsScopeBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "DeferredCaptureBoundary::Tracked";
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = 1;
            installDestroy(className);

            RuntimeScalar pad = referenceTo(referent);
            pad.refCountOwned = true;
            pad.retainClosureCapture();

            RuntimeScalar weak = referenceTo(referent);
            WeakRefRegistry.weaken(weak);
            assertTrue(WeakRefRegistry.isweak(weak), "the observer starts weakened");
            MortalList.flush();

            MortalList.pushMark();
            RuntimeScalar.scopeExitCleanup(pad);
            MortalList.popAndFlush();

            assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                    referent.perlLifecycleState());
            assertEquals(RuntimeScalarType.UNDEF, weak.type,
                    "the weak observer clears before the next Perl operation");
            GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
        }
    }

    @Test
    void temporaryOnlyRootDefersDestroyUntilTheNextStatementBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "Issue1794::RequestTransaction";
            AtomicInteger destroyCalls = new AtomicInteger();
            RuntimeHash transaction = new RuntimeHash();
            transaction.blessId = NameNormalizer.getBlessId(className);
            transaction.refCount = 1;
            installDestroy(className, (args, context) -> {
                destroyCalls.incrementAndGet();
                return new RuntimeScalar().getList();
            });

            RuntimeScalar weak = referenceTo(transaction);
            WeakRefRegistry.weaken(weak);
            assertFalse(MortalList.isReachableFromPersistentRootCached(transaction),
                    "the weak observer is not a persistent owner");
            RuntimeHash unrelatedRoot = new RuntimeHash();
            MortalList.pushTemporaryRoot(unrelatedRoot);
            try {
                MortalList.pushTemporaryRoot(transaction);
                try {
                    MortalList.deferDecrement(transaction);
                    MortalList.flush();

                    assertEquals(0, destroyCalls.get(),
                            "an active temporary root delays destruction during nested cleanup");
                    assertEquals(1, transaction.refCount,
                            "the referent stays available until its temporary root is removed");
                    assertEquals(RuntimeScalarType.HASHREFERENCE, weak.type);
                } finally {
                    MortalList.popTemporaryRoot(transaction);
                }

                MortalList.flushAboveMark();

                assertEquals(1, destroyCalls.get(),
                        "the temporary-only owner is rechecked despite unrelated temporary roots");
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        transaction.perlLifecycleState());
                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "the weak observer clears when the request transaction is destroyed");
            } finally {
                MortalList.popTemporaryRoot(unrelatedRoot);
                GlobalVariable.removeGlobalCodeRefForStashDelete(className + "::DESTROY");
            }
        }
    }

    @Test
    void targetedWeakSweepPreservesReferentThatGainsALiveRootBeforeBoundary() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 1;
            RuntimeScalar weak = referenceTo(referent);
            WeakRefRegistry.weaken(weak);
            RuntimeScalar lexicalOwner = referenceTo(referent);
            RuntimeHash unrelatedTemporary = new RuntimeHash();

            MyVarCleanupStack.register(lexicalOwner);
            MyVarCleanupStack.snapshotStackToLiveCounts();
            MortalList.invalidateAllRootSnapshots();
            try {
                MortalList.requestTargetedWeakSweep(referent);
                MortalList.pushTemporaryRoot(unrelatedTemporary);
                try {
                    MortalList.flushAboveMark();

                    assertTrue(MortalList.isReachableFromPersistentRootCached(referent),
                            "the lexical owner remains a current Perl root");
                    assertEquals(RuntimeScalarType.HASHREFERENCE, weak.type,
                            "an unrelated temporary root must not cause a live referent to be swept");
                    assertFalse(PerlRuntime.current().lifecycleState
                                    .targetedWeakSweepReferents.contains(referent),
                            "a persistent root should not leave the candidate queued for every later boundary");
                } finally {
                    MortalList.popTemporaryRoot(unrelatedTemporary);
                }
                MyVarCleanupStack.unregister(lexicalOwner);
                MortalList.flushAboveMark();
            } finally {
                WeakRefRegistry.unweaken(weak);
                MortalList.invalidateAllRootSnapshots();
            }
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }

    private static void installDestroy(String className) {
        installDestroy(className, (args, context) -> new RuntimeScalar().getList());
    }

    private static void installDestroy(String className, PerlSubroutine body) {
        RuntimeCode code = new RuntimeCode(body, null);
        int separator = className.lastIndexOf("::");
        code.packageName = className.substring(0, separator);
        code.subName = "DESTROY";
        GlobalVariable.getGlobalCodeRef(className + "::DESTROY").set(new RuntimeScalar(code));
    }
}

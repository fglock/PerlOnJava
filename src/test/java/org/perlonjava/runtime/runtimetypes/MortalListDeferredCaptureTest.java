package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class MortalListDeferredCaptureTest {
    @Test
    void forwardedRuntimeListUsesOneTemporaryRootUntilItsSingleRelease() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeList result = new RuntimeList();

            MortalList.pushTemporaryRoot(result);
            MortalList.pushTemporaryRoot(result);
            assertEquals(1, MortalList.snapshotTemporaryRoots().size(),
                    "forwarding the same result must not duplicate its temporary root");

            MortalList.popTemporaryRoot(result);
            assertFalse(MortalList.hasTemporaryRoots(),
                    "one release clears the single forwarded result root");
        }
    }

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
    void unreachableBlessedCaptureUsesExplicitScalarSlotAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "DeferredCaptureBoundary::ExplicitSlot";
            RuntimeHash referent = new RuntimeHash();
            referent.blessId = NameNormalizer.getBlessId(className);
            referent.refCount = 1;
            installDestroy(className);

            RuntimeScalar pad = referenceTo(referent);
            pad.setRefCountOwned(true);
            pad.refCountOwned = false;
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
    void closureCaptureDoesNotDoubleCountExplicitScalarOwnerAfterLegacyFlagRetires() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash referent = new RuntimeHash();
            referent.refCount = 1;
            RuntimeScalar pad = referenceTo(referent);
            pad.setRefCountOwned(true);
            pad.refCountOwned = false;

            pad.retainClosureCapture();

            assertEquals(1, referent.refCount,
                    "the explicit scalar slot already accounts for the referent");
            assertEquals(0, pad.captureRefCountOwned(),
                    "capture metadata must not add a second legacy bridge owner");
            pad.releaseClosureCapture();
        }
    }

    private static RuntimeScalar referenceTo(RuntimeBase referent) {
        RuntimeScalar scalar = new RuntimeScalar();
        scalar.type = RuntimeScalarType.HASHREFERENCE;
        scalar.value = referent;
        return scalar;
    }

    private static void installDestroy(String className) {
        RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeScalar().getList(), null);
        int separator = className.lastIndexOf("::");
        code.packageName = className.substring(0, separator);
        code.subName = "DESTROY";
        GlobalVariable.getGlobalCodeRef(className + "::DESTROY").set(new RuntimeScalar(code));
    }
}

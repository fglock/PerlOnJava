package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class EscapedScalarCellFinalOwnerReleaseTest {
    @Test
    void deferredCellCleanupRunsWhenItsLastOwnerLeaves() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String className = "EscapedScalarCellFinalOwnerReleaseTest::Target";
            String destroyFqn = className + "::DESTROY";
            RuntimeHash target = new RuntimeHash();
            target.blessId = NameNormalizer.getBlessId(className);
            target.refCount = 1;

            AtomicInteger destroyCalls = new AtomicInteger();
            RuntimeCode destroy = new RuntimeCode((args, context) -> {
                destroyCalls.incrementAndGet();
                return new RuntimeList();
            }, null);
            destroy.packageName = "EscapedScalarCellFinalOwnerReleaseTest";
            destroy.subName = "DESTROY";
            GlobalVariable.getGlobalCodeRef(destroyFqn).set(new RuntimeScalar(destroy));
            DestroyDispatch.registerIfDestroyable(target, target.blessId);

            RuntimeScalar cell = new RuntimeScalar();
            cell.type = RuntimeScalarType.HASHREFERENCE;
            cell.value = target;
            cell.refCountOwned = true;
            target.activateOwnerTracking();
            target.recordActiveOwner(cell);

            RuntimeScalar weak = new RuntimeScalar();
            weak.type = RuntimeScalarType.HASHREFERENCE;
            weak.value = target;
            WeakRefRegistry.weaken(weak);

            // The wrapper's DESTROY cleanup sees its temporary self owner and
            // must keep the escaped scalar cell's current value until that
            // owner is released.
            cell.referencedByScalarReference = true;
            PerlOwnerSlot scalarCellOwner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SCALAR_CELL);
            scalarCellOwner.acquireNativeOwner(cell);
            try {
                RuntimeScalar.scopeExitCleanup(cell);
                assertEquals(0, destroyCalls.get());
                assertTrue(WeakRefRegistry.isweak(weak),
                        "the observer remains weak while the scalar cell is owned");

                scalarCellOwner.release();
                MortalList.flush();

                assertEquals(1, destroyCalls.get(), "the nested object is destroyed once");
                assertEquals(RuntimeScalarType.UNDEF, weak.type,
                        "the nested object's weak observer clears with the final cell owner");
            } finally {
                if (scalarCellOwner.isActive()) scalarCellOwner.release();
                GlobalVariable.globalCodeRefs.remove(destroyFqn);
                DestroyDispatch.unregisterDestroyable(target);
            }
        }
    }
}

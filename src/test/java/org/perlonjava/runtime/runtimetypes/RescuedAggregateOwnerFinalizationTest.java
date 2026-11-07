package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("unit")
class RescuedAggregateOwnerFinalizationTest {
    @Test
    void deferredRescueFinalizationReleasesNestedOwnersThroughSlots() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String parentClass = "Issue1649::RescuedOwnerParent";
            String childClass = "Issue1649::RescuedOwnerChild";
            RuntimeHash parent = new RuntimeHash();
            RuntimeHash child = new RuntimeHash();
            parent.blessId = NameNormalizer.getBlessId(parentClass);
            child.blessId = NameNormalizer.getBlessId(childClass);
            child.refCount = 0;

            RuntimeScalar childSlot = new RuntimeScalar();
            parent.elements.put("child", childSlot);
            childSlot.set(child.createAnonymousReference());

            RuntimeScalar weakChild = new RuntimeScalar();
            weakChild.type = RuntimeScalarType.HASHREFERENCE;
            weakChild.value = child;
            WeakRefRegistry.weaken(weakChild);

            AtomicInteger childDestroyCount = new AtomicInteger();
            installDestroy(parentClass, (args, context) -> {
                DestroyDispatch.markDestroyTargetRescued();
                return new RuntimeScalar().getList();
            });
            installDestroy(childClass, (args, context) -> {
                childDestroyCount.incrementAndGet();
                return new RuntimeScalar().getList();
            });

            parent.refCount = Integer.MIN_VALUE;
            try {
                DestroyDispatch.callDestroy(parent);
                assertEquals(RuntimeBase.PerlLifecycleState.RESURRECTED,
                        parent.perlLifecycleState());

                // This runtime has no deferred captures: shutdown must still
                // process rescued objects instead of returning early.
                MortalList.flushDeferredCaptures();

                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        parent.perlLifecycleState());
                assertEquals(RuntimeBase.PerlLifecycleState.DESTROYED,
                        child.perlLifecycleState());
                assertEquals(1, childDestroyCount.get(),
                        "releasing the rescued aggregate's slot runs the nested destructor once");
                assertNull(weakChild.value,
                        "the nested weak reference clears when its actual owner leaves");
                assertFalse(WeakRefRegistry.isweak(weakChild));
            } finally {
                WeakRefRegistry.unweaken(weakChild);
                GlobalVariable.removeGlobalCodeRefForStashDelete(parentClass + "::DESTROY");
                GlobalVariable.removeGlobalCodeRefForStashDelete(childClass + "::DESTROY");
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

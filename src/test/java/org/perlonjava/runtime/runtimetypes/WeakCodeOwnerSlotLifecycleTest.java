package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class WeakCodeOwnerSlotLifecycleTest {
    @Test
    void liveLexicalCodeOwnerPreservesWeakObserver() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.setTrackedRefCount(0);
            RuntimeScalar strongOwner = new RuntimeScalar();
            RuntimeScalar weakOwner = new RuntimeScalar();
            int mark = MyVarCleanupStack.pushMark();
            MyVarCleanupStack.register(strongOwner);
            MyVarCleanupStack.register(weakOwner);
            strongOwner.set(new RuntimeScalar(code));
            weakOwner.set(new RuntimeScalar(code));

            try {
                WeakRefRegistry.weaken(weakOwner);

                assertTrue(WeakRefRegistry.isweak(weakOwner));
                assertEquals(RuntimeScalarType.CODE, weakOwner.type);

                strongOwner.set(new RuntimeScalar());
                assertEquals(RuntimeScalarType.UNDEF, weakOwner.type,
                        "releasing the final lexical CODE owner clears its weak observer");
            } finally {
                MyVarCleanupStack.unregister(weakOwner);
                MyVarCleanupStack.unregister(strongOwner);
                MyVarCleanupStack.popMark(mark);
            }
        }
    }
}

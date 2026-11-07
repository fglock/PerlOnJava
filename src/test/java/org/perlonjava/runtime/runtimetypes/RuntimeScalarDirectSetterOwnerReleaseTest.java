package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarDirectSetterOwnerReleaseTest {
    @Test
    void assigningJavaNullReleasesThePriorScalarCellOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            List<Consumer<RuntimeScalar>> setters = List.of(
                    cell -> cell.set("replacement"),
                    cell -> cell.set((String) null),
                    cell -> cell.set((RuntimeScalar) null),
                    cell -> cell.set(7),
                    cell -> cell.set(7L),
                    cell -> cell.set(BigInteger.valueOf(7)),
                    cell -> cell.set(true));
            for (Consumer<RuntimeScalar> setter : setters) {
                assertSetterReleasesCurrentOwner(setter);
            }
        }
    }

    private static void assertSetterReleasesCurrentOwner(Consumer<RuntimeScalar> setter) {
        RuntimeHash referent = new RuntimeHash();
        referent.activateOwnerTracking();

        RuntimeScalar cell = new RuntimeScalar();
        cell.set(referent.createAnonymousReference());
        RuntimeScalar weakObserver = referent.createAnonymousReference();
        WeakRefRegistry.weaken(weakObserver);
        assertTrue(cell.refCountOwned);
        assertEquals(1, referent.refCount);

        setter.accept(cell);
        MortalList.flush();

        assertFalse(cell.refCountOwned,
                "primitive/string setter retires the prior scalar-content owner token");
        assertFalse(WeakRefRegistry.isweak(weakObserver),
                "the final referent owner clears its weak observer");
        assertEquals(Integer.MIN_VALUE, referent.refCount,
                "the referent is finalized after its scalar cell is overwritten");
    }
}

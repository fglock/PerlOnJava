package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeScalarJavaNullAssignmentOwnerTest {
    @Test
    void assigningJavaNullReleasesThePriorScalarCellOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash original = new RuntimeHash();
            original.activateOwnerTracking();

            RuntimeScalar cell = new RuntimeScalar();
            cell.set(original.createAnonymousReference());
            RuntimeScalar weakObserver = original.createAnonymousReference();
            WeakRefRegistry.weaken(weakObserver);

            cell.set((RuntimeScalar) null);
            MortalList.flush();

            assertFalse(cell.refCountOwned,
                    "undefining the cell retires its scalar-content owner token");
            assertFalse(WeakRefRegistry.isweak(weakObserver),
                    "the final referent owner clears its weak observer");
            assertEquals(Integer.MIN_VALUE, original.refCount,
                    "the referent is finalized after its scalar cell is undefined");

            RuntimeHash replacement = new RuntimeHash();
            replacement.activateOwnerTracking();
            RuntimeScalar replacementReference = replacement.createAnonymousReference();
            cell.set(replacementReference);

            assertSame(replacement, cell.value);
            assertTrue(cell.refCountOwned);
            assertEquals(1, replacement.refCount);

            cell.undefine();
            MortalList.flush();
            assertEquals(Integer.MIN_VALUE, replacement.refCount);
        }
    }
}

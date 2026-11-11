package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RescuedWeakRefsEmptyRegistryFastPathTest {
    private static final class CountingHashValues extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return Collections.emptyList();
        }
    }

    @Test
    void emptyWeakRegistrySkipsNestedRescuedHashWalk() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash rescued = new RuntimeHash();
            CountingHashValues nestedValues = new CountingHashValues();
            rescued.elements = nestedValues;
            runtime.lifecycleState.rescuedObjects.add(rescued);

            assertFalse(WeakRefRegistry.hasAnyWeakRefs());
            DestroyDispatch.clearRescuedWeakRefsAtEndBoundary();

            assertEquals(0, nestedValues.valuesCalls,
                    "the END boundary must skip rescue-graph traversal without observers");
            assertTrue(DestroyDispatch.isRescued(rescued),
                    "the optimization must preserve the rescued object through END");
        }
    }
}

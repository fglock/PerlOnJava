package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class TemporaryRootReleaseDoesNotTraverseTest {
    private static final class CountingHashValues extends AbstractMap<String, RuntimeScalar> {
        private int valuesCalls;

        @Override
        public Set<Map.Entry<String, RuntimeScalar>> entrySet() {
            return Collections.emptySet();
        }

        @Override
        public Collection<RuntimeScalar> values() {
            valuesCalls++;
            return List.of();
        }
    }

    @Test
    void cleanupDoesNotTraverseNestedTemporaryValues() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash nested = new RuntimeHash();
            CountingHashValues nestedValues = new CountingHashValues();
            nested.elements = nestedValues;
            RuntimeList temporaryRoot = new RuntimeList();
            temporaryRoot.elements.add(nested.createReference());
            MortalList.pushTemporaryRoot(temporaryRoot);
            try {
                assertEquals(1, temporaryRoot.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_TEMPORARY_ROOT));
                MortalList.popTemporaryRoot(temporaryRoot);

                assertEquals(0, nestedValues.valuesCalls,
                        "temporary-root leases record their owner directly without walking nested graphs");
            } finally {
                if (temporaryRoot.ownerSlotCount(
                        PerlOwnerSlot.Kind.EXPRESSION_TEMPORARY_ROOT) != 0) {
                    MortalList.popTemporaryRoot(temporaryRoot);
                }
            }
        }
    }
}

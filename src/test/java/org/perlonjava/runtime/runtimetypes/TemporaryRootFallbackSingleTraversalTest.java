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
class TemporaryRootFallbackSingleTraversalTest {
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
    void unblessedFallbackDoesNotRepeatTheTemporaryRootWalkAfterAMiss() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeHash nested = new RuntimeHash();
            CountingHashValues nestedValues = new CountingHashValues();
            nested.elements = nestedValues;
            RuntimeList temporaryRoot = new RuntimeList();
            temporaryRoot.elements.add(nested.createReference());
            RuntimeHash target = new RuntimeHash();

            MortalList.pushTemporaryRoot(temporaryRoot);
            try {
                MortalList.isUnblessedAggregateReachableDuringDrain(target);

                assertEquals(1, nestedValues.valuesCalls,
                        "the temporary-root miss should fall through to persistent roots directly");
            } finally {
                MortalList.popTemporaryRoot(temporaryRoot);
            }
        }
    }
}

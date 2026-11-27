package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class WatcherCleanupDoesNotTraverseLiveAggregateTest {
    private static final class CountingStashMap extends AbstractMap<String, RuntimeScalar> {
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
    void scopeCleanupDoesNotTraverseReferencedStashWhenWatchersExistElsewhere() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            AtomicInteger callbackCalls = new AtomicInteger();
            RuntimeCode callback = new RuntimeCode((args, context) -> {
                callbackCalls.incrementAndGet();
                return new RuntimeList();
            }, null);

            RuntimeStash stash = new RuntimeStash("WatcherCleanupDoesNotTraverseLiveAggregateTest::");
            CountingStashMap stashView = new CountingStashMap();
            stash.elements = stashView;
            ((RuntimeHash) stash).elements = stashView;

            RuntimeArray container = new RuntimeArray();
            RuntimeScalar stashReference = stash.createReference();
            stashReference.addDestroyedWatcher(1, new RuntimeScalar(callback));
            container.addClonedElement(stashReference);
            MortalList.scopeExitCleanupArray(container);

            assertEquals(0, stashView.valuesCalls,
                    "destroying a reference container must not traverse a still-live stash");
            assertEquals(1, callbackCalls.get(),
                    "the destroyed watcher on the released direct slot still runs");
        }
    }
}

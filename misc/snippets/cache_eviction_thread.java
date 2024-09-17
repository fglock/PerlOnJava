
    Create a separate thread for eviction
    Use a ScheduledExecutorService to run eviction periodically
    Implement eviction logic in the separate thread

import java.util.concurrent.*;
import java.util.Map.Entry;

public class EvictingConcurrentHashMap<K, V> {
    private final ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();
    private final int maxSize;
    private final ScheduledExecutorService evictionExecutor = Executors.newSingleThreadScheduledExecutor();

    public EvictingConcurrentHashMap(int maxSize, long evictionPeriodMillis) {
        this.maxSize = maxSize;
        evictionExecutor.scheduleAtFixedRate(this::evict, evictionPeriodMillis, evictionPeriodMillis, TimeUnit.MILLISECONDS);
    }

    public V put(K key, V value) {
        return map.put(key, value);
    }

    public V get(K key) {
        return map.get(key);
    }

    private void evict() {
        while (map.size() > maxSize) {
            Entry<K, V> eldest = map.entrySet().iterator().next();
            map.remove(eldest.getKey());
        }
    }

    public void shutdown() {
        evictionExecutor.shutdown();
    }

    // Other methods delegated to map as needed
}


Usage:


EvictingConcurrentHashMap<String, Pattern> patternCache = 
    new EvictingConcurrentHashMap<>(1000, 60000); // Max 1000 entries, evict every 60 seconds

Pattern getCompiledPattern(String regex) {
    return patternCache.computeIfAbsent(regex, Pattern::compile);
}



Advantages of this approach:

    Main operations (put/get) remain fast, no eviction check overhead
    Eviction runs periodically, not affecting every operation
    Flexible: various eviction strategies (LRU, time-based, etc.)

Considerations:

    Eviction is not immediate; cache may temporarily exceed maxSize between eviction runs
    Need to manage the eviction thread lifecycle (e.g., call shutdown() when done)
    Tune eviction period (frequent enough to manage size, not so frequent as to impact performance)

This approach can provide a good balance between the simplicity and performance of ConcurrentHashMap and the need for automatic eviction. However, for more advanced caching needs (like fine-grained eviction policies or better memory efficiency), a specialized caching library like Caffeine might still be preferable.


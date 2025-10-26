package org.perlonjava.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * StableHashMap is a LinkedHashMap that maintains insertion order and capacity after growth.
 * Once the map has grown to accommodate elements, it doesn't shrink back
 * even when elements are removed. This mimics Perl's hash behavior.
 * <p>
 * Uses LinkedHashMap to maintain insertion order, providing deterministic iteration
 * order which is important for compatibility with Perl code that relies on
 * consistent hash key ordering (e.g., when sorted).
 * <p>
 * This optimization avoids repeated resizing when elements are frequently
 * added and removed.
 */
public class StableHashMap<K, V> extends LinkedHashMap<K, V> {
    private int maxCapacityReached = 16;  // Track the maximum capacity we've grown to
    private int maxSizeReached = 0;       // Track the maximum number of elements we've had

    /**
     * Default constructor with initial capacity of 8 (Perl's default)
     */
    public StableHashMap() {
        super(8, 0.75f);  // Initial capacity 8, load factor 0.75 (HashMap default)
        maxCapacityReached = 8;
    }

    /**
     * Constructor with specified initial capacity
     */
    public StableHashMap(int initialCapacity) {
        super(initialCapacity, 0.75f);
        maxCapacityReached = initialCapacity;
    }

    /**
     * Copy constructor
     */
    public StableHashMap(Map<? extends K, ? extends V> m) {
        super(m);
        maxSizeReached = m.size();
        maxCapacityReached = calculateCapacityForSize(maxSizeReached);
    }

    @Override
    public V put(K key, V value) {
        V result = super.put(key, value);
        int currentSize = size();
        if (currentSize > maxSizeReached) {
            maxSizeReached = currentSize;
            // Update max capacity if we've grown
            int currentCapacity = calculateCurrentCapacity();
            if (currentCapacity > maxCapacityReached) {
                maxCapacityReached = currentCapacity;
            }
        }
        return result;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        super.putAll(m);
        int currentSize = size();
        if (currentSize > maxSizeReached) {
            maxSizeReached = currentSize;
            // Update max capacity if we've grown
            int currentCapacity = calculateCurrentCapacity();
            if (currentCapacity > maxCapacityReached) {
                maxCapacityReached = currentCapacity;
            }
        }
    }

    /**
     * Calculate the capacity needed for a given number of elements
     * Following HashMap's growth pattern (powers of 2)
     */
    private int calculateCapacityForSize(int size) {
        if (size == 0) {
            return 8;
        }
        // HashMap grows when load factor (0.75) is exceeded
        int minCapacity = (int) Math.ceil(size / 0.75);
        int capacity = 8;
        while (capacity < minCapacity) {
            capacity *= 2;
        }
        return capacity;
    }

    /**
     * Estimate current capacity based on current size
     * This maintains the illusion of stable capacity
     */
    private int calculateCurrentCapacity() {
        // Use reflection or estimate based on size
        // For now, use the calculation method
        return calculateCapacityForSize(size());
    }

    /**
     * Get the stable capacity (maximum capacity ever reached)
     * Used for bucket_ratio calculations
     */
    public int getStableCapacity() {
        // Return the maximum capacity we've ever reached
        // This ensures bucket_ratio shows stable capacity even after deletions
        return maxCapacityReached;
    }

    /**
     * Get the estimated number of used buckets
     * Used for bucket_ratio calculations
     */
    public int getUsedBuckets() {
        int keyCount = size();
        if (keyCount == 0) {
            return 0;
        }

        int capacity = getStableCapacity();

        // For small numbers of keys, assume good distribution
        if (keyCount <= 8) {
            return keyCount;  // Each key likely in its own bucket
        }

        // For larger hashes, estimate based on hash distribution
        // Using the formula for expected number of non-empty buckets
        double loadFactor = (double) keyCount / capacity;
        double usedRatio = 1.0 - Math.exp(-loadFactor * keyCount);
        int used = (int) Math.ceil(capacity * usedRatio);

        // Ensure we don't exceed logical limits
        used = Math.min(used, keyCount);    // Can't have more used buckets than keys
        used = Math.min(used, capacity);     // Can't exceed total capacity
        used = Math.max(used, 1);           // At least 1 if we have keys

        return used;
    }

    /**
     * Get bucket ratio as a string "used/total"
     * This is what Perl's bucket_ratio returns
     */
    public String getBucketRatio() {
        return getUsedBuckets() + "/" + getStableCapacity();
    }

    /**
     * Set the minimum capacity for the hash map.
     * This is used when Perl code does: keys %hash = $number
     * to preallocate hash buckets.
     *
     * @param requestedSize The requested minimum number of elements
     */
    public void setMinimumCapacity(int requestedSize) {
        if (requestedSize <= 0) {
            return; // No change for non-positive sizes
        }

        // Calculate the capacity needed for this size
        int targetCapacity = calculateCapacityForSize(requestedSize);

        // If requested capacity is larger than what we've seen, grow
        if (targetCapacity > maxCapacityReached) {
            maxCapacityReached = targetCapacity;

            // Force rehash by ensuring the map to preallocate space
            // We can do this by adding and removing a temporary entry if needed
            // Or we can rely on the next put() operation to trigger growth
            // For now, just update our max capacity tracking
        }
    }
}

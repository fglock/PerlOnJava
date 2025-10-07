package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * HashUtil - provides Perl's Hash::Util functionality
 * 
 * This class implements hash-related utility functions, particularly
 * bucket_ratio() which is needed for hash statistics in tests.
 */
public class HashUtil extends PerlModuleBase {
    
    /**
     * Constructor initializes the module.
     */
    public HashUtil() {
        super("Hash::Util", false);  // false because loaded via XSLoader
    }
    
    /**
     * Static initializer to set up the module and register methods.
     */
    public static void initialize() {
        HashUtil hashUtil = new HashUtil();
        
        try {
            // Register the bucket_ratio method
            hashUtil.registerMethod("bucket_ratio", "bucket_ratio", "\\%");
            
            // Register other Hash::Util methods (placeholders for now)
            hashUtil.registerMethod("lock_keys", "lock_keys", "\\%@");
            hashUtil.registerMethod("unlock_keys", "unlock_keys", "\\%@");
            hashUtil.registerMethod("lock_hash", "lock_hash", "\\%");
            hashUtil.registerMethod("unlock_hash", "unlock_hash", "\\%");
            hashUtil.registerMethod("hash_seed", "hash_seed", null);
            
            // Additional methods not yet implemented:
            // lock_value, unlock_value, lock_keys_plus, hash_value, 
            // bucket_info, bucket_stats, bucket_array
        } catch (NoSuchMethodException e) {
            // This should not happen if all methods are properly implemented
            System.err.println("Warning: Missing HashUtil method: " + e.getMessage());
        }
    }
    
    /**
     * Calculate the bucket ratio for a hash
     * Returns "used/total" where:
     * - used: number of buckets with at least one entry
     * - total: total number of buckets allocated
     */
    public static RuntimeList bucket_ratio(RuntimeArray args, int ctx) {
        if (args.size() == 0) {
            throw new IllegalArgumentException("bucket_ratio requires a hash argument");
        }
        
        RuntimeScalar hashRef = args.get(0);
        RuntimeHash hash;
        
        // Handle both direct hash and hash reference
        if (hashRef.type == RuntimeScalarType.HASHREFERENCE) {
            hash = (RuntimeHash) hashRef.value;
        } else {
            // Try to convert to hash
            hash = hashRef.hashDeref();
        }
        
        // Get bucket statistics
        String ratio = calculateBucketRatio(hash);
        return new RuntimeScalar(ratio).getList();
    }
    
    /**
     * Calculate bucket statistics for a RuntimeHash
     * 
     * Since RuntimeHash uses StableHashMap internally, we can get
     * the bucket ratio directly from it.
     */
    private static String calculateBucketRatio(RuntimeHash hash) {
        Map<String, RuntimeScalar> elements = hash.elements;
        
        // Check if it's a StableHashMap (which it should be for PLAIN_HASH)
        if (elements instanceof org.perlonjava.runtime.StableHashMap) {
            org.perlonjava.runtime.StableHashMap<String, RuntimeScalar> stableMap = 
                (org.perlonjava.runtime.StableHashMap<String, RuntimeScalar>) elements;
            return stableMap.getBucketRatio();
        }
        
        // Fallback for other map types (shouldn't happen normally)
        int keyCount = elements.size();
        
        if (keyCount == 0) {
            // Empty hash: 0 used, but still has initial capacity
            return "0/8";
        }
        
        // Try to get actual HashMap capacity using reflection
        int capacity = getHashMapCapacity(elements);
        int used = estimateUsedBuckets(elements, capacity);
        
        return used + "/" + capacity;
    }
    
    /**
     * Get the actual capacity of a HashMap using reflection
     * Falls back to estimation if reflection fails
     */
    private static int getHashMapCapacity(Map<String, RuntimeScalar> map) {
        if (!(map instanceof HashMap)) {
            // Fallback for non-HashMap implementations
            return estimateCapacity(map.size());
        }
        
        try {
            // Try to access the internal table array to get actual capacity
            Field tableField = HashMap.class.getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] table = (Object[]) tableField.get(map);
            
            if (table != null) {
                return table.length;
            } else {
                // HashMap not initialized yet, use default
                return 16;  // HashMap default initial capacity
            }
        } catch (Exception e) {
            // Reflection failed, fall back to estimation
            return estimateCapacity(map.size());
        }
    }
    
    /**
     * Estimate capacity based on key count
     * This mimics HashMap's growth pattern (powers of 2)
     */
    private static int estimateCapacity(int keyCount) {
        if (keyCount == 0) {
            return 8;  // Initial capacity for Perl hashes
        }
        
        // HashMap grows when load factor (0.75) is exceeded
        // Find next power of 2 >= keyCount / 0.75
        int minCapacity = (int) Math.ceil(keyCount / 0.75);
        int capacity = 8;
        while (capacity < minCapacity) {
            capacity *= 2;
        }
        return capacity;
    }
    
    /**
     * Estimate the number of used buckets
     * A bucket is "used" if it contains at least one entry
     */
    private static int estimateUsedBuckets(Map<String, RuntimeScalar> map, int capacity) {
        int keyCount = map.size();
        
        if (keyCount == 0) {
            return 0;
        }
        
        // For small numbers of keys, assume good distribution
        if (keyCount <= 8) {
            return keyCount;  // Each key likely in its own bucket
        }
        
        // For larger hashes, estimate based on hash distribution
        // This uses the formula for expected number of non-empty buckets
        // in a hash table with uniform distribution
        double loadFactor = (double) keyCount / capacity;
        double usedRatio = 1.0 - Math.exp(-loadFactor * keyCount);
        int used = (int) Math.ceil(capacity * usedRatio);
        
        // Ensure we don't exceed logical limits
        used = Math.min(used, keyCount);    // Can't have more used buckets than keys
        used = Math.min(used, capacity);     // Can't exceed total capacity
        used = Math.max(used, 1);           // At least 1 if we have keys
        
        return used;
    }
    
    // Placeholder implementations for other Hash::Util functions
    
    public static RuntimeList lock_keys(RuntimeArray args, int ctx) {
        // TODO: Implement hash key locking
        if (args.size() > 0) {
            return args.get(0).getList();  // Return the hash reference
        }
        return RuntimeScalarCache.scalarUndef.getList();
    }
    
    public static RuntimeList unlock_keys(RuntimeArray args, int ctx) {
        // TODO: Implement hash key unlocking
        if (args.size() > 0) {
            return args.get(0).getList();  // Return the hash reference
        }
        return RuntimeScalarCache.scalarUndef.getList();
    }
    
    public static RuntimeList lock_hash(RuntimeArray args, int ctx) {
        // TODO: Implement full hash locking
        if (args.size() > 0) {
            return args.get(0).getList();  // Return the hash reference
        }
        return RuntimeScalarCache.scalarUndef.getList();
    }
    
    public static RuntimeList unlock_hash(RuntimeArray args, int ctx) {
        // TODO: Implement full hash unlocking
        if (args.size() > 0) {
            return args.get(0).getList();  // Return the hash reference
        }
        return RuntimeScalarCache.scalarUndef.getList();
    }
    
    public static RuntimeList hash_seed(RuntimeArray args, int ctx) {
        // Return a constant seed value for now
        return new RuntimeScalar(0).getList();
    }
}

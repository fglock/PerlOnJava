package org.perlonjava.runtime;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * HashProxy is a pass-through proxy for hash operations that allows custom behavior
 * to be executed when entries are set. This is particularly useful for implementing
 * features like hash autovivification in Perl.
 *
 * <p>Unlike HashSpecialVariable which provides special behavior for Perl's %+, %-, and stash,
 * this class acts as a regular hash with interceptable operations.
 *
 * <p>Example usage for autovivification:
 * <pre>{@code
 * HashProxy autoVivifyHash = new HashProxy((key, value) -> {
 *     // Custom logic when a value is set
 *     // For example, ensure the value is properly initialized
 *     if (value == null || !value.getDefinedBoolean()) {
 *         // Initialize as an empty hash reference
 *         RuntimeHash newHash = new RuntimeHash();
 *         value = newHash.createReference();
 *     }
 * });
 * }</pre>
 */
public class HashProxy extends AbstractMap<String, RuntimeScalar> {

    // The underlying storage for hash entries
    private final Map<String, RuntimeScalar> storage = new HashMap<>();

    // Optional callback to be executed when an entry is set
    private BiConsumer<String, RuntimeScalar> onPutCallback;

    // Optional callback to be executed when an entry is accessed
    private BiConsumer<String, RuntimeScalar> onGetCallback;

    /**
     * Constructs an empty HashProxy with no callbacks.
     */
    public HashProxy() {
        this(null, null);
    }

    /**
     * Constructs a HashProxy with an optional callback for put operations.
     *
     * @param onPutCallback Callback to execute when an entry is set (can be null)
     */
    public HashProxy(BiConsumer<String, RuntimeScalar> onPutCallback) {
        this(onPutCallback, null);
    }

    /**
     * Constructs a HashProxy with optional callbacks for put and get operations.
     *
     * @param onPutCallback Callback to execute when an entry is set (can be null)
     * @param onGetCallback Callback to execute when an entry is accessed (can be null)
     */
    public HashProxy(BiConsumer<String, RuntimeScalar> onPutCallback,
                     BiConsumer<String, RuntimeScalar> onGetCallback) {
        this.onPutCallback = onPutCallback;
        this.onGetCallback = onGetCallback;
    }

    /**
     * Sets the callback to be executed when entries are set.
     *
     * @param callback The callback to execute on put operations
     */
    public void setOnPutCallback(BiConsumer<String, RuntimeScalar> callback) {
        this.onPutCallback = callback;
    }

    /**
     * Sets the callback to be executed when entries are accessed.
     *
     * @param callback The callback to execute on get operations
     */
    public void setOnGetCallback(BiConsumer<String, RuntimeScalar> callback) {
        this.onGetCallback = callback;
    }

    /**
     * Returns a set view of the mappings contained in this map.
     *
     * @return A set of map entries
     */
    @Override
    public Set<Entry<String, RuntimeScalar>> entrySet() {
        return storage.entrySet();
    }

    /**
     * Associates the specified value with the specified key in this map.
     * If a put callback is set, it will be executed after the value is stored.
     *
     * @param key   The key with which the specified value is to be associated
     * @param value The value to be associated with the specified key
     * @return The previous value associated with key, or null if there was no mapping
     */
    @Override
    public RuntimeScalar put(String key, RuntimeScalar value) {
        RuntimeScalar oldValue = storage.put(key, value);

        // Execute callback if set
        if (onPutCallback != null) {
            onPutCallback.accept(key, value);
        }

        return oldValue;
    }

    /**
     * Returns the value to which the specified key is mapped, or null if this map
     * contains no mapping for the key. If a get callback is set, it will be executed
     * before returning the value.
     *
     * @param key The key whose associated value is to be returned
     * @return The value to which the specified key is mapped, or null
     */
    @Override
    public RuntimeScalar get(Object key) {
        if (key instanceof String) {
            RuntimeScalar value = storage.get(key);

            // Execute callback if set
            if (onGetCallback != null && value != null) {
                onGetCallback.accept((String) key, value);
            }

            return value;
        }
        return null;
    }

    /**
     * Returns true if this map contains a mapping for the specified key.
     *
     * @param key The key whose presence in this map is to be tested
     * @return true if this map contains a mapping for the specified key
     */
    @Override
    public boolean containsKey(Object key) {
        return storage.containsKey(key);
    }

    /**
     * Removes the mapping for a key from this map if it is present.
     *
     * @param key The key whose mapping is to be removed from the map
     * @return The previous value associated with key, or null if there was no mapping
     */
    @Override
    public RuntimeScalar remove(Object key) {
        return storage.remove(key);
    }

    /**
     * Removes all of the mappings from this map.
     */
    @Override
    public void clear() {
        storage.clear();
    }

    /**
     * Returns the number of key-value mappings in this map.
     *
     * @return The number of key-value mappings in this map
     */
    @Override
    public int size() {
        return storage.size();
    }

    /**
     * Returns true if this map contains no key-value mappings.
     *
     * @return true if this map contains no key-value mappings
     */
    @Override
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    /**
     * Creates a copy of this HashProxy with the same storage contents
     * but without the callbacks.
     *
     * @return A new HashProxy containing the same entries
     */
    public HashProxy copy() {
        HashProxy copy = new HashProxy();
        copy.storage.putAll(this.storage);
        return copy;
    }

    /**
     * Creates a copy of this HashProxy with the same storage contents
     * and the same callbacks.
     *
     * @return A new HashProxy with the same entries and callbacks
     */
    public HashProxy deepCopy() {
        HashProxy copy = new HashProxy(this.onPutCallback, this.onGetCallback);
        copy.storage.putAll(this.storage);
        return copy;
    }
}
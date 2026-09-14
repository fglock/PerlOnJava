package org.perlonjava.runtime.runtimetypes;

import java.util.Arrays;

/**
 * Compiler-private storage for a proven native-word lexical array.
 *
 * <p>This is deliberately not a {@link RuntimeArray}. Before materialization
 * it has no Perl-visible element cells. A compiler must retain the ordinary
 * {@code RuntimeArray} local as the source of truth after calling
 * {@link #materialize()}, and must never use this carrier again. The current
 * compiler does not instantiate this class; it is phase-two infrastructure
 * for the private-native-array representation.</p>
 */
public final class PrivateNativeArrayCarrier {
    private long[] words;
    private boolean[] initialized;
    private int length;
    private RuntimeArray materialized;

    public PrivateNativeArrayCarrier() {
        this(8);
    }

    public PrivateNativeArrayCarrier(int initialCapacity) {
        if (initialCapacity < 0) throw new IllegalArgumentException("negative initial capacity");
        words = new long[initialCapacity];
        initialized = new boolean[initialCapacity];
    }

    /** Store a raw Perl unsigned-word bit pattern at a non-negative index. */
    public void setWord(int index, long value) {
        requireNative();
        if (index < 0) throw new IllegalArgumentException("negative private array index");
        ensureCapacity(index + 1);
        words[index] = value;
        initialized[index] = true;
        length = Math.max(length, index + 1);
    }

    /** Read an initialized raw word; unsupported uninitialized reads must materialize first. */
    public long wordAt(int index) {
        requireNative();
        if (index < 0 || index >= length || !initialized[index]) {
            throw new IllegalStateException("uninitialized private native array element");
        }
        return words[index];
    }

    public int length() {
        return materialized == null ? length : materialized.countElements();
    }

    public boolean isMaterialized() {
        return materialized != null;
    }

    /**
     * Create the ordinary array exactly once. Holes stay absent; negative raw
     * words use the same unsigned conversion as RuntimeArray's word store.
     */
    public RuntimeArray materialize() {
        if (materialized != null) return materialized;
        RuntimeArray array = new RuntimeArray(length);
        for (int index = 0; index < length; index++) {
            if (!initialized[index]) {
                array.elements.add(null);
            } else {
                array.setUnsignedWordElement(index, words[index]);
            }
        }
        materialized = array;
        words = null;
        initialized = null;
        return array;
    }

    /** Keep an already-resolved ordinary lexical authoritative. */
    public void retainOrdinary(RuntimeArray array) {
        if (materialized != null || length != 0) throw new IllegalStateException("carrier already used");
        materialized = array;
        words = null;
        initialized = null;
    }

    private void requireNative() {
        if (materialized != null) {
            throw new IllegalStateException("private native array has materialized");
        }
    }

    private void ensureCapacity(int required) {
        if (required <= words.length) return;
        int grown = Math.max(required, Math.max(8, words.length * 2));
        words = Arrays.copyOf(words, grown);
        initialized = Arrays.copyOf(initialized, grown);
    }
}

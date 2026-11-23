package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** A synchronized weak set whose keys retain object identity semantics. */
final class WeakIdentitySet<T> {
    private final Map<IdentityReference<T>, Boolean> entries = new HashMap<>();
    private final ReferenceQueue<T> cleared = new ReferenceQueue<>();
    private int registrations;

    synchronized void add(T value) {
        drainCleared();
        entries.put(new IdentityReference<>(value, cleared), Boolean.TRUE);
        if ((++registrations & 1023) == 0) drainCleared();
    }

    synchronized void remove(T value) {
        drainCleared();
        entries.remove(new IdentityReference<>(value, null));
    }

    synchronized ArrayList<T> snapshot() {
        drainCleared();
        ArrayList<T> values = new ArrayList<>(entries.size());
        var iterator = entries.keySet().iterator();
        while (iterator.hasNext()) {
            T value = iterator.next().get();
            if (value == null) iterator.remove();
            else values.add(value);
        }
        return values;
    }

    synchronized void clear() {
        entries.clear();
        while (cleared.poll() != null) {
            // Discard references queued for entries that were just cleared.
        }
        registrations = 0;
    }

    private void drainCleared() {
        IdentityReference<?> reference;
        while ((reference = (IdentityReference<?>) cleared.poll()) != null) {
            entries.remove(reference);
        }
    }

    private static final class IdentityReference<T> extends WeakReference<T> {
        private final int identityHash;

        IdentityReference(T value, ReferenceQueue<T> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityReference<?> reference)) return false;
            T value = get();
            return value != null && value == reference.get();
        }
    }
}

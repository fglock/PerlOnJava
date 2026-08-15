package org.perlonjava.runtime.runtimetypes;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Marker and first-tranche synchronization policy for threads::shared. */
public final class SharedPerlStorage {
    private static final WeakIdentityRegistry<Object, LockState> LOCKS =
            new WeakIdentityRegistry<>();
    private static final Map<Object, ArrayDeque<Waiter>> WAITERS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private SharedPerlStorage() {}

    private static final class LockState {
        final ReentrantLock lock = new ReentrantLock();
    }

    /** Weak keys with explicit identity equality, independent of referent equals/hashCode. */
    private static final class WeakIdentityRegistry<K, V> {
        private final ReferenceQueue<K> staleKeys = new ReferenceQueue<>();
        private final Map<IdentityWeakReference<K>, V> entries = new HashMap<>();

        synchronized V computeIfAbsent(K key, java.util.function.Function<K, V> factory) {
            expungeStaleEntries();
            IdentityWeakReference<K> lookup = new IdentityWeakReference<>(key);
            V value = entries.get(lookup);
            if (value != null) return value;
            value = factory.apply(key);
            entries.put(new IdentityWeakReference<>(key, staleKeys), value);
            return value;
        }

        synchronized int expungeStaleEntries() {
            int removed = 0;
            IdentityWeakReference<?> stale;
            while ((stale = (IdentityWeakReference<?>) staleKeys.poll()) != null) {
                if (entries.remove(stale) != null) removed++;
            }
            return removed;
        }
    }

    private static final class IdentityWeakReference<T> extends WeakReference<T> {
        private final int identityHash;

        IdentityWeakReference(T referent) {
            super(referent);
            identityHash = System.identityHashCode(referent);
        }

        IdentityWeakReference(T referent, ReferenceQueue<T> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference<?> reference)) return false;
            Object referent = get();
            return referent != null && referent == reference.get();
        }
    }

    private record Waiter(CountDownLatch latch) {}

    public static RuntimeBase referent(RuntimeScalar reference) {
        if (reference == null) return null;
        return reference.value instanceof RuntimeBase base ? base : reference;
    }

    public static RuntimeBase share(RuntimeScalar reference) {
        RuntimeBase root = referent(reference);
        return shareValue(root);
    }

    /** Mark declaration storage directly, without manufacturing a reference wrapper. */
    public static RuntimeBase shareValue(RuntimeBase root) {
        if (root == null) throw new IllegalArgumentException("share requires a scalar, array, or hash reference");
        // Validate the complete graph before publishing any shared markers.  A
        // late blessed/tied/CODE node must not leave the prefix of the graph
        // shared after share() reports failure.
        validateGraph(root, Collections.newSetFromMap(new IdentityHashMap<>()));
        markGraph(root, Collections.newSetFromMap(new IdentityHashMap<>()));
        return root;
    }

    public static boolean isShared(RuntimeScalar reference) {
        RuntimeBase root = referent(reference);
        return root != null && root.threadShared;
    }

    public static RuntimeScalar sharedClone(RuntimeScalar reference) {
        PerlRuntime runtime = PerlRuntime.current();
        RuntimeScalar clone = new RuntimeGraphCloner(runtime, runtime).cloneGraph(reference);
        share(clone);
        return clone;
    }

    /** Acquire a recursive advisory lock until the surrounding Perl scope exits. */
    public static RuntimeBase lock(RuntimeScalar reference) {
        RuntimeBase root = requireShared(reference, "lock");
        PerlRuntime owner = PerlRuntime.current();
        LockState state = lockState(root);
        state.lock.lock();
        owner.sharedLockAcquired();
        DynamicVariableManager.pushLocalVariable(new DynamicState() {
            @Override
            public void dynamicSaveState() {
                // The acquisition precedes registration so failed acquisition cannot leak a guard.
            }

            @Override
            public void dynamicRestoreState() {
                try {
                    state.lock.unlock();
                } finally {
                    owner.sharedLockReleased();
                }
            }

            @Override
            public Object dynamicSuspendState() {
                state.lock.unlock();
                return null;
            }

            @Override
            public void dynamicResumeState(Object token) {
                state.lock.lock();
            }
        });
        return root;
    }

    /** Wait indefinitely, atomically publishing the waiter before releasing the lock. */
    public static boolean conditionWait(RuntimeScalar conditionReference, RuntimeScalar lockReference) {
        return conditionWait(conditionReference, lockReference, 0, false);
    }

    /** Wait until an absolute Unix timestamp (seconds), returning false on timeout. */
    public static boolean conditionTimedWait(RuntimeScalar conditionReference,
                                             RuntimeScalar lockReference,
                                             double deadlineSeconds) {
        return conditionWait(conditionReference, lockReference, deadlineSeconds, true);
    }

    public static boolean conditionSignal(RuntimeScalar conditionReference, boolean broadcast) {
        RuntimeBase condition = requireShared(conditionReference,
                broadcast ? "cond_broadcast" : "cond_signal");
        Object conditionIdentity = sharedIdentity(condition);
        if (!lockState(condition).lock.isHeldByCurrentThread()) {
            return false;
        }

        List<Waiter> wake = new ArrayList<>();
        synchronized (WAITERS) {
            ArrayDeque<Waiter> queue = WAITERS.get(conditionIdentity);
            if (queue != null) {
                if (broadcast) {
                    while (!queue.isEmpty()) wake.add(queue.removeFirst());
                } else if (!queue.isEmpty()) {
                    wake.add(queue.removeFirst());
                }
                if (queue.isEmpty()) WAITERS.remove(conditionIdentity);
            }
        }
        for (Waiter waiter : wake) waiter.latch().countDown();
        return true;
    }

    private static boolean conditionWait(RuntimeScalar conditionReference,
                                         RuntimeScalar lockReference,
                                         double deadlineSeconds,
                                         boolean timed) {
        RuntimeBase condition = requireShared(conditionReference,
                timed ? "cond_timedwait" : "cond_wait");
        Object conditionIdentity = sharedIdentity(condition);
        RuntimeBase lockRoot = requireShared(lockReference,
                timed ? "cond_timedwait" : "cond_wait");
        ReentrantLock lock = lockState(lockRoot).lock;
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException((timed ? "cond_timedwait" : "cond_wait")
                    + "() called on unlocked variable");
        }

        Waiter waiter = new Waiter(new CountDownLatch(1));
        synchronized (WAITERS) {
            WAITERS.computeIfAbsent(conditionIdentity, ignored -> new ArrayDeque<>()).addLast(waiter);
        }

        int holds = lock.getHoldCount();
        PerlRuntime owner = PerlRuntime.current();
        owner.sharedWaiterEntered();
        for (int i = 0; i < holds; i++) lock.unlock();
        boolean signalled = false;
        try {
            if (timed) {
                long nanos = Math.max(0L, (long) ((deadlineSeconds
                        - System.currentTimeMillis() / 1000.0) * 1_000_000_000L));
                signalled = waiter.latch().await(nanos, TimeUnit.NANOSECONDS);
            } else {
                waiter.latch().await();
                signalled = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on shared condition", interrupted);
        } finally {
            if (!signalled) {
                synchronized (WAITERS) {
                    ArrayDeque<Waiter> queue = WAITERS.get(conditionIdentity);
                    if (queue != null) {
                        queue.remove(waiter);
                        if (queue.isEmpty()) WAITERS.remove(conditionIdentity);
                    }
                }
            }
            for (int i = 0; i < holds; i++) lock.lock();
            owner.sharedWaiterExited();
        }
        return signalled;
    }

    private static LockState lockState(RuntimeBase root) {
        return LOCKS.computeIfAbsent(sharedIdentity(root), ignored -> new LockState());
    }

    static int expungeStaleLocksForTesting() {
        return LOCKS.expungeStaleEntries();
    }

    static ReentrantLock lockForTesting(RuntimeBase root) {
        return lockState(root).lock;
    }

    private static RuntimeBase requireShared(RuntimeScalar reference, String operation) {
        RuntimeBase root = referent(reference);
        if (root == null || !root.threadShared) {
            throw new IllegalArgumentException(operation + " requires a shared variable");
        }
        return root;
    }

    private static void validateGraph(RuntimeBase value, Set<RuntimeBase> seen) {
        if (value == null || !seen.add(value)) return;
        if (value.blessId != 0) {
            PerlRuntime runtime = PerlRuntime.currentOrNull();
            if (runtime == null || NameNormalizer.getBlessStr(value.blessId) == null) {
                throw new IllegalArgumentException("Cannot share a value with an unknown blessing");
            }
        }
        if (value instanceof RuntimeScalar scalar) {
            if (scalar.type == RuntimeScalarType.TIED_SCALAR) {
                return;
            }
            if (scalar.value instanceof RuntimeBase nested) validateGraph(nested, seen);
            return;
        }
        if (value instanceof RuntimeArray array) {
            if (array.type != RuntimeArray.PLAIN_ARRAY) {
                if (array.type == RuntimeArray.TIED_ARRAY) return;
                throw new IllegalArgumentException("Unsupported shared array type " + array.type);
            }
            for (RuntimeScalar element : array.elements) validateGraph(element, seen);
            return;
        }
        if (value instanceof RuntimeHash hash) {
            if (hash.type != RuntimeHash.PLAIN_HASH) {
                if (hash.type == RuntimeHash.TIED_HASH) return;
                throw new IllegalArgumentException("Unsupported shared hash type " + hash.type);
            }
            for (RuntimeScalar element : hash.elements.values()) validateGraph(element, seen);
            return;
        }
        throw new IllegalArgumentException("Unsupported shared value type " + value.getClass().getName());
    }

    private static void markGraph(RuntimeBase value, Set<RuntimeBase> seen) {
        if (value == null || !seen.add(value)) return;
        if (value instanceof RuntimeScalar scalar) {
            if (scalar.type == RuntimeScalarType.TIED_SCALAR) {
                // Perl keeps scalar magic runtime-local, but share() first
                // stores undef through the current tie object.
                scalar.set(RuntimeScalarCache.scalarUndef);
                markShared(scalar);
                return;
            }
            markShared(scalar);
            if (scalar.value instanceof RuntimeBase nested) markGraph(nested, seen);
            return;
        }
        if (value instanceof RuntimeArray array) {
            if (array.type == RuntimeArray.TIED_ARRAY) {
                // threads::shared replaces an existing aggregate tie with its
                // own shared storage without invoking CLEAR or UNTIE.
                if (array.elements instanceof TieArray tie) tie.releaseTiedObject();
                array.type = RuntimeArray.PLAIN_ARRAY;
                array.elements = Collections.synchronizedList(new ArrayList<>());
                markShared(array);
                return;
            }
            markShared(array);
            for (RuntimeScalar element : array.elements) markGraph(element, seen);
            array.elements = Collections.synchronizedList(array.elements);
            return;
        }
        if (value instanceof RuntimeHash hash) {
            if (hash.type == RuntimeHash.TIED_HASH) {
                if (hash.elements instanceof TieHash tie) tie.releaseTiedObject();
                hash.type = RuntimeHash.PLAIN_HASH;
                hash.elements = Collections.synchronizedMap(new StableHashMap<>());
                hash.resetIterator();
                markShared(hash);
                return;
            }
            markShared(hash);
            for (RuntimeScalar element : hash.elements.values()) markGraph(element, seen);
            hash.elements = Collections.synchronizedMap(hash.elements);
            return;
        }
    }

    private static void markShared(RuntimeBase value) {
        synchronized (value) {
            if (value.threadSharedIdentity == null) value.threadSharedIdentity = new Object();
            if (value.threadSharedLifecycle == null) {
                value.threadSharedLifecycle = new RuntimeBase.SharedLifecycle();
            }
            value.threadSharedBlessName = currentBlessName(value);
            value.threadShared = true;
        }
    }

    /** Return a fresh runtime-local reference view for a nested shared aggregate. */
    static RuntimeScalar fetchedElement(RuntimeBase owner, RuntimeScalar stored) {
        if (!owner.threadShared || stored == null
                || !(stored.value instanceof RuntimeBase nested)
                || !nested.threadShared
                || !(nested instanceof RuntimeArray || nested instanceof RuntimeHash)) {
            return stored;
        }
        PerlRuntime runtime = PerlRuntime.current();
        RuntimeBase view = new RuntimeGraphCloner(runtime, runtime).cloneGraph(nested);
        view.threadSharedFetchedView = true;
        return new SharedElementProxy(stored, view);
    }

    /** Publish the local class of a shared view when that view is stored. */
    static void publishBlessing(RuntimeScalar value) {
        if (value != null && value.value instanceof RuntimeBase base && base.threadShared) {
            base.threadSharedBlessName = currentBlessName(base);
        }
    }

    private static String currentBlessName(RuntimeBase value) {
        if (value.blessId == 0) return null;
        return NameNormalizer.getBlessStr(value.blessId);
    }

    private static Object sharedIdentity(RuntimeBase value) {
        Object identity = value.threadSharedIdentity;
        if (identity != null) return identity;
        synchronized (value) {
            if (value.threadSharedIdentity == null) value.threadSharedIdentity = new Object();
            return value.threadSharedIdentity;
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.util.Collections;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Marker and first-tranche synchronization policy for threads::shared. */
public final class SharedPerlStorage {
    private static final Map<RuntimeBase, LockState> LOCKS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<RuntimeBase, ArrayDeque<Waiter>> WAITERS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private SharedPerlStorage() {}

    private static final class LockState {
        final ReentrantLock lock = new ReentrantLock();
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
        LockState state = lockState(root);
        state.lock.lock();
        DynamicVariableManager.pushLocalVariable(new DynamicState() {
            @Override
            public void dynamicSaveState() {
                // The acquisition precedes registration so failed acquisition cannot leak a guard.
            }

            @Override
            public void dynamicRestoreState() {
                state.lock.unlock();
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
        if (!lockState(condition).lock.isHeldByCurrentThread()) {
            return false;
        }

        List<Waiter> wake = new ArrayList<>();
        synchronized (WAITERS) {
            ArrayDeque<Waiter> queue = WAITERS.get(condition);
            if (queue != null) {
                if (broadcast) {
                    while (!queue.isEmpty()) wake.add(queue.removeFirst());
                } else if (!queue.isEmpty()) {
                    wake.add(queue.removeFirst());
                }
                if (queue.isEmpty()) WAITERS.remove(condition);
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
        RuntimeBase lockRoot = requireShared(lockReference,
                timed ? "cond_timedwait" : "cond_wait");
        ReentrantLock lock = lockState(lockRoot).lock;
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException((timed ? "cond_timedwait" : "cond_wait")
                    + "() called on unlocked variable");
        }

        Waiter waiter = new Waiter(new CountDownLatch(1));
        synchronized (WAITERS) {
            WAITERS.computeIfAbsent(condition, ignored -> new ArrayDeque<>()).addLast(waiter);
        }

        int holds = lock.getHoldCount();
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
                    ArrayDeque<Waiter> queue = WAITERS.get(condition);
                    if (queue != null) {
                        queue.remove(waiter);
                        if (queue.isEmpty()) WAITERS.remove(condition);
                    }
                }
            }
            for (int i = 0; i < holds; i++) lock.lock();
        }
        return signalled;
    }

    private static LockState lockState(RuntimeBase root) {
        synchronized (LOCKS) {
            return LOCKS.computeIfAbsent(root, ignored -> new LockState());
        }
    }

    private static RuntimeBase requireShared(RuntimeScalar reference, String operation) {
        RuntimeBase root = referent(reference);
        if (root == null || !root.threadShared) {
            throw new IllegalArgumentException(operation + " requires a shared variable");
        }
        return root;
    }

    private static void markGraph(RuntimeBase value, Set<RuntimeBase> seen) {
        if (value == null || !seen.add(value)) return;
        if (value.blessId != 0) throw new IllegalArgumentException("Sharing blessed values is not supported");
        if (value instanceof RuntimeScalar scalar) {
            if (scalar.type == RuntimeScalarType.TIED_SCALAR) {
                throw new IllegalArgumentException("Sharing tied values is not supported");
            }
            scalar.threadShared = true;
            if (scalar.value instanceof RuntimeBase nested) markGraph(nested, seen);
            return;
        }
        if (value instanceof RuntimeArray array) {
            if (array.type != RuntimeArray.PLAIN_ARRAY) {
                throw new IllegalArgumentException("Sharing tied arrays is not supported");
            }
            for (RuntimeScalar element : array.elements) markGraph(element, seen);
            array.elements = Collections.synchronizedList(array.elements);
            array.threadShared = true;
            return;
        }
        if (value instanceof RuntimeHash hash) {
            if (hash.type != RuntimeHash.PLAIN_HASH) {
                throw new IllegalArgumentException("Sharing tied hashes is not supported");
            }
            for (RuntimeScalar element : hash.elements.values()) markGraph(element, seen);
            hash.elements = Collections.synchronizedMap(hash.elements);
            hash.threadShared = true;
            return;
        }
        throw new IllegalArgumentException("Unsupported shared value type " + value.getClass().getName());
    }
}

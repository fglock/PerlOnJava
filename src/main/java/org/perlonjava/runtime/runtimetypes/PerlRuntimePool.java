package org.perlonjava.runtime.runtimetypes;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** A bounded, explicitly owned pool of independent {@link PerlRuntime}s. */
public final class PerlRuntimePool implements AutoCloseable {
    public static final String SIZE_PROPERTY = "jperl.runtime.pool.size";
    public static final String SIZE_ENVIRONMENT = "JPERL_RUNTIME_POOL_SIZE";

    @FunctionalInterface
    public interface Recycler {
        PerlRuntime recycle(PerlRuntime runtime) throws Exception;
    }

    private final int capacity;
    private final Supplier<PerlRuntime> factory;
    private final Recycler recycler;
    private final ArrayBlockingQueue<PerlRuntime> available;
    private final Set<PerlRuntime> checkedOut =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object ownershipMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PerlRuntimePool(int capacity) {
        this(capacity, () -> new PerlRuntime().initialize(), runtime -> runtime.reset());
    }

    public PerlRuntimePool(int capacity, Supplier<PerlRuntime> factory, Recycler recycler) {
        if (capacity < 0) throw new IllegalArgumentException("Runtime pool size must not be negative");
        this.capacity = capacity;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.recycler = Objects.requireNonNull(recycler, "recycler");
        this.available = capacity == 0 ? null : new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < capacity; i++) {
            available.add(newRuntime());
        }
    }

    /** Resolve the process-wide pool size. The safe default is disabled. */
    public static int configuredSize() {
        return resolveSize(System.getProperty(SIZE_PROPERTY), System.getenv(SIZE_ENVIRONMENT));
    }

    static int resolveSize(String propertyValue, String environmentValue) {
        String value = propertyValue != null ? propertyValue : environmentValue;
        if (value == null || value.isBlank()) return 0;
        final int size;
        try {
            size = Integer.parseInt(value.strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Invalid Perl runtime pool size '" + value + "'; expected a non-negative integer",
                    invalid);
        }
        if (size < 0) throw new IllegalArgumentException("Runtime pool size must not be negative");
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public int availableCount() {
        return capacity == 0 ? 0 : available.size();
    }

    /**
     * Check out one runtime, waiting for the bounded deadline when pooling is enabled.
     * A disabled pool returns a one-shot runtime whose lease closes it.
     */
    public Lease checkout(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("Checkout timeout must not be negative");
        if (closed.get()) throw new IllegalStateException("Perl runtime pool is closed");

        PerlRuntime runtime;
        boolean pooled = capacity != 0;
        if (pooled) {
            runtime = available.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (runtime == null) throw new IllegalStateException("Timed out waiting for a Perl runtime");
        } else {
            runtime = newRuntime();
        }
        synchronized (ownershipMonitor) {
            if (closed.get()) {
                runtime.close();
                throw new IllegalStateException("Perl runtime pool is closed");
            }
            if (!checkedOut.add(runtime)) {
                throw new IllegalStateException("Perl runtime was checked out twice");
            }
        }
        return new Lease(this, runtime, pooled);
    }

    private PerlRuntime newRuntime() {
        PerlRuntime runtime = Objects.requireNonNull(factory.get(), "runtime factory returned null");
        if (runtime.isClosed()) throw new IllegalStateException("Runtime factory returned a closed runtime");
        return runtime.isInitialized() ? runtime : runtime.initialize();
    }

    private void release(PerlRuntime runtime, boolean pooled) {
        synchronized (ownershipMonitor) {
            if (!checkedOut.remove(runtime)) {
                throw new IllegalStateException("Perl runtime lease is not owned by this pool");
            }
        }
        if (!pooled || closed.get()) {
            runtime.close();
            return;
        }

        PerlRuntime reusable = null;
        try {
            reusable = Objects.requireNonNull(recycler.recycle(runtime), "runtime recycler returned null");
            if (reusable.isClosed()) throw new IllegalStateException("Runtime recycler returned a closed runtime");
        } catch (Throwable resetFailure) {
            runtime.close();
            try {
                reusable = newRuntime();
            } catch (Throwable replacementFailure) {
                resetFailure.addSuppressed(replacementFailure);
                throw new IllegalStateException("Failed to recycle and replace a Perl runtime", resetFailure);
            }
        }

        if (closed.get() || !available.offer(reusable)) {
            reusable.close();
            if (!closed.get()) throw new IllegalStateException("Perl runtime pool overflow");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (available != null) {
            PerlRuntime runtime;
            while ((runtime = available.poll()) != null) runtime.close();
        }
        // Checked-out runtimes are closed by their lease. Closing a pool never
        // races an in-flight request by forcibly resetting its interpreter.
    }

    public static final class Lease implements AutoCloseable {
        private final PerlRuntimePool pool;
        private final PerlRuntime runtime;
        private final boolean pooled;
        private final AtomicBoolean returned = new AtomicBoolean();

        private Lease(PerlRuntimePool pool, PerlRuntime runtime, boolean pooled) {
            this.pool = pool;
            this.runtime = runtime;
            this.pooled = pooled;
        }

        public PerlRuntime runtime() {
            if (returned.get()) throw new IllegalStateException("Perl runtime lease is closed");
            return runtime;
        }

        @Override
        public void close() {
            if (returned.compareAndSet(false, true)) pool.release(runtime, pooled);
        }
    }
}

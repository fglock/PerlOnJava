package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;

import java.lang.ref.Cleaner;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages Perl DESTROY method calls for blessed objects.
 *
 * <p>In Perl, when the last reference to a blessed object goes away,
 * the DESTROY method (if defined) is called. Since PerlOnJava runs on the
 * JVM with garbage collection instead of reference counting, we use
 * {@link java.lang.ref.Cleaner} to detect when blessed objects become
 * unreachable and schedule their DESTROY calls.
 *
 * <p>Performance: Only objects blessed into classes that define DESTROY
 * are tracked. A per-blessId cache makes the check O(1) for repeated
 * bless calls. The safe-point check is a single volatile boolean read
 * (~2 CPU cycles) when no DESTROYs are pending.
 *
 * <p>Architecture:
 * <ol>
 *   <li>At bless time, check the per-blessId cache to see if the class
 *       has DESTROY. If not, return immediately (fast path).</li>
 *   <li>If yes, register the object with the Cleaner along with captured
 *       context data (hashCode, blessId, internal data reference).</li>
 *   <li>When the JVM GC determines the object is phantom-reachable,
 *       the Cleaner thread enqueues a DestroyTask.</li>
 *   <li>The main thread processes pending DESTROY calls at safe points
 *       (same mechanism as signal handling).</li>
 * </ol>
 */
public class DestroyManager {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final Queue<DestroyTask> pendingDestroys = new ConcurrentLinkedQueue<>();

    // Volatile flag for fast checking (like PerlSignalQueue)
    private static volatile boolean hasPendingDestroy = false;

    // Cache: blessId -> has DESTROY method (true/false).
    // Avoids repeated method resolution on every bless call.
    // Invalidated when @ISA changes (via invalidateDestroyCache).
    private static final ConcurrentHashMap<Integer, Boolean> destroyCache = new ConcurrentHashMap<>();

    /**
     * Register a blessed object for DESTROY notification.
     * Called at bless time. Uses a per-blessId cache so repeated
     * bless calls for the same class are O(1).
     *
     * @param target  the blessed RuntimeBase object
     * @param blessId the bless ID of the object
     */
    public static void registerForDestroy(RuntimeBase target, int blessId) {
        // Fast path: check cache first (ConcurrentHashMap.get is lock-free)
        int cacheKey = Math.abs(blessId);
        Boolean hasDestroy = destroyCache.get(cacheKey);
        if (hasDestroy != null) {
            if (!hasDestroy) {
                return; // Class doesn't have DESTROY, skip
            }
        } else {
            // Cache miss: do the method resolution once
            String className = NameNormalizer.getBlessStr(cacheKey);
            RuntimeScalar destroyMethod = InheritanceResolver.findMethodInHierarchy(
                    "DESTROY", className, null, 0);
            boolean found = (destroyMethod != null);
            destroyCache.put(cacheKey, found);
            if (!found) {
                return; // No DESTROY method, nothing to do
            }
        }

        // Only reach here if the class has DESTROY.
        // Capture data needed for DESTROY reconstruction.
        // CRITICAL: The Cleaner action must NOT reference 'target' directly,
        // or the object will never become phantom-reachable.
        int originalHashCode = target.hashCode();
        int capturedBlessId = blessId;

        // Capture the internal data container (shared reference, not a copy).
        // This keeps the data alive after the RuntimeBase wrapper is GC'd.
        Object internalData;
        int proxyType;
        if (target instanceof RuntimeHash hash) {
            internalData = hash.elements;
            proxyType = RuntimeScalarType.HASHREFERENCE;
        } else if (target instanceof RuntimeArray array) {
            internalData = array.elements;
            proxyType = RuntimeScalarType.ARRAYREFERENCE;
        } else if (target instanceof RuntimeCode) {
            internalData = null;
            proxyType = RuntimeScalarType.CODE;
        } else {
            internalData = null;
            proxyType = RuntimeScalarType.REFERENCE;
        }

        CLEANER.register(target, () -> {
            pendingDestroys.add(new DestroyTask(
                    capturedBlessId, originalHashCode, proxyType, internalData));
            hasPendingDestroy = true;
        });
    }

    /**
     * Invalidate the DESTROY cache for a specific class.
     * Should be called when @ISA changes, since a DESTROY method
     * might be added or removed from the inheritance hierarchy.
     *
     * @param blessId the bless ID of the class whose cache entry should be invalidated
     */
    public static void invalidateDestroyCache(int blessId) {
        destroyCache.remove(Math.abs(blessId));
    }

    /**
     * Invalidate the entire DESTROY cache.
     * Called when @ISA changes in a way that might affect multiple classes.
     */
    public static void invalidateAllDestroyCache() {
        destroyCache.clear();
    }

    /**
     * Check and process pending DESTROY calls.
     * Called at safe points on the main thread (alongside signal processing).
     * Fast path is a single volatile boolean read (~2 CPU cycles).
     */
    public static void checkPendingDestroys() {
        if (!hasPendingDestroy) {
            return; // Fast path: no pending DESTROYs
        }
        processPendingDestroysImpl();
    }

    /**
     * Process all pending DESTROY calls.
     * Called at program exit for global destruction phase.
     */
    public static void processAllPendingDestroys() {
        processPendingDestroysImpl();
    }

    @SuppressWarnings("unchecked")
    private static void processPendingDestroysImpl() {
        DestroyTask task;
        while ((task = pendingDestroys.poll()) != null) {
            hasPendingDestroy = !pendingDestroys.isEmpty();
            try {
                String className = NameNormalizer.getBlessStr(Math.abs(task.blessId));
                RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(
                        "DESTROY", className, null, 0);
                if (method == null) {
                    continue;
                }

                // Create a proxy object that stringifies the same as the original
                RuntimeScalar selfRef = createProxySelf(task);

                // Call DESTROY
                RuntimeArray args = new RuntimeArray();
                args.push(selfRef);
                RuntimeCode.apply(method, args, RuntimeContextType.VOID);
            } catch (Throwable t) {
                // DESTROY exceptions in Perl are warned, not fatal
                // "(in cleanup) error message"
                try {
                    System.err.println("(in cleanup) " + t.getMessage());
                } catch (Throwable ignored) {
                    // Ignore errors during error reporting
                }
            }
        }
        hasPendingDestroy = false;
    }

    /**
     * Create a proxy RuntimeScalar that represents $_[0] for the DESTROY call.
     * The proxy has the same hashCode and blessId as the original, ensuring
     * consistent stringification (important for hash key lookups).
     */
    @SuppressWarnings("unchecked")
    private static RuntimeScalar createProxySelf(DestroyTask task) {
        RuntimeScalar selfRef = new RuntimeScalar();

        if (task.proxyType == RuntimeScalarType.HASHREFERENCE) {
            DestroyHashProxy proxy = new DestroyHashProxy(task.originalHashCode);
            proxy.setBlessId(task.blessId);
            if (task.internalData instanceof Map) {
                proxy.elements = (Map<String, RuntimeScalar>) task.internalData;
            }
            selfRef.type = RuntimeScalarType.HASHREFERENCE;
            selfRef.value = proxy;
        } else if (task.proxyType == RuntimeScalarType.ARRAYREFERENCE) {
            DestroyArrayProxy proxy = new DestroyArrayProxy(task.originalHashCode);
            proxy.setBlessId(task.blessId);
            if (task.internalData instanceof java.util.List) {
                proxy.elements = (java.util.List<RuntimeScalar>) task.internalData;
            }
            selfRef.type = RuntimeScalarType.ARRAYREFERENCE;
            selfRef.value = proxy;
        } else if (task.proxyType == RuntimeScalarType.CODE) {
            DestroyCodeProxy proxy = new DestroyCodeProxy(task.originalHashCode);
            proxy.setBlessId(task.blessId);
            selfRef.type = RuntimeScalarType.CODE;
            selfRef.value = proxy;
        } else {
            DestroyScalarProxy proxy = new DestroyScalarProxy(task.originalHashCode);
            proxy.setBlessId(task.blessId);
            selfRef.type = RuntimeScalarType.REFERENCE;
            selfRef.value = proxy;
        }

        return selfRef;
    }

    /**
     * Force GC and process remaining DESTROY calls.
     * Used during global destruction phase at program exit.
     */
    public static void runGlobalDestruction() {
        // Hint to GC to collect unreachable objects
        System.gc();
        // Give Cleaner thread time to process
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        processAllPendingDestroys();
    }

    // Task record for pending DESTROY calls
    static class DestroyTask {
        final int blessId;
        final int originalHashCode;
        final int proxyType;
        final Object internalData;

        DestroyTask(int blessId, int originalHashCode, int proxyType, Object internalData) {
            this.blessId = blessId;
            this.originalHashCode = originalHashCode;
            this.proxyType = proxyType;
            this.internalData = internalData;
        }
    }

    /**
     * Hash proxy that overrides hashCode() to match the original object.
     * This ensures stringification like "Class=HASH(0xABC)" matches.
     */
    static class DestroyHashProxy extends RuntimeHash {
        private final int proxyHashCode;

        DestroyHashProxy(int hashCode) {
            this.proxyHashCode = hashCode;
        }

        @Override
        public int hashCode() {
            return proxyHashCode;
        }
    }

    /**
     * Array proxy that overrides hashCode() to match the original object.
     */
    static class DestroyArrayProxy extends RuntimeArray {
        private final int proxyHashCode;

        DestroyArrayProxy(int hashCode) {
            this.proxyHashCode = hashCode;
        }

        @Override
        public int hashCode() {
            return proxyHashCode;
        }
    }

    /**
     * Code proxy that overrides hashCode() to match the original object.
     */
    static class DestroyCodeProxy extends RuntimeCode {
        private final int proxyHashCode;

        DestroyCodeProxy(int hashCode) {
            super(null, null, null);
            this.proxyHashCode = hashCode;
        }

        @Override
        public int hashCode() {
            return proxyHashCode;
        }

        @Override
        public String toStringRef() {
            String ref = "CODE(0x" + Integer.toHexString(proxyHashCode) + ")";
            return (blessId == 0
                    ? ref
                    : NameNormalizer.getBlessStr(blessId) + "=" + ref);
        }
    }

    /**
     * Scalar proxy that overrides hashCode() to match the original object.
     */
    static class DestroyScalarProxy extends RuntimeScalar {
        private final int proxyHashCode;

        DestroyScalarProxy(int hashCode) {
            this.proxyHashCode = hashCode;
        }

        @Override
        public int hashCode() {
            return proxyHashCode;
        }
    }
}

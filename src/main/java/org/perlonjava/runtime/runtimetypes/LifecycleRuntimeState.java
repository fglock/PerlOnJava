package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned mortal, weak-reference, DESTROY, and owner-slot state. */
final class LifecycleRuntimeState {
    final AtomicBoolean boundaryWorkRegistered = new AtomicBoolean();
    boolean mortalActive = true;
    final ArrayList<RuntimeBase> pending = new ArrayList<>();
    // Parallel to pending.  A non-null entry retains trace-only provenance for
    // an owner token whose scalar was cleared when its decrement was queued.
    final ArrayList<RuntimeBase.PendingOwnerRelease> pendingOwnerReleases = new ArrayList<>();
    // Parallel to pending.  Weak provenance permits a tail-call handoff to
    // drain only the decrement whose owning argument it actually replaces.
    final ArrayList<java.lang.ref.WeakReference<RuntimeScalar>> pendingOwnerScalars = new ArrayList<>();
    // Parallel to pending. Captured when a scalar decrement is queued so a
    // tail-call handoff can match its source argument frame by identity.
    final ArrayList<Object> pendingArgumentFrames = new ArrayList<>();
    // Parallel to pending. A non-scalar transient owner kind is recorded only
    // for trace attribution; it does not create a Perl owner.
    final ArrayList<String> pendingTransientOwnerKinds = new ArrayList<>();
    final ArrayList<MortalList.DeferredArgumentOwnerRelease> deferredArgumentOwnerReleases =
            new ArrayList<>();
    final ArrayList<TiedVariableBase> pendingTiedReleases = new ArrayList<>();
    final ArrayList<RuntimeScalar> pendingIoReleases = new ArrayList<>();
    final ArrayList<RuntimeScalar> pendingUntrackedReturnOwnerReleases = new ArrayList<>();
    // One-shot list-assignment arrays keep their copied cells alive through the
    // receiving caller's statement boundary when a subroutine returns them.
    static final class TransientListAssignmentFrame {
        final RuntimeCode executionCode;
        final int executionDepth;

        TransientListAssignmentFrame(RuntimeCode executionCode, int executionDepth) {
            this.executionCode = executionCode;
            this.executionDepth = executionDepth;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TransientListAssignmentFrame frame
                    && frame.executionCode == executionCode
                    && frame.executionDepth == executionDepth;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(executionCode) + executionDepth;
        }
    }

    static final class PendingTransientListAssignmentRhs {
        final RuntimeArray array;
        final long enqueueSequence;
        long drainAtStatementBoundary;

        PendingTransientListAssignmentRhs(RuntimeArray array, long enqueueSequence,
                long drainAtStatementBoundary) {
            this.array = array;
            this.enqueueSequence = enqueueSequence;
            this.drainAtStatementBoundary = drainAtStatementBoundary;
        }
    }

    /** Compact per-frame append buffer; boundaries are monotonic in normal use. */
    static final class TransientListAssignmentQueue {
        private RuntimeArray[] arrays = new RuntimeArray[8];
        private long[] enqueueSequences = new long[8];
        private long[] boundaries = new long[4];
        private int[] boundaryEnds = new int[4];
        private int size;
        private int boundaryCount;
        private int firstBoundary;
        private java.util.TreeMap<Long, ArrayDeque<PendingTransientListAssignmentRhs>>
                orderedFallback;
        TransientListAssignmentQueue nextReusable;

        boolean isEmpty() {
            return orderedFallback == null
                    ? size == 0 : orderedFallback.isEmpty();
        }

        /** Append without allocating a per-boundary collection. */
        void append(RuntimeArray array, long sequence, long boundary) {
            if (orderedFallback != null) {
                appendToFallback(array, sequence, boundary);
                return;
            }
            if (firstBoundary < boundaryCount
                    && boundary < boundaries[boundaryCount - 1]) {
                promoteToOrderedFallback();
                appendToFallback(array, sequence, boundary);
                return;
            }
            appendOrdered(array, sequence, boundary);
        }

        private void appendOrdered(RuntimeArray array, long sequence, long boundary) {
            ensureEntryCapacity(size + 1);
            arrays[size] = array;
            enqueueSequences[size] = sequence;
            size++;
            if (firstBoundary < boundaryCount
                    && boundaries[boundaryCount - 1] == boundary) {
                boundaryEnds[boundaryCount - 1] = size;
                return;
            }
            ensureBoundaryCapacity(boundaryCount + 1);
            boundaries[boundaryCount] = boundary;
            boundaryEnds[boundaryCount] = size;
            boundaryCount++;
            LifecycleHotPathDiagnostics.transientRhsBoundaryMarked();
        }

        private void appendToFallback(RuntimeArray array, long sequence, long boundary) {
            ArrayDeque<PendingTransientListAssignmentRhs> entries =
                    orderedFallback.get(boundary);
            if (entries == null) {
                entries = new ArrayDeque<>();
                orderedFallback.put(boundary, entries);
                LifecycleHotPathDiagnostics.boundaryQueueAllocated();
            }
            entries.addLast(new PendingTransientListAssignmentRhs(array, sequence, boundary));
        }

        private void promoteToOrderedFallback() {
            orderedFallback = new java.util.TreeMap<>();
            int start = firstBoundary == 0 ? 0 : boundaryEnds[firstBoundary - 1];
            for (int boundaryIndex = firstBoundary;
                    boundaryIndex < boundaryCount; boundaryIndex++) {
                long boundary = boundaries[boundaryIndex];
                ArrayDeque<PendingTransientListAssignmentRhs> entries = new ArrayDeque<>();
                int end = boundaryEnds[boundaryIndex];
                for (int entryIndex = start; entryIndex < end; entryIndex++) {
                    RuntimeArray array = arrays[entryIndex];
                    if (array != null) {
                        entries.addLast(new PendingTransientListAssignmentRhs(
                                array, enqueueSequences[entryIndex], boundary));
                    }
                }
                if (!entries.isEmpty()) {
                    orderedFallback.put(boundary, entries);
                    LifecycleHotPathDiagnostics.boundaryQueueAllocated();
                }
                start = end;
            }
            clearBufferedEntries();
            LifecycleHotPathDiagnostics.transientRhsBoundaryOrderRegression();
        }

        void drainAtBoundary(long currentBoundary) {
            if (orderedFallback != null) {
                java.util.Map.Entry<Long, ArrayDeque<PendingTransientListAssignmentRhs>> due =
                        orderedFallback.floorEntry(currentBoundary);
                while (due != null) {
                    orderedFallback.remove(due.getKey());
                    ArrayDeque<PendingTransientListAssignmentRhs> entries = due.getValue();
                    while (!entries.isEmpty()) {
                        PendingTransientListAssignmentRhs pending = entries.removeLast();
                        LifecycleHotPathDiagnostics.transientRhsDrained();
                        MortalList.releaseTransientListAssignmentRhs(pending.array);
                    }
                    due = orderedFallback.floorEntry(currentBoundary);
                }
                if (orderedFallback.isEmpty()) resetForReuse();
                return;
            }

            int dueBoundaryEnd = firstBoundary;
            while (dueBoundaryEnd < boundaryCount
                    && boundaries[dueBoundaryEnd] <= currentBoundary) {
                dueBoundaryEnd++;
            }
            for (int boundaryIndex = dueBoundaryEnd - 1;
                    boundaryIndex >= firstBoundary; boundaryIndex--) {
                int start = boundaryIndex == 0 ? 0 : boundaryEnds[boundaryIndex - 1];
                int end = boundaryEnds[boundaryIndex];
                for (int entryIndex = end - 1; entryIndex >= start; entryIndex--) {
                    RuntimeArray array = arrays[entryIndex];
                    if (array != null) {
                        arrays[entryIndex] = null;
                        enqueueSequences[entryIndex] = 0;
                        LifecycleHotPathDiagnostics.transientRhsDrained();
                        MortalList.releaseTransientListAssignmentRhs(array);
                    }
                }
            }
            firstBoundary = dueBoundaryEnd;
            if (firstBoundary == boundaryCount) {
                resetForReuse();
            } else {
                compactConsumedPrefix();
            }
        }

        /** Move this frame's FIFO sequence to the caller's next LIFO bucket. */
        void handoffTo(TransientListAssignmentQueue target, long nextBoundary) {
            if (orderedFallback != null) {
                for (ArrayDeque<PendingTransientListAssignmentRhs> entries
                        : orderedFallback.values()) {
                    for (PendingTransientListAssignmentRhs pending : entries) {
                        target.append(pending.array, pending.enqueueSequence, nextBoundary);
                        LifecycleHotPathDiagnostics.transientRhsHandedOff();
                    }
                }
            } else {
                int start = firstBoundary == 0 ? 0 : boundaryEnds[firstBoundary - 1];
                for (int entryIndex = start; entryIndex < size; entryIndex++) {
                    RuntimeArray array = arrays[entryIndex];
                    if (array != null) {
                        target.append(array, enqueueSequences[entryIndex], nextBoundary);
                        LifecycleHotPathDiagnostics.transientRhsHandedOff();
                    }
                }
            }
            resetForReuse();
        }

        /** Retag a source queue when it becomes the caller queue directly. */
        void retagAll(long boundary) {
            if (orderedFallback != null) {
                java.util.TreeMap<Long, ArrayDeque<PendingTransientListAssignmentRhs>> old =
                        orderedFallback;
                orderedFallback = null;
                clearBufferedEntries();
                for (ArrayDeque<PendingTransientListAssignmentRhs> entries : old.values()) {
                    for (PendingTransientListAssignmentRhs pending : entries) {
                        appendOrdered(pending.array, pending.enqueueSequence, boundary);
                    }
                }
                old.clear();
                return;
            }
            int start = firstBoundary == 0 ? 0 : boundaryEnds[firstBoundary - 1];
            int write = 0;
            for (int entryIndex = start; entryIndex < size; entryIndex++) {
                RuntimeArray array = arrays[entryIndex];
                if (array != null) {
                    arrays[write] = array;
                    enqueueSequences[write] = enqueueSequences[entryIndex];
                    write++;
                }
            }
            Arrays.fill(arrays, write, size, null);
            Arrays.fill(enqueueSequences, write, size, 0);
            size = write;
            boundaryCount = 0;
            firstBoundary = 0;
            if (size > 0) {
                ensureBoundaryCapacity(1);
                boundaries[0] = boundary;
                boundaryEnds[0] = size;
                boundaryCount = 1;
            }
        }

        void collectPending(List<PendingTransientListAssignmentRhs> destination) {
            if (orderedFallback != null) {
                for (ArrayDeque<PendingTransientListAssignmentRhs> entries
                        : orderedFallback.values()) {
                    destination.addAll(entries);
                }
                return;
            }
            int start = firstBoundary == 0 ? 0 : boundaryEnds[firstBoundary - 1];
            for (int entryIndex = start; entryIndex < size; entryIndex++) {
                RuntimeArray array = arrays[entryIndex];
                if (array != null) {
                    destination.add(new PendingTransientListAssignmentRhs(
                            array, enqueueSequences[entryIndex], 0));
                }
            }
        }

        private void compactConsumedPrefix() {
            if (firstBoundary < 4 || firstBoundary * 2 < boundaryCount) return;
            int consumedEntries = boundaryEnds[firstBoundary - 1];
            int remainingEntries = size - consumedEntries;
            System.arraycopy(arrays, consumedEntries, arrays, 0, remainingEntries);
            System.arraycopy(enqueueSequences, consumedEntries,
                    enqueueSequences, 0, remainingEntries);
            Arrays.fill(arrays, remainingEntries, size, null);
            Arrays.fill(enqueueSequences, remainingEntries, size, 0);
            int remainingBoundaries = boundaryCount - firstBoundary;
            System.arraycopy(boundaries, firstBoundary, boundaries, 0, remainingBoundaries);
            System.arraycopy(boundaryEnds, firstBoundary, boundaryEnds, 0, remainingBoundaries);
            for (int i = 0; i < remainingBoundaries; i++) {
                boundaryEnds[i] -= consumedEntries;
            }
            size = remainingEntries;
            boundaryCount = remainingBoundaries;
            firstBoundary = 0;
        }

        private void ensureEntryCapacity(int required) {
            if (required <= arrays.length) return;
            int capacity = arrays.length * 2;
            while (capacity < required) capacity *= 2;
            arrays = Arrays.copyOf(arrays, capacity);
            enqueueSequences = Arrays.copyOf(enqueueSequences, capacity);
            LifecycleHotPathDiagnostics.transientRhsEntryBufferGrown();
        }

        private void ensureBoundaryCapacity(int required) {
            if (required <= boundaries.length) return;
            int capacity = boundaries.length * 2;
            while (capacity < required) capacity *= 2;
            boundaries = Arrays.copyOf(boundaries, capacity);
            boundaryEnds = Arrays.copyOf(boundaryEnds, capacity);
            LifecycleHotPathDiagnostics.transientRhsBoundaryBufferGrown();
        }

        private void clearBufferedEntries() {
            Arrays.fill(arrays, 0, size, null);
            Arrays.fill(enqueueSequences, 0, size, 0);
            size = 0;
            boundaryCount = 0;
            firstBoundary = 0;
        }

        void resetForReuse() {
            if (orderedFallback != null) {
                orderedFallback.clear();
                orderedFallback = null;
            }
            clearBufferedEntries();
        }
    }

    // Each active Perl frame owns one compact queue. A statement flush visits
    // only its due boundary segments; returning a frame hands the queue to its
    // caller. Segment marks are integer indexes into the append-only arrays.
    final Map<TransientListAssignmentFrame,
            TransientListAssignmentQueue> pendingTransientListAssignmentRhsByFrame =
            new HashMap<>();
    TransientListAssignmentQueue reusableTransientListAssignmentQueues;

    TransientListAssignmentQueue obtainTransientListAssignmentQueue() {
        TransientListAssignmentQueue queue = reusableTransientListAssignmentQueues;
        if (queue == null) return new TransientListAssignmentQueue();
        reusableTransientListAssignmentQueues = queue.nextReusable;
        queue.nextReusable = null;
        return queue;
    }

    void recycleTransientListAssignmentQueue(TransientListAssignmentQueue queue) {
        if (queue == null) return;
        queue.resetForReuse();
        queue.nextReusable = reusableTransientListAssignmentQueues;
        reusableTransientListAssignmentQueues = queue;
    }
    long nextTransientListAssignmentSequence;
    long statementBoundarySequence;
    final ArrayList<RuntimeScalar> deferredCaptures = new ArrayList<>();
    final IdentityHashMap<RuntimeScalar, Integer> deferredCapturesSet = new IdentityHashMap<>();
    // Strong semantic referents retained by explicit Perl owner slots. Counts
    // are per referent because several independent pad slots can own one base.
    final IdentityHashMap<RuntimeBase, Integer> positiveOwnerReferents = new IdentityHashMap<>();
    boolean deferredCapturesMayBeReady;
    final ArrayDeque<TemporaryRootLease> temporaryRoots = new ArrayDeque<>();
    final ArrayDeque<ForeachSourceOwnerLease> foreachSourceOwners = new ArrayDeque<>();
    private static final int INITIAL_MARK_CAPACITY = 16;
    int[] marks = new int[INITIAL_MARK_CAPACITY];
    int[] tiedReleaseMarks = new int[INITIAL_MARK_CAPACITY];
    int[] ioReleaseMarks = new int[INITIAL_MARK_CAPACITY];
    int[] untrackedReturnOwnerMarks = new int[INITIAL_MARK_CAPACITY];
    int markDepth;
    boolean flushing;
    boolean flushSuppressed;
    int deferredReleaseDrainDepth;
    // Reusable primitive stack for nested releases appended while a boundary
    // drains its owner queue. This keeps destruction depth-first without
    // allocating a boxed cursor for each aggregate edge.
    int[] pendingReleaseResumeStack;
    int pendingReleaseResumeDepth;

    void pushPendingReleaseResume(int cursor, int floor) {
        int required = (pendingReleaseResumeDepth + 1) * 2;
        if (pendingReleaseResumeStack == null) {
            pendingReleaseResumeStack = new int[16];
        } else if (required > pendingReleaseResumeStack.length) {
            pendingReleaseResumeStack = Arrays.copyOf(
                    pendingReleaseResumeStack, pendingReleaseResumeStack.length * 2);
        }
        int offset = pendingReleaseResumeDepth * 2;
        pendingReleaseResumeStack[offset] = cursor;
        pendingReleaseResumeStack[offset + 1] = floor;
        pendingReleaseResumeDepth++;
    }

    void pushMortalMark(int pendingSize, int tiedReleaseSize, int ioReleaseSize,
                        int untrackedReturnOwnerReleaseSize) {
        if (markDepth == marks.length) {
            int capacity = marks.length * 2;
            marks = Arrays.copyOf(marks, capacity);
            tiedReleaseMarks = Arrays.copyOf(tiedReleaseMarks, capacity);
            ioReleaseMarks = Arrays.copyOf(ioReleaseMarks, capacity);
            untrackedReturnOwnerMarks = Arrays.copyOf(untrackedReturnOwnerMarks, capacity);
        }
        marks[markDepth] = pendingSize;
        tiedReleaseMarks[markDepth] = tiedReleaseSize;
        ioReleaseMarks[markDepth] = ioReleaseSize;
        untrackedReturnOwnerMarks[markDepth] = untrackedReturnOwnerReleaseSize;
        markDepth++;
    }

    boolean hasMortalMarks() {
        return markDepth != 0;
    }

    void popMortalMark() {
        if (markDepth > 0) markDepth--;
    }

    final Set<RuntimeScalar> weakScalars = Collections.newSetFromMap(new IdentityHashMap<>());
    final IdentityHashMap<RuntimeBase, Set<RuntimeScalar>> referentToWeakRefs = new IdentityHashMap<>();
    volatile boolean weakRefsExist;

    final BitSet destroyClasses = new BitSet();
    final BitSet destroyClassesChecked = new BitSet();
    final ConcurrentHashMap<Integer, RuntimeScalar> destroyMethodCache = new ConcurrentHashMap<>();
    final Set<RuntimeBase> destroyableObjects =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    // Perl invokes DESTROY for blessed objects in refcount cycles during
    // global destruction. Keep a weak registry so those
    // cycles remain discoverable without extending object lifetime.
    final WeakIdentitySet<RuntimeBase> globalDestroyables = new WeakIdentitySet<>();
    final Set<RuntimeBase> statementBoundaryDestroyableObjects =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    RuntimeBase currentDestroyTarget;
    boolean destroyTargetRescued;
    final List<RuntimeBase> rescuedObjects = Collections.synchronizedList(new ArrayList<>());
    boolean blessedObjectExists;
    final Map<RuntimeBase, LinkedHashMap<Integer, String>> traceOwners =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** One expression-root stack entry and its exact temporary owner identity. */
    static final class TemporaryRootLease {
        private final RuntimeBase root;
        private final PerlOwnerSlot ownerSlot;

        TemporaryRootLease(RuntimeBase root) {
            this.root = root;
            if (root.isPerlDestroyed()) {
                // Keep the boundary balanced without reviving a destroyed value.
                this.ownerSlot = null;
            } else {
                if (root.isRefCountUntracked() && root.refCount > 0) {
                    // Adopt a positive legacy count before acquiring the
                    // expression owner so the native lease pairs with it.
                    root.setTrackedRefCount(root.refCount);
                }
                PerlOwnerSlot owner = new PerlOwnerSlot(PerlOwnerSlot.Kind.EXPRESSION_VALUE);
                owner.acquireCountedNativeOwner(root, "MortalList temporary expression value");
                this.ownerSlot = owner;
            }
        }

        RuntimeBase root() {
            return root;
        }

        void release() {
            if (ownerSlot != null) ownerSlot.releaseNativeOwner();
            if (root.localBindingExists || root.hasCurrentPerlStorageOwner()) return;
            if (root instanceof RuntimeArray array) {
                MortalList.scopeExitCleanupArray(array);
            } else if (root instanceof RuntimeHash hash) {
                MortalList.scopeExitCleanupHash(hash);
            }
        }
    }

    /** Explicit temporary owners for references in one evaluated foreach list. */
    static final class ForeachSourceOwnerLease {
        private final List<PerlOwnerSlot> owners = new ArrayList<>();

        void retain(RuntimeScalar scalar) {
            if (scalar == null || scalar.nonOwningBObjectReference
                    || (scalar.type & RuntimeScalarType.REFERENCE_BIT) == 0
                    || WeakRefRegistry.isweak(scalar)
                    || !(scalar.value instanceof RuntimeBase referent)) {
                return;
            }
            retainReferent(referent);
        }

        void retainReferent(RuntimeBase referent) {
            if (referent == null || referent.isPerlDestroyed()) return;
            PerlOwnerSlot owner = new PerlOwnerSlot(PerlOwnerSlot.Kind.EXPRESSION_VALUE);
            owner.acquireCountedNativeOwner(referent, "foreach source list value");
            owners.add(owner);
        }

        void release() {
            Throwable failure = null;
            for (int i = owners.size() - 1; i >= 0; i--) {
                try {
                    owners.get(i).releaseNativeOwner();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            owners.clear();
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error errorFailure) throw errorFailure;
        }
    }

    void clear() {
        mortalActive = true;
        while (!pendingTransientListAssignmentRhsByFrame.isEmpty()) {
            ArrayList<PendingTransientListAssignmentRhs> pendingRhs = new ArrayList<>();
            for (TransientListAssignmentQueue queue
                    : pendingTransientListAssignmentRhsByFrame.values()) {
                queue.collectPending(pendingRhs);
            }
            pendingTransientListAssignmentRhsByFrame.clear();
            reusableTransientListAssignmentQueues = null;
            pendingRhs.sort((left, right) -> Long.compare(
                    right.enqueueSequence, left.enqueueSequence));
            for (PendingTransientListAssignmentRhs pending : pendingRhs) {
                if (pending != null && pending.array != null) {
                    LifecycleHotPathDiagnostics.transientRhsDrained();
                    MortalList.releaseTransientListAssignmentRhs(pending.array);
                }
            }
        }
        for (int i = 0; i < pending.size(); i++) {
            RuntimeBase.PendingOwnerRelease release = pendingOwnerReleases.get(i);
            if (release != null) {
                pending.get(i).cancelQueuedOwnerRelease(release,
                        "LifecycleRuntimeState.clear");
            }
            pending.get(i).releaseTransientTraceOwner(pendingTransientOwnerKinds.get(i),
                    "LifecycleRuntimeState.clear");
        }
        pending.clear();
        pendingOwnerReleases.clear();
        pendingOwnerScalars.clear();
        pendingArgumentFrames.clear();
        pendingTransientOwnerKinds.clear();
        pendingTiedReleases.clear();
        pendingIoReleases.clear();
        statementBoundarySequence = 0;
        for (RuntimeScalar scalar : pendingUntrackedReturnOwnerReleases) {
            if (scalar != null) scalar.releaseMortalReturnValueOwner();
        }
        pendingUntrackedReturnOwnerReleases.clear();
        deferredCaptures.clear();
        deferredCapturesSet.clear();
        synchronized (this) {
            positiveOwnerReferents.clear();
        }
        deferredCapturesMayBeReady = false;
        while (!temporaryRoots.isEmpty()) temporaryRoots.pop().release();
        while (!foreachSourceOwners.isEmpty()) foreachSourceOwners.pop().release();
        markDepth = 0;
        flushing = false;
        flushSuppressed = false;
        deferredReleaseDrainDepth = 0;
        pendingReleaseResumeDepth = 0;
        weakScalars.clear();
        referentToWeakRefs.clear();
        weakRefsExist = false;
        destroyClasses.clear();
        destroyClassesChecked.clear();
        destroyMethodCache.clear();
        destroyableObjects.clear();
        globalDestroyables.clear();
        statementBoundaryDestroyableObjects.clear();
        currentDestroyTarget = null;
        destroyTargetRescued = false;
        rescuedObjects.clear();
        blessedObjectExists = false;
        traceOwners.clear();
    }

}

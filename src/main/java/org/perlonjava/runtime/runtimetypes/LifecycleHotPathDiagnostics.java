package org.perlonjava.runtime.runtimetypes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in counters for measuring lifecycle scans and deferred-list cleanup. */
final class LifecycleHotPathDiagnostics {
    static final boolean ENABLED = Boolean.getBoolean("perlonjava.lifecycleHotPathDiagnostics");
    private static final String OUTPUT =
            System.getProperty("perlonjava.lifecycleHotPathDiagnosticsOutput");

    private static final LongAdder OWNER_SLOTS_VISITED = new LongAdder();
    private static final LongAdder OWNER_SLOT_IDENTITY_FAST_PATHS = new LongAdder();
    private static final LongAdder TRANSIENT_RHS_APPENDS = new LongAdder();
    private static final LongAdder TRANSIENT_RHS_DRAINS = new LongAdder();
    private static final LongAdder TRANSIENT_RHS_HANDOFFS = new LongAdder();
    private static final LongAdder FRAME_QUEUE_ALLOCATIONS = new LongAdder();
    private static final LongAdder FRAME_QUEUE_REUSES = new LongAdder();
    private static final LongAdder BOUNDARY_QUEUE_ALLOCATIONS = new LongAdder();
    private static final LongAdder BOUNDARY_MARKS = new LongAdder();
    private static final LongAdder BOUNDARY_ORDER_REGRESSIONS = new LongAdder();
    private static final LongAdder ENTRY_BUFFER_GROWTHS = new LongAdder();
    private static final LongAdder BOUNDARY_BUFFER_GROWTHS = new LongAdder();
    private static final LongAdder AGGREGATE_RELEASE_WALKERS = new LongAdder();
    private static final LongAdder AGGREGATE_RELEASE_WORKSET_ALLOCATIONS = new LongAdder();
    private static final LongAdder AGGREGATE_RELEASE_ROOTS = new LongAdder();
    private static final LongAdder AGGREGATE_RELEASE_PROBE_WORKSET_ALLOCATIONS = new LongAdder();
    private static final LongAdder AGGREGATE_RELEASE_PROBE_ROOTS = new LongAdder();

    static {
        if (ENABLED && OUTPUT != null && !OUTPUT.isBlank()) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    LifecycleHotPathDiagnostics::writeReport,
                    "perlonjava-lifecycle-hot-path-diagnostics"));
        }
    }

    private LifecycleHotPathDiagnostics() { }

    static void ownerSlotsVisited(long count) {
        if (ENABLED && count > 0) OWNER_SLOTS_VISITED.add(count);
    }

    static void ownerSlotIdentityFastPath() {
        if (ENABLED) OWNER_SLOT_IDENTITY_FAST_PATHS.increment();
    }

    static void transientRhsAppended() {
        if (ENABLED) TRANSIENT_RHS_APPENDS.increment();
    }

    static void transientRhsDrained() {
        if (ENABLED) TRANSIENT_RHS_DRAINS.increment();
    }

    static void transientRhsHandedOff() {
        if (ENABLED) TRANSIENT_RHS_HANDOFFS.increment();
    }

    static void frameQueueAllocated() {
        if (ENABLED) FRAME_QUEUE_ALLOCATIONS.increment();
    }

    static void transientRhsFrameQueueReused() {
        if (ENABLED) FRAME_QUEUE_REUSES.increment();
    }

    static void boundaryQueueAllocated() {
        if (ENABLED) BOUNDARY_QUEUE_ALLOCATIONS.increment();
    }

    static void transientRhsBoundaryMarked() {
        if (ENABLED) BOUNDARY_MARKS.increment();
    }

    static void transientRhsBoundaryOrderRegression() {
        if (ENABLED) BOUNDARY_ORDER_REGRESSIONS.increment();
    }

    static void transientRhsEntryBufferGrown() {
        if (ENABLED) ENTRY_BUFFER_GROWTHS.increment();
    }

    static void transientRhsBoundaryBufferGrown() {
        if (ENABLED) BOUNDARY_BUFFER_GROWTHS.increment();
    }

    static void aggregateReleaseWalkerAllocated() {
        if (ENABLED) AGGREGATE_RELEASE_WALKERS.increment();
    }

    static void aggregateReleaseWorksetAllocated() {
        if (ENABLED) AGGREGATE_RELEASE_WORKSET_ALLOCATIONS.increment();
    }

    static void aggregateReleaseRootProcessed() {
        if (ENABLED) AGGREGATE_RELEASE_ROOTS.increment();
    }

    static void aggregateReleaseProbeWorksetAllocated() {
        if (ENABLED) AGGREGATE_RELEASE_PROBE_WORKSET_ALLOCATIONS.increment();
    }

    static void aggregateReleaseProbeRootProcessed() {
        if (ENABLED) AGGREGATE_RELEASE_PROBE_ROOTS.increment();
    }

    private static void writeReport() {
        long pid = ProcessHandle.current().pid();
        String json = "{\n"
                + "  \"kind\": \"perlonjava-lifecycle-hot-path-diagnostics\",\n"
                + "  \"process_id\": " + pid + ",\n"
                + "  \"owner_slots_visited\": " + OWNER_SLOTS_VISITED.sum() + ",\n"
                + "  \"owner_slot_identity_fast_paths\": "
                + OWNER_SLOT_IDENTITY_FAST_PATHS.sum() + ",\n"
                + "  \"transient_rhs_appends\": " + TRANSIENT_RHS_APPENDS.sum() + ",\n"
                + "  \"transient_rhs_drains\": " + TRANSIENT_RHS_DRAINS.sum() + ",\n"
                + "  \"transient_rhs_handoffs\": " + TRANSIENT_RHS_HANDOFFS.sum() + ",\n"
                + "  \"frame_queue_allocations\": " + FRAME_QUEUE_ALLOCATIONS.sum() + ",\n"
                + "  \"frame_queue_reuses\": " + FRAME_QUEUE_REUSES.sum() + ",\n"
                + "  \"boundary_queue_allocations\": " + BOUNDARY_QUEUE_ALLOCATIONS.sum() + ",\n"
                + "  \"boundary_marks\": " + BOUNDARY_MARKS.sum() + ",\n"
                + "  \"boundary_order_regressions\": " + BOUNDARY_ORDER_REGRESSIONS.sum() + ",\n"
                + "  \"entry_buffer_growths\": " + ENTRY_BUFFER_GROWTHS.sum() + ",\n"
                + "  \"boundary_buffer_growths\": " + BOUNDARY_BUFFER_GROWTHS.sum() + ",\n"
                + "  \"aggregate_release_walkers\": " + AGGREGATE_RELEASE_WALKERS.sum() + ",\n"
                + "  \"aggregate_release_workset_allocations\": "
                + AGGREGATE_RELEASE_WORKSET_ALLOCATIONS.sum() + ",\n"
                + "  \"aggregate_release_roots\": " + AGGREGATE_RELEASE_ROOTS.sum() + ",\n"
                + "  \"aggregate_release_probe_workset_allocations\": "
                + AGGREGATE_RELEASE_PROBE_WORKSET_ALLOCATIONS.sum() + ",\n"
                + "  \"aggregate_release_probe_roots\": "
                + AGGREGATE_RELEASE_PROBE_ROOTS.sum() + "\n"
                + "}\n";
        try {
            Path output = Path.of(OUTPUT + "-" + pid + ".json");
            java.nio.file.Files.writeString(output, json);
        } catch (IOException | RuntimeException e) {
            System.err.println("cannot write lifecycle hot-path diagnostics: " + e.getMessage());
        }
    }
}

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/** Bounded streaming extractor for Phase 36 final-performance JFR evidence. */
public final class Phase36JfrMetrics {
    private record HeapAfterGc(long gcId, long used, long committed) {}

    private static final int MAX_PENDING_GC_IDS = 4096;
    // Exact allocation sites observed in A220's retained JFR views:
    // GlobalVariable$GlobalCodeRefMap$1$1.next(), Field.copy(), and
    // Class.copyFields(). Prefix matching is intentionally forbidden.
    private static final String GLOBAL_CODE_NEXT_OWNER =
            "org.perlonjava.runtime.runtimetypes.GlobalVariable$GlobalCodeRefMap$1$1";

    private static final class Metrics {
        long allocation;
        long rootReflectiveAllocation;
        long peakCommitted;
        long finalOldGcLive;
        long nmtCommitted;
        long nmtReserved;
        long dataLoss;
        long youngGcCount;
        long oldGcCount;
        long totalGcPauseNanos;
        long maxGcPauseNanos;
        boolean postOldGc;
        boolean nmtObserved;
        final Map<Long, String> pendingCollections = new HashMap<>();
        final Map<Long, HeapAfterGc> pendingHeap = new HashMap<>();
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length == 1 && argv[0].equals("--self-test")) {
            selfTest();
            return;
        }
        Map<String, Path> args = arguments(argv);
        verifyRuntime(args.get("jdk-executable"));
        Metrics metrics = readRecording(args.get("recording"));
        System.out.print(render(metrics, args));
    }

    private static void selfTest() {
        require(rootOrReflectiveOwner(GLOBAL_CODE_NEXT_OWNER, "next"),
                "exact GlobalCodeRefMap iterator site");
        require(rootOrReflectiveOwner("java.lang.reflect.Field", "copy"),
                "JFR Field.copy allocation site");
        require(rootOrReflectiveOwner("java.lang.Class", "copyFields"),
                "JFR Class.copyFields allocation site");
        require(!rootOrReflectiveOwner(
                        "org.perlonjava.runtime.runtimetypes.GlobalVariable$GlobalCodeRefMapLike$1$1",
                        "next"), "similarly named GlobalCodeRefMap class");
        require(!rootOrReflectiveOwner(GLOBAL_CODE_NEXT_OWNER + "0", "next"),
                "similarly named iterator owner");
        require(!rootOrReflectiveOwner("java.lang.reflect.Field", "get"),
                "unrelated reflective method");
        require(!rootOrReflectiveOwner("example.java.lang.Class", "copyFields"),
                "similarly named Class owner");
        boolean overflowRejected = false;
        try {
            Math.addExact(Long.MAX_VALUE, 1);
        } catch (ArithmeticException expected) {
            overflowRejected = true;
        }
        require(overflowRejected, "checked accumulator overflow");
        Metrics pending = new Metrics();
        for (long id = 0; id <= MAX_PENDING_GC_IDS; id++) {
            pending.pendingCollections.put(id, "fixture");
        }
        boolean boundRejected = false;
        try {
            checkPendingBound(pending);
        } catch (IllegalStateException expected) {
            boundRejected = true;
        }
        require(boundRejected, "pending GC state bound");
        System.out.println("PHASE36_JFR_METRICS_SELF_TEST ok");
    }

    private static void require(boolean value, String label) {
        if (!value) {
            throw new AssertionError("classifier self-test failed: " + label);
        }
    }

    private static void verifyRuntime(Path sealedExecutable) throws Exception {
        String command = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("JDK command identity unavailable"));
        Path runningExecutable = Path.of(command).toRealPath();
        if (!sha256(runningExecutable).equals(sha256(sealedExecutable))) {
            throw new IllegalStateException("running JDK executable differs from sealed identity");
        }
    }

    private static Map<String, Path> arguments(String[] argv) {
        if (argv.length != 14) {
            throw new IllegalArgumentException("expected seven --name path arguments");
        }
        Map<String, Path> result = new HashMap<>();
        for (int index = 0; index < argv.length; index += 2) {
            String name = argv[index];
            if (!name.startsWith("--") || name.length() < 3) {
                throw new IllegalArgumentException("invalid option: " + name);
            }
            Path value = Path.of(argv[index + 1]).toAbsolutePath().normalize();
            if (result.put(name.substring(2), value) != null || !Files.isRegularFile(value)) {
                throw new IllegalArgumentException("missing, duplicate, or non-file option: " + name);
            }
        }
        for (String required : List.of("recording", "command", "jfr-tool",
                "jdk-executable", "jdk-version-log", "jfc", "helper")) {
            if (!result.containsKey(required)) {
                throw new IllegalArgumentException("missing --" + required);
            }
        }
        return result;
    }

    private static Metrics readRecording(Path recording) throws IOException {
        Metrics metrics = new Metrics();
        try (RecordingFile input = new RecordingFile(recording)) {
            while (input.hasMoreEvents()) {
                RecordedEvent event = input.readEvent();
                String type = event.getEventType().getName();
                switch (type) {
                    case "jdk.DataLoss" -> metrics.dataLoss = Math.addExact(
                            metrics.dataLoss, requiredNonNegative(event, "amount"));
                    case "jdk.GarbageCollection" -> recordCollection(metrics, event);
                    case "jdk.GCHeapSummary" -> recordHeapSummary(metrics, event);
                    case "jdk.ObjectAllocationSample" -> recordAllocation(metrics, event);
                    case "jdk.NativeMemoryUsageTotal" -> recordNativeMemory(metrics, event);
                    default -> { }
                }
            }
        }
        return metrics;
    }

    private static void recordCollection(Metrics metrics, RecordedEvent event) {
        long gcId = requiredNonNegative(event, "gcId");
        String name = stringValue(event, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("GarbageCollection.name is missing");
        }
        long duration = event.getDuration().toNanos();
        if (duration < 0) {
            throw new IllegalArgumentException("GarbageCollection duration is negative");
        }
        metrics.totalGcPauseNanos = Math.addExact(metrics.totalGcPauseNanos, duration);
        metrics.maxGcPauseNanos = Math.max(metrics.maxGcPauseNanos, duration);
        if (oldCollection(name)) {
            metrics.oldGcCount = Math.addExact(metrics.oldGcCount, 1);
        } else {
            metrics.youngGcCount = Math.addExact(metrics.youngGcCount, 1);
        }
        HeapAfterGc heap = metrics.pendingHeap.remove(gcId);
        if (heap != null) {
            applyOldGc(metrics, name, heap);
            return;
        }
        metrics.pendingCollections.put(gcId, name);
        checkPendingBound(metrics);
    }

    private static void recordHeapSummary(Metrics metrics, RecordedEvent event) {
        long committed = requiredNestedNonNegative(event, "heapSpace", "committedSize");
        metrics.peakCommitted = Math.max(metrics.peakCommitted, committed);
        if ("After GC".equals(stringValue(event, "when"))) {
            long gcId = requiredNonNegative(event, "gcId");
            HeapAfterGc heap = new HeapAfterGc(gcId,
                    requiredNonNegative(event, "heapUsed"), committed);
            String name = metrics.pendingCollections.remove(gcId);
            if (name != null) {
                applyOldGc(metrics, name, heap);
            } else {
                metrics.pendingHeap.put(gcId, heap);
                checkPendingBound(metrics);
            }
        }
    }

    private static void recordAllocation(Metrics metrics, RecordedEvent event) {
        long weight = requiredNonNegative(event, "weight");
        metrics.allocation = Math.addExact(metrics.allocation, weight);
        if (rootOrReflective(event.getStackTrace())) {
            metrics.rootReflectiveAllocation = Math.addExact(
                    metrics.rootReflectiveAllocation, weight);
        }
    }

    private static void recordNativeMemory(Metrics metrics, RecordedEvent event) {
        metrics.nmtObserved = true;
        metrics.nmtCommitted = Math.max(metrics.nmtCommitted,
                requiredNonNegative(event, "committed"));
        metrics.nmtReserved = Math.max(metrics.nmtReserved,
                requiredNonNegative(event, "reserved"));
    }

    private static void applyOldGc(Metrics metrics, String name, HeapAfterGc heap) {
        if (oldCollection(name)) {
            metrics.finalOldGcLive = heap.used();
            metrics.postOldGc = true;
        }
    }

    private static boolean oldCollection(String name) {
        return name.matches("(?i).*(old|full|mixed).*");
    }

    private static void checkPendingBound(Metrics metrics) {
        if (metrics.pendingCollections.size() + metrics.pendingHeap.size()
                > MAX_PENDING_GC_IDS) {
            throw new IllegalStateException("unpaired GC event bound exceeded");
        }
    }

    private static boolean rootOrReflective(RecordedStackTrace trace) {
        if (trace == null) {
            return false;
        }
        for (RecordedFrame frame : trace.getFrames()) {
            RecordedMethod method = frame.getMethod();
            if (method == null || method.getType() == null) {
                continue;
            }
            String owner = method.getType().getName();
            String name = method.getName();
            if (rootOrReflectiveOwner(owner, name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean rootOrReflectiveOwner(String owner, String method) {
        return (owner.equals(GLOBAL_CODE_NEXT_OWNER) && method.equals("next"))
                || (owner.equals("java.lang.reflect.Field") && method.equals("copy"))
                || (owner.equals("java.lang.Class") && method.equals("copyFields"));
    }

    private static long nestedLong(RecordedEvent event, String outer, String inner,
            long fallback) {
        if (event.getEventType().getField(outer) == null) {
            return fallback;
        }
        Object value = event.getValue(outer);
        if (!(value instanceof RecordedObject object)
                || object.getFields().stream().noneMatch(field -> field.getName().equals(inner))) {
            return fallback;
        }
        Object nested = object.getValue(inner);
        return nested instanceof Number number ? number.longValue() : fallback;
    }

    private static long longValue(RecordedEvent event, String field, long fallback) {
        if (event.getEventType().getField(field) == null) {
            return fallback;
        }
        Object value = event.getValue(field);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static long requiredNonNegative(RecordedEvent event, String field) {
        long value = longValue(event, field, -1);
        if (value < 0) {
            throw new IllegalArgumentException(event.getEventType().getName()
                    + "." + field + " is missing or negative");
        }
        return value;
    }

    private static long requiredNestedNonNegative(RecordedEvent event, String outer,
            String inner) {
        long value = nestedLong(event, outer, inner, -1);
        if (value < 0) {
            throw new IllegalArgumentException(event.getEventType().getName()
                    + "." + outer + "." + inner + " is missing or negative");
        }
        return value;
    }

    private static String stringValue(RecordedEvent event, String field) {
        if (event.getEventType().getField(field) == null) {
            return "";
        }
        Object value = event.getValue(field);
        return value == null ? "" : value.toString();
    }

    private static String render(Metrics metrics, Map<String, Path> args)
            throws Exception {
        return "{\n"
                + "  \"complete\": true,\n"
                + "  \"identity\": {\n"
                + identity("command_sha256", args.get("command"))
                + identity("jdk_executable_sha256", args.get("jdk-executable"))
                + identity("jdk_version_log_sha256", args.get("jdk-version-log"))
                + identity("jfc_sha256", args.get("jfc"))
                + identity("jfr_recording_sha256", args.get("recording"))
                + identity("jfr_tool_sha256", args.get("jfr-tool"))
                + "    \"producer_sha256\": \"" + sha256(args.get("helper")) + "\"\n"
                + "  },\n"
                + "  \"metrics\": {\n"
                + metric("data_loss_events", metrics.dataLoss)
                + metric("final_live_heap_bytes", metrics.finalOldGcLive)
                + metric("max_gc_pause_nanos", metrics.maxGcPauseNanos)
                + metric("nmt_committed_bytes", metrics.nmtCommitted)
                + metric("nmt_reserved_bytes", metrics.nmtReserved)
                + metric("old_gc_count", metrics.oldGcCount)
                + metric("peak_committed_heap_bytes", metrics.peakCommitted)
                + metric("root_reflective_allocation_bytes", metrics.rootReflectiveAllocation)
                + metric("total_gc_pause_nanos", metrics.totalGcPauseNanos)
                + metric("total_allocation_bytes", metrics.allocation)
                + "    \"young_gc_count\": " + metrics.youngGcCount + "\n"
                + "  },\n"
                + "  \"nmt_status\": \"" + (metrics.nmtObserved ? "supported" : "unsupported") + "\",\n"
                + "  \"post_old_gc_observed\": " + metrics.postOldGc + ",\n"
                + "  \"schema_version\": 1,\n"
                + "  \"truncated\": false\n"
                + "}\n";
    }

    private static String identity(String name, Path path) throws Exception {
        return "    \"" + name + "\": \"" + sha256(path) + "\",\n";
    }

    private static String metric(String name, long value) {
        return "    \"" + name + "\": " + value + ",\n";
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count != 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

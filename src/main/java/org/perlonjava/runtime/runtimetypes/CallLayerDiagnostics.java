package org.perlonjava.runtime.runtimetypes;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opt-in attribution for the general Perl subroutine call boundary.
 *
 * <p>This is intentionally controlled by a JVM property, rather than a Perl
 * option: the collector changes both timing and allocation behaviour and must
 * never be enabled for normal benchmarks.  When enabled, nested invocations
 * are accounted with a per-thread stack.  Each reported category therefore
 * has inclusive and exclusive wall-clock nanoseconds and allocated bytes per
 * operation.  The phase-3 benchmark runner writes the compact JSON result and
 * removes any larger profiler artefacts after extracting its evidence.</p>
 */
final class CallLayerDiagnostics {
    static final boolean ENABLED = Boolean.getBoolean("perlonjava.callLayerDiagnostics");
    /**
     * Splits the normal call-path categories by callee name. This is a
     * diagnostic-only cardinality increase and is deliberately separate from
     * {@link #ENABLED} so existing aggregate reports remain comparable.
     */
    static final boolean BY_CODE = Boolean.getBoolean("perlonjava.callLayerDiagnosticsByCode");
    private static final String OUTPUT = System.getProperty("perlonjava.callLayerDiagnosticsOutput");
    private static final ThreadMXBean ALLOCATION_BEAN = allocationBean();
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private static final Map<String, Totals> TOTALS = new LinkedHashMap<>();

    static {
        if (ENABLED && OUTPUT != null && !OUTPUT.isBlank()) {
            Runtime.getRuntime().addShutdownHook(new Thread(CallLayerDiagnostics::writeReport,
                    "perlonjava-call-layer-diagnostics"));
        }
    }

    private CallLayerDiagnostics() { }

    static Token enter(String category) {
        if (!ENABLED) return null;
        Token parent = CURRENT.get();
        Token token = new Token(category, parent, System.nanoTime(), allocatedBytes());
        CURRENT.set(token);
        return token;
    }

    static void markDispatch(Token token) {
        if (token != null) token.dispatchNanos = System.nanoTime();
    }

    static void markBodyComplete(Token token) {
        if (token != null) token.bodyCompleteNanos = System.nanoTime();
    }

    static void exit(Token token) {
        if (token == null) return;
        long endNanos = System.nanoTime();
        long endBytes = allocatedBytes();
        CURRENT.set(token.parent);
        long inclusiveNanos = Math.max(0, endNanos - token.startNanos);
        long inclusiveBytes = Math.max(0, endBytes - token.startBytes);
        long exclusiveNanos = Math.max(0, inclusiveNanos - token.childNanos);
        long exclusiveBytes = Math.max(0, inclusiveBytes - token.childBytes);
        synchronized (TOTALS) {
            Totals totals = TOTALS.computeIfAbsent(token.category, ignored -> new Totals());
            totals.operations++;
            totals.inclusiveNanos += inclusiveNanos;
            totals.exclusiveNanos += exclusiveNanos;
            totals.inclusiveBytes += inclusiveBytes;
            totals.exclusiveBytes += exclusiveBytes;
            if (token.dispatchNanos != 0) totals.setupNanos += token.dispatchNanos - token.startNanos;
            if (token.dispatchNanos != 0 && token.bodyCompleteNanos != 0) {
                totals.bodyNanos += token.bodyCompleteNanos - token.dispatchNanos;
            }
            if (token.parent != null) {
                token.parent.childNanos += inclusiveNanos;
                token.parent.childBytes += inclusiveBytes;
            }
        }
    }

    private static ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof ThreadMXBean allocationBean && allocationBean.isThreadAllocatedMemorySupported()) {
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) allocationBean.setThreadAllocatedMemoryEnabled(true);
            return allocationBean;
        }
        return null;
    }

    private static long allocatedBytes() {
        return ALLOCATION_BEAN == null ? 0 : ALLOCATION_BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static void writeReport() {
        StringBuilder json = new StringBuilder("{\n  \"kind\": \"perlonjava-call-layer-diagnostics\",\n  \"categories\": {");
        synchronized (TOTALS) {
            boolean first = true;
            for (Map.Entry<String, Totals> entry : TOTALS.entrySet()) {
                if (!first) json.append(',');
                first = false;
                Totals value = entry.getValue();
                double operations = Math.max(1, value.operations);
                json.append("\n    \"").append(entry.getKey()).append("\": {")
                        .append("\"operations\": ").append(value.operations)
                        .append(", \"inclusive_nanoseconds_per_operation\": ").append(value.inclusiveNanos / operations)
                        .append(", \"exclusive_nanoseconds_per_operation\": ").append(value.exclusiveNanos / operations)
                        .append(", \"inclusive_allocated_bytes_per_operation\": ").append(value.inclusiveBytes / operations)
                        .append(", \"exclusive_allocated_bytes_per_operation\": ").append(value.exclusiveBytes / operations)
                        .append(", \"setup_nanoseconds_per_operation\": ").append(value.setupNanos / operations)
                        .append(", \"body_nanoseconds_per_operation\": ").append(value.bodyNanos / operations)
                        .append('}');
            }
        }
        json.append("\n  }\n}\n");
        try {
            Files.writeString(Path.of(OUTPUT), json);
        } catch (IOException e) {
            System.err.println("cannot write call-layer diagnostics: " + e.getMessage());
        }
    }

    static final class Token {
        final String category; final Token parent; final long startNanos; final long startBytes;
        long dispatchNanos; long bodyCompleteNanos; long childNanos; long childBytes;
        Token(String category, Token parent, long startNanos, long startBytes) {
            this.category = category; this.parent = parent; this.startNanos = startNanos; this.startBytes = startBytes;
        }
    }

    private static final class Totals {
        long operations, inclusiveNanos, exclusiveNanos, inclusiveBytes, exclusiveBytes, setupNanos, bodyNanos;
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in lifecycle counters for private one-scalar {@link RuntimeList} results.
 *
 * <p>The counters identify whether scalar-context callers actually return the
 * wrappers produced by {@link RuntimeScalar#getList()} to the runtime-local
 * pool. They deliberately collect no timing or allocation data and are absent
 * from ordinary execution unless both the enable and output properties are
 * supplied.</p>
 */
final class ScalarResultDiagnostics {
    static final boolean ENABLED = Boolean.getBoolean("perlonjava.scalarResultDiagnostics");
    private static final String OUTPUT = System.getProperty("perlonjava.scalarResultDiagnosticsOutput");

    private static final LongAdder ACQUIRE_POOL_HIT = new LongAdder();
    private static final LongAdder ACQUIRE_POOL_MISS = new LongAdder();
    private static final LongAdder SCALAR_EXTRACTION = new LongAdder();
    private static final LongAdder RECYCLED = new LongAdder();
    private static final LongAdder REJECTED_ORDINARY_LIST = new LongAdder();
    private static final LongAdder REJECTED_MULTI_ELEMENT = new LongAdder();

    static {
        if (ENABLED && OUTPUT != null && !OUTPUT.isBlank()) {
            Runtime.getRuntime().addShutdownHook(new Thread(ScalarResultDiagnostics::writeReport,
                    "perlonjava-scalar-result-diagnostics"));
        }
    }

    private ScalarResultDiagnostics() { }

    static void acquired(boolean reused) {
        if (!ENABLED) return;
        (reused ? ACQUIRE_POOL_HIT : ACQUIRE_POOL_MISS).increment();
    }

    static void scalarExtracted(boolean recyclable, int size) {
        if (!ENABLED) return;
        SCALAR_EXTRACTION.increment();
        if (!recyclable) REJECTED_ORDINARY_LIST.increment();
        else if (size != 1) REJECTED_MULTI_ELEMENT.increment();
    }

    static void recycled() {
        if (ENABLED) RECYCLED.increment();
    }

    private static void writeReport() {
        String json = "{\n"
                + "  \"kind\": \"perlonjava-scalar-result-diagnostics\",\n"
                + "  \"acquire_pool_hit\": " + ACQUIRE_POOL_HIT.sum() + ",\n"
                + "  \"acquire_pool_miss\": " + ACQUIRE_POOL_MISS.sum() + ",\n"
                + "  \"scalar_extraction\": " + SCALAR_EXTRACTION.sum() + ",\n"
                + "  \"recycled\": " + RECYCLED.sum() + ",\n"
                + "  \"rejected_ordinary_list\": " + REJECTED_ORDINARY_LIST.sum() + ",\n"
                + "  \"rejected_multi_element\": " + REJECTED_MULTI_ELEMENT.sum() + "\n"
                + "}\n";
        try {
            Files.writeString(Path.of(OUTPUT), json);
        } catch (IOException e) {
            System.err.println("cannot write scalar-result diagnostics: " + e.getMessage());
        }
    }
}

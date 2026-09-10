package org.perlonjava.backend.bytecode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Opt-in opcode-frequency attribution for the bytecode interpreter.
 *
 * <p>The collector is deliberately disabled in normal runs. When enabled with
 * {@code -Dperlonjava.bytecodeOpcodeDiagnostics=true}, every interpreter
 * dispatch increments a thread-confined counter. Supplying
 * {@code -Dperlonjava.bytecodeOpcodeDiagnosticsOutput=FILE} writes a compact
 * JSON report at JVM shutdown. This is diagnostic instrumentation only: its
 * cost makes it unsuitable for throughput measurements.</p>
 */
final class BytecodeOpcodeDiagnostics {
    static final boolean ENABLED = Boolean.getBoolean("perlonjava.bytecodeOpcodeDiagnostics");
    private static final String OUTPUT = System.getProperty("perlonjava.bytecodeOpcodeDiagnosticsOutput");
    private static final int MAX_OPCODE = 553;
    private static final ConcurrentLinkedQueue<long[]> ALL_COUNTERS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<long[]> COUNTERS = ThreadLocal.withInitial(() -> {
        long[] counters = new long[MAX_OPCODE + 1];
        ALL_COUNTERS.add(counters);
        return counters;
    });
    private static final String[] NAMES = opcodeNames();

    static {
        if (ENABLED && OUTPUT != null && !OUTPUT.isBlank()) {
            Runtime.getRuntime().addShutdownHook(new Thread(BytecodeOpcodeDiagnostics::writeReport,
                    "perlonjava-bytecode-opcode-diagnostics"));
        }
    }

    private BytecodeOpcodeDiagnostics() { }

    static void record(int opcode) {
        if (opcode >= 0 && opcode <= MAX_OPCODE) {
            COUNTERS.get()[opcode]++;
        }
    }

    private static String[] opcodeNames() {
        String[] names = new String[MAX_OPCODE + 1];
        for (Field field : Opcodes.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || (field.getType() != short.class && field.getType() != int.class)) {
                continue;
            }
            try {
                int value = field.getType() == short.class ? field.getShort(null) : field.getInt(null);
                if (value >= 0 && value <= MAX_OPCODE) {
                    names[value] = field.getName();
                }
            } catch (IllegalAccessException ignored) {
                // Public opcode constants are expected; omit an inaccessible name.
            }
        }
        return names;
    }

    private static void writeReport() {
        long[] totals = new long[MAX_OPCODE + 1];
        for (long[] counters : ALL_COUNTERS) {
            for (int opcode = 0; opcode <= MAX_OPCODE; opcode++) {
                totals[opcode] += counters[opcode];
            }
        }
        List<Integer> used = new ArrayList<>();
        for (int opcode = 0; opcode <= MAX_OPCODE; opcode++) {
            if (totals[opcode] != 0) used.add(opcode);
        }
        used.sort(Comparator.comparingLong((Integer opcode) -> totals[opcode]).reversed());
        StringBuilder json = new StringBuilder("{\n  \"kind\": \"perlonjava-bytecode-opcode-diagnostics\",\n  \"opcodes\": [");
        boolean first = true;
        for (int opcode : used) {
            if (!first) json.append(',');
            first = false;
            String name = NAMES[opcode] == null ? "UNKNOWN" : NAMES[opcode];
            json.append("\n    {\"opcode\": ").append(opcode)
                    .append(", \"name\": \"").append(name)
                    .append("\", \"count\": ").append(totals[opcode]).append('}');
        }
        json.append("\n  ]\n}\n");
        try {
            Files.writeString(Path.of(OUTPUT), json);
        } catch (IOException e) {
            System.err.println("cannot write bytecode opcode diagnostics: " + e.getMessage());
        }
    }
}

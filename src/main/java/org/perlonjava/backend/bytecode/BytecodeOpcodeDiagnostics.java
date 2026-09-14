package org.perlonjava.backend.bytecode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private static final ConcurrentLinkedQueue<ThreadCounters> ALL_COUNTERS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<ThreadCounters> COUNTERS = ThreadLocal.withInitial(() -> {
        ThreadCounters counters = new ThreadCounters();
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

    static void record(InterpretedCode code, int opcode) {
        if (opcode >= 0 && opcode <= MAX_OPCODE) {
            ThreadCounters counters = COUNTERS.get();
            counters.total[opcode]++;
            counters.byCode.computeIfAbsent(code, ignored -> new long[MAX_OPCODE + 1])[opcode]++;
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
        Map<String, long[]> byCode = new TreeMap<>();
        for (ThreadCounters counters : ALL_COUNTERS) {
            for (int opcode = 0; opcode <= MAX_OPCODE; opcode++) {
                totals[opcode] += counters.total[opcode];
            }
            for (Map.Entry<InterpretedCode, long[]> entry : counters.byCode.entrySet()) {
                long[] aggregate = byCode.computeIfAbsent(codeLabel(entry.getKey()),
                        ignored -> new long[MAX_OPCODE + 1]);
                long[] codeCounters = entry.getValue();
                for (int opcode = 0; opcode <= MAX_OPCODE; opcode++) {
                    aggregate[opcode] += codeCounters[opcode];
                }
            }
        }
        List<Integer> used = new ArrayList<>();
        for (int opcode = 0; opcode <= MAX_OPCODE; opcode++) {
            if (totals[opcode] != 0) used.add(opcode);
        }
        used.sort(Comparator.comparingLong((Integer opcode) -> totals[opcode]).reversed());
        StringBuilder json = new StringBuilder("{\n  \"kind\": \"perlonjava-bytecode-opcode-diagnostics\",\n  \"opcodes\": ");
        appendOpcodes(json, totals, "  ");
        List<Map.Entry<String, long[]>> codes = new ArrayList<>(byCode.entrySet());
        codes.sort(Comparator.comparingLong((Map.Entry<String, long[]> entry) -> total(entry.getValue())).reversed());
        json.append(",\n  \"codes\": [");
        for (int index = 0; index < codes.size(); index++) {
            if (index != 0) json.append(',');
            Map.Entry<String, long[]> code = codes.get(index);
            json.append("\n    {\"code\": \"").append(jsonEscape(code.getKey()))
                    .append("\", \"dispatch_count\": ").append(total(code.getValue()))
                    .append(", \"opcodes\": ");
            appendOpcodes(json, code.getValue(), "    ");
            json.append("\n    }");
        }
        json.append("\n  ]\n}\n");
        try {
            Files.writeString(Path.of(OUTPUT), json);
        } catch (IOException e) {
            System.err.println("cannot write bytecode opcode diagnostics: " + e.getMessage());
        }
    }

    private static void appendOpcodes(StringBuilder json, long[] counts, String indent) {
        List<Integer> used = new ArrayList<>();
        for (int opcode = 0; opcode <= MAX_OPCODE; opcode++) {
            if (counts[opcode] != 0) used.add(opcode);
        }
        used.sort(Comparator.comparingLong((Integer opcode) -> counts[opcode]).reversed());
        json.append('[');
        for (int index = 0; index < used.size(); index++) {
            if (index != 0) json.append(',');
            int opcode = used.get(index);
            String name = NAMES[opcode] == null ? "UNKNOWN" : NAMES[opcode];
            json.append("\n").append(indent).append("  {\"opcode\": ").append(opcode)
                    .append(", \"name\": \"").append(name)
                    .append("\", \"count\": ").append(counts[opcode]).append('}');
        }
        if (!used.isEmpty()) json.append("\n").append(indent);
        json.append(']');
    }

    private static long total(long[] counts) {
        long total = 0;
        for (long count : counts) total += count;
        return total;
    }

    private static String codeLabel(InterpretedCode code) {
        String packageName = code.packageName == null ? "main" : code.packageName;
        String subName = code.subName == null ? "(eval)" : code.subName;
        String source = code.sourceName == null ? "(unknown source)" : code.sourceName;
        return packageName + "::" + subName + " at " + source + ':' + code.sourceLine
                + " (" + code.bytecode.length + " bytecodes)";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final class ThreadCounters {
        private final long[] total = new long[MAX_OPCODE + 1];
        private final Map<InterpretedCode, long[]> byCode = new IdentityHashMap<>();
    }
}

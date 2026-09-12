package org.perlonjava.runtime.runtimetypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in selection counters for the proven immediate-argument-copy lowering. */
final class DirectArgumentCopyDiagnostics {
    static final boolean ENABLED = Boolean.getBoolean("perlonjava.directArgumentCopyDiagnostics");
    private static final String OUTPUT = System.getProperty("perlonjava.directArgumentCopyDiagnosticsOutput");
    private static final LongAdder SELECTED = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();

    static {
        if (ENABLED && OUTPUT != null && !OUTPUT.isBlank()) {
            Runtime.getRuntime().addShutdownHook(new Thread(DirectArgumentCopyDiagnostics::writeReport,
                    "perlonjava-direct-argument-copy-diagnostics"));
        }
    }

    private DirectArgumentCopyDiagnostics() { }

    static void selected() { if (ENABLED) SELECTED.increment(); }
    static void rejected() { if (ENABLED) REJECTED.increment(); }

    private static void writeReport() {
        String json = "{\n"
                + "  \"kind\": \"perlonjava-direct-argument-copy-diagnostics\",\n"
                + "  \"selected\": " + SELECTED.sum() + ",\n"
                + "  \"rejected\": " + REJECTED.sum() + "\n"
                + "}\n";
        try { Files.writeString(Path.of(OUTPUT), json); }
        catch (IOException e) { System.err.println("cannot write direct argument-copy diagnostics: " + e.getMessage()); }
    }
}

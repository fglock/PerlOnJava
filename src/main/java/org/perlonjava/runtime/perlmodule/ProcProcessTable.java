package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.time.Duration;
import java.time.Instant;

/** Portable subset of Proc::ProcessTable backed by Java ProcessHandle. */
public final class ProcProcessTable extends PerlModuleBase {
    public static final String XS_VERSION = "0.637";
    private static final String MODULE = "Proc::ProcessTable";

    public ProcProcessTable() {
        super(MODULE, false);
    }

    public static void initialize() {
        ProcProcessTable module = new ProcProcessTable();
        try {
            module.registerMethod("table", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + MODULE, e);
        }
    }

    public static RuntimeList table(RuntimeArray args, int ctx) {
        RuntimeArray table = new RuntimeArray();
        ProcessHandle.allProcesses().forEach(process -> {
            try {
                RuntimeHash fields = processFields(process);
                RuntimeScalar object = ReferenceOperators.bless(
                        fields.createReference(),
                        new RuntimeScalar("Proc::ProcessTable::Process"));
                RuntimeArray.push(table, object);
            } catch (RuntimeException ignored) {
                // Processes can disappear or become inaccessible while the
                // operating system's process table is being enumerated.
            }
        });
        return table.createReference().getList();
    }

    private static RuntimeHash processFields(ProcessHandle process) {
        ProcessHandle.Info info = process.info();
        RuntimeHash fields = new RuntimeHash();
        fields.put("pid", new RuntimeScalar(process.pid()));
        fields.put("ppid", new RuntimeScalar(process.parent().map(ProcessHandle::pid).orElse(0L)));

        String command = info.command().orElse("");
        String commandLine = info.commandLine().orElseGet(() -> {
            String[] arguments = info.arguments().orElse(new String[0]);
            return arguments.length == 0 ? command : command + " " + String.join(" ", arguments);
        });
        String filename = command;
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (separator >= 0) filename = filename.substring(separator + 1);

        fields.put("fname", new RuntimeScalar(filename));
        fields.put("cmndline", new RuntimeScalar(commandLine));
        fields.put("start", new RuntimeScalar(
                info.startInstant().map(Instant::getEpochSecond).orElse(0L)));
        fields.put("time", new RuntimeScalar(
                info.totalCpuDuration().map(Duration::toMillis).orElse(0L) / 1000.0));
        fields.put("state", new RuntimeScalar(process.isAlive() ? "run" : "defunct"));
        return fields;
    }
}

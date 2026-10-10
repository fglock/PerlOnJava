package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.SystemOperator;
import org.perlonjava.runtime.ForkOpenState;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** JVM-backed argv-safe process execution for PerlOnJava tooling. */
public class PerlOnJavaProcess extends PerlModuleBase {
    static final int MAX_CAPTURE_BYTES_PER_STREAM = 1024 * 1024;

    public PerlOnJavaProcess() {
        super("PerlOnJava::Process", false);
    }

    public static void initialize() {
        PerlOnJavaProcess module = new PerlOnJavaProcess();
        try {
            module.registerMethod("_run", null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static RuntimeList _run(RuntimeArray args, int ctx) {
        if (args.size() < 4) {
            throw new IllegalArgumentException("_run requires timeout, cwd, tee, and argv");
        }

        double timeoutSeconds = args.get(0).getDouble();
        String cwd = args.get(1).toString();
        boolean tee = args.get(2).getBoolean();
        List<String> argv = new ArrayList<>();
        for (int i = 3; i < args.size(); i++) {
            argv.add(args.get(i).toString());
        }

        RuntimeHash result = new RuntimeHash();
        Process process = null;
        ProcessOutputCapture stdout = new ProcessOutputCapture(MAX_CAPTURE_BYTES_PER_STREAM);
        ProcessOutputCapture stderr = new ProcessOutputCapture(MAX_CAPTURE_BYTES_PER_STREAM);
        Thread stdoutReader = null;
        Thread stderrReader = null;
        boolean timedOut = false;
        int exitCode = -1;
        String error = "";

        try {
            ProcessBuilder builder = new ProcessBuilder(resolveChildCommand(argv));
            if (!cwd.isEmpty()) {
                builder.directory(new File(cwd));
            }
            copyPerlEnvironment(builder);
            builder.redirectErrorStream(false);
            process = builder.start();
            process.getOutputStream().close();

            Process activeProcess = process;
            PerlRuntime runtime = PerlRuntime.current();
            stdoutReader = new Thread(() -> {
                try (PerlRuntime.Binding ignored = runtime.bind()) {
                    stdout.copy(activeProcess.getInputStream(), tee
                            ? (buffer, length) -> SystemOperator.writeToPerlStdoutBytes(buffer, length)
                            : null);
                }
            },
                "perlonjava-process-stdout");
            stderrReader = new Thread(() -> {
                try (PerlRuntime.Binding ignored = runtime.bind()) {
                    stderr.copy(activeProcess.getErrorStream(), tee
                            ? (buffer, length) -> SystemOperator.writeToPerlStderrBytes(buffer, length)
                            : null);
                }
            },
                "perlonjava-process-stderr");
            stdoutReader.setDaemon(true);
            stderrReader.setDaemon(true);
            stdoutReader.start();
            stderrReader.start();

            if (timeoutSeconds > 0) {
                long timeoutMillis = Math.max(1L, (long) (timeoutSeconds * 1000));
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    timedOut = true;
                    terminateTree(process);
                }
            } else {
                process.waitFor();
            }
            exitCode = process.isAlive() ? -1 : process.exitValue();
        } catch (IOException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "process wait interrupted";
            if (process != null) {
                terminateTree(process);
            }
        } finally {
            joinReader(stdoutReader);
            joinReader(stderrReader);
        }

        String stdoutText = stdout.text();
        String stderrText = stderr.text();
        if (!stdout.error().isEmpty()) error = appendError(error, "stdout reader: " + stdout.error());
        if (!stderr.error().isEmpty()) error = appendError(error, "stderr reader: " + stderr.error());
        result.put("exit_code", new RuntimeScalar(exitCode));
        result.put("stdout", new RuntimeScalar(stdoutText));
        result.put("stderr", new RuntimeScalar(stderrText));
        // Keep the original API for CPAN tooling callers that only need a
        // combined diagnostic transcript.
        result.put("output", new RuntimeScalar(stdoutText + stderrText));
        result.put("timed_out", new RuntimeScalar(timedOut ? 1 : 0));
        result.put("error", new RuntimeScalar(error));
        result.put("output_truncated", new RuntimeScalar(
                stdout.truncated() || stderr.truncated() ? 1 : 0));
        return result.createReference().getList();
    }

    private static String appendError(String current, String additional) {
        return current.isEmpty() ? additional : current + "; " + additional;
    }

    static List<String> resolveChildCommand(List<String> argv) {
        if (!argv.isEmpty()) {
            String normalized = argv.getFirst().replace('\\', '/');
            int separator = normalized.lastIndexOf('/');
            String executableName = separator >= 0
                    ? normalized.substring(separator + 1) : normalized;
            if ("jperl.bat".equalsIgnoreCase(executableName)) {
                File script = new File(argv.getFirst());
                if (script.isFile() && argv.subList(1, argv.size()).stream()
                        .anyMatch(value -> value.indexOf('\n') >= 0
                                || value.indexOf('\r') >= 0 || value.indexOf('"') >= 0)) {
                    return SystemOperator.buildWindowsBatchCommand(
                            argv, script.getAbsoluteFile().getParentFile());
                }
                List<String> direct = new ArrayList<>(ForkOpenState.currentJavaCommand());
                direct.addAll(argv.subList(1, argv.size()));
                return direct;
            }
        }
        return SystemOperator.resolveCommandForProcessBuilder(argv);
    }

    private static void copyPerlEnvironment(ProcessBuilder builder) {
        Map<String, String> environment = builder.environment();
        environment.clear();
        for (Map.Entry<String, RuntimeScalar> entry
                : GlobalVariable.getGlobalHash("main::ENV").elements.entrySet()) {
            environment.put(entry.getKey(), entry.getValue().toString());
        }
    }

    private static void joinReader(Thread reader) {
        if (reader == null) return;
        try {
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void terminateTree(Process process) {
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .toList();
        } catch (RuntimeException e) {
            // Restricted containers may deny process-table queries. The root
            // process is still terminated below; unrestricted runtimes retain
            // full descendant cleanup.
            descendants = List.of();
        }
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        descendants.stream().filter(ProcessHandle::isAlive)
            .forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}

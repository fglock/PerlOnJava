package org.perlonjava.runtime;

import org.perlonjava.core.Configuration;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Thread-local state for fork-open emulation.
 * 
 * <p>This class manages the state needed to emulate Perl's fork-open pattern
 * ({@code open FH, "-|"}) on the JVM, which doesn't support fork().
 * 
 * <h2>How Fork-Open Emulation Works</h2>
 * 
 * <p>In Perl, {@code my $pid = open FH, "-|"} forks the process:
 * <ul>
 *   <li>Parent gets child's PID, reads from FH (child's stdout)</li>
 *   <li>Child gets 0, typically calls exec to run a command</li>
 * </ul>
 * 
 * <p>Since the JVM can't fork, we emulate this pattern by replaying the
 * current invocation in a child JVM:
 * <ol>
 *   <li>The parent starts the same Perl program with a marker identifying the
 *       no-command open occurrence and returns the child PID.</li>
 *   <li>The child suppresses output while replaying statements that happened
 *       before the fork point.</li>
 *   <li>At the marked open, the child restores stdout, updates {@code $$}, and
 *       returns 0 so arbitrary child-side Perl code can continue.</li>
 * </ol>
 * 
 * <h2>Supported Patterns</h2>
 * <pre>{@code
 * # Classic if/else
 * my $pid = open FH, "-|";
 * if ($pid) { ... } else { exec @cmd }
 * 
 * # The child may exec, or may continue running Perl code.
 * open FH, "-|" or exec @cmd;
 * }</pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>State is stored in ThreadLocal, so each thread has its own pending state.
 * 
 * @see org.perlonjava.runtime.operators.IOOperator#open
 * @see org.perlonjava.runtime.operators.SystemOperator#exec
 */
public class ForkOpenState {
    public static final String REPLAY_ENV = "PERLONJAVA_FORK_OPEN_REPLAY";

    /** Whether this JVM is replaying the program up to a no-command pipe open. */
    public static boolean isReplayProcess() {
        return System.getenv(REPLAY_ENV) != null;
    }

    /** Return the next no-command pipe-open occurrence in this interpreter. */
    public static int nextOccurrence() {
        return ++PerlRuntime.current().forkOpenOccurrence;
    }

    /** Whether this occurrence is the fork point selected for the replay child. */
    public static boolean isReplayChildOccurrence(int occurrence) {
        String target = System.getenv(REPLAY_ENV);
        if (target == null) return false;
        try {
            return Integer.parseInt(target.split(":", 2)[0]) == occurrence;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * During replay, expose the parent's PID until the fork point. Libraries
     * such as Test2 then observe the same PID transition they see after fork.
     */
    public static long initialProcessId() {
        String marker = System.getenv(REPLAY_ENV);
        if (marker != null) {
            String[] parts = marker.split(":", 2);
            if (parts.length == 2) {
                try {
                    return Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                    // Fall through to the real process ID.
                }
            }
        }
        return ProcessHandle.current().pid();
    }

    /**
     * Reconstruct the Java command prefix that launches PerlOnJava, ending at
     * {@code org.perlonjava.app.cli.Main} and excluding the current Perl argv.
     */
    public static List<String> currentJavaCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String[] arguments = info.arguments().orElse(new String[0]);
        return currentJavaCommand(info.command().orElse(null), arguments);
    }

    static List<String> currentJavaCommand(String command, String[] arguments) {
        int mainIndex = Arrays.asList(arguments).indexOf("org.perlonjava.app.cli.Main");
        if (mainIndex >= 0) {
            if (command == null || command.isEmpty()) {
                throw new IllegalStateException("Cannot determine current Java executable");
            }
            List<String> invocation = new ArrayList<>(mainIndex + 2);
            invocation.add(command);
            invocation.addAll(Arrays.asList(arguments).subList(0, mainIndex + 1));
            return invocation;
        }

        // Unit tests and other embedders run PerlOnJava inside their own JVM.
        // Reconstruct a CLI invocation from the active Perl program instead of
        // accidentally re-executing the host (for example, a Gradle worker).
        String javaName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        List<String> invocation = new ArrayList<>();
        invocation.add(new File(new File(System.getProperty("java.home"), "bin"), javaName).getPath());
        invocation.add("--enable-native-access=ALL-UNNAMED");
        invocation.add("-cp");
        invocation.add(embeddedRuntimeClasspath());
        invocation.add("org.perlonjava.app.cli.Main");
        return invocation;
    }

    /**
     * Resolve the product classpath used when an embedded runtime launches a
     * child PerlOnJava CLI. The host classpath may belong to a Gradle worker
     * (and cannot load the CLI in a separately launched JVM), so mirror the
     * checked-in launchers before falling back to it.
     */
    static String embeddedRuntimeClasspath() {
        String exactJar = System.getenv("PERLONJAVA_JAR");
        if (exactJar != null) {
            return exactJar;
        }

        String launcher = System.getenv("PERLONJAVA_EXECUTABLE");
        if (launcher == null || launcher.isEmpty()) {
            return System.getProperty("java.class.path");
        }
        File launcherDirectory = new File(launcher).getAbsoluteFile().getParentFile();
        if (launcherDirectory == null) {
            return System.getProperty("java.class.path");
        }

        File targetJar = new File(launcherDirectory,
                "target/perlonjava-" + Configuration.version + ".jar");
        if (targetJar.isFile() && targetJar.length() > 0) {
            return targetJar.getAbsolutePath();
        }

        File targetClasses = new File(launcherDirectory, "target/classes");
        File mavenClasspath = new File(launcherDirectory, "target/jperl-test-classpath.txt");
        if (targetClasses.isDirectory() && mavenClasspath.isFile()) {
            try {
                String dependencies = Files.readString(mavenClasspath.toPath())
                        .replace("\r", "").replace("\n", "");
                return dependencies.isEmpty()
                        ? targetClasses.getAbsolutePath()
                        : targetClasses.getAbsolutePath() + File.pathSeparator + dependencies;
            } catch (Exception ignored) {
                // Continue through the same packaged-layout fallbacks as jperl.
            }
        }

        File adjacentJar = new File(launcherDirectory,
                "perlonjava-" + Configuration.version + ".jar");
        if (adjacentJar.isFile() && adjacentJar.length() > 0) {
            return adjacentJar.getAbsolutePath();
        }
        File installedJar = new File(launcherDirectory,
                "../lib/perlonjava-" + Configuration.version + ".jar");
        return installedJar.isFile() && installedJar.length() > 0
                ? installedJar.getAbsolutePath()
                : System.getProperty("java.class.path");
    }

    /** Reconstruct the current JVM invocation so a fork-open child can replay it. */
    public static List<String> currentInvocation() {
        List<String> invocation = new ArrayList<>(currentJavaCommand());
        for (RuntimeBase include : GlobalVariable.getGlobalArray("main::INC").elements) {
            if (include instanceof RuntimeScalar scalar
                    && !RuntimeScalarType.isReference(scalar)) {
                invocation.add("-I" + include.toString());
            }
        }
        String program = GlobalVariable.getGlobalVariable("main::0").toString();
        if (program == null || program.isEmpty() || "-e".equals(program)) {
            throw new IllegalStateException("no-command fork-open replay requires a script file");
        }
        File programFile = new File(program);
        if (!programFile.isFile()) {
            URL resource = ForkOpenState.class.getClassLoader().getResource(program);
            if (resource != null && "file".equals(resource.getProtocol())) {
                try {
                    program = new File(resource.toURI()).getPath();
                } catch (Exception ignored) {
                    // Keep the original name so the child reports the useful error.
                }
            }
        }
        invocation.add(program);
        for (RuntimeBase argument : GlobalVariable.getGlobalArray("main::ARGV").elements) {
            invocation.add(argument.toString());
        }
        return invocation;
    }
    
    /**
     * Thread-local storage for pending fork-open state.
     */
    /**
     * Represents a pending fork-open operation waiting for exec to complete it.
     */
    public static class PendingForkOpen {
        /** The filehandle scalar that will receive the pipe */
        public final RuntimeScalar fileHandle;
        
        /** Token index for error messages */
        public final int tokenIndex;
        
        /** I/O layers to apply (e.g., ":utf8") */
        public final String ioLayers;

        /** The Perl subroutine that initiated the fork-open operation. */
        public final RuntimeCode boundaryCode;
        
        public PendingForkOpen(RuntimeScalar fileHandle, int tokenIndex, String ioLayers,
                               RuntimeCode boundaryCode) {
            this.fileHandle = fileHandle;
            this.tokenIndex = tokenIndex;
            this.ioLayers = ioLayers != null ? ioLayers : "";
            this.boundaryCode = boundaryCode;
        }
    }
    
    /**
     * Sets a pending fork-open state.
     * 
     * <p>Called by {@code open FH, "-|"} when no command is provided (fork mode).
     * The state will be consumed by the next {@code exec} call.
     * 
     * @param fileHandle The filehandle scalar to set up when exec is called
     * @param tokenIndex Token index for error reporting
     * @param ioLayers Optional I/O layers (e.g., ":utf8")
     */
    public static void setPending(RuntimeScalar fileHandle, int tokenIndex, String ioLayers) {
        PerlRuntime.current().pendingForkOpen = new PendingForkOpen(fileHandle, tokenIndex, ioLayers,
                RuntimeCode.getActiveCodeAt(0));
    }
    
    /**
     * Gets the current pending fork-open state.
     * 
     * @return The pending state, or null if none
     */
    public static PendingForkOpen getPending() {
        return PerlRuntime.current().pendingForkOpen;
    }
    
    /**
     * Clears any pending fork-open state.
     * 
     * <p>Called by:
     * <ul>
     *   <li>{@code open} - at the start of any open operation</li>
     *   <li>{@code close} - when closing filehandles</li>
     *   <li>{@code exec} - after successfully completing a fork-open</li>
     *   <li>Error handlers - to prevent stale state</li>
     * </ul>
     */
    public static void clear() {
        PerlRuntime.current().pendingForkOpen = null;
    }
    
    /**
     * Checks if there's a pending fork-open waiting for exec.
     * 
     * @return true if a fork-open is pending
     */
    public static boolean hasPending() {
        return PerlRuntime.current().pendingForkOpen != null;
    }
}

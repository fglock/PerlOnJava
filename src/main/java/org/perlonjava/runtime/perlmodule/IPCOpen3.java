package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.io.ProcessInputHandle;
import org.perlonjava.runtime.io.ProcessOutputHandle;
import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.operators.WaitpidOperator;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;

/**
 * IPC::Open3 - open a process for reading, writing, and error handling
 * <p>
 * This class provides the XS portion of IPC::Open3 using Java's ProcessBuilder
 * instead of fork(), which is not available on the JVM.
 * <p>
 * Loaded via XSLoader from Open3.pm
 */
public class IPCOpen3 extends PerlModuleBase {

    private static final boolean IS_WINDOWS = NativeUtils.IS_WINDOWS;

    /**
     * Constructor for IPCOpen3.
     */
    public IPCOpen3() {
        super("IPC::Open3");
    }

    /**
     * Static initializer called by XSLoader::load().
     */
    public static void initialize() {
        IPCOpen3 module = new IPCOpen3();
        try {
            // Register _open3 and _open2 as the XS implementations
            module.registerMethod("_open3", null);
            module.registerMethod("_open2", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing IPC::Open3 method: " + e.getMessage());
        }
    }

    /**
     * Copies the Perl %ENV hash to the ProcessBuilder environment.
     */
    private static void copyPerlEnvToProcessBuilder(ProcessBuilder processBuilder) {
        Map<String, String> env = processBuilder.environment();
        RuntimeHash perlEnv = GlobalVariable.getGlobalHash("main::ENV");
        for (Map.Entry<String, RuntimeScalar> entry : perlEnv.elements.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue().toString();
            env.put(key, value);
        }
    }

    /**
     * Register child process for waitpid() - handles both Windows and POSIX.
     */
    private static void registerChildProcess(Process process) {
        long pid = process.pid();
        if (IS_WINDOWS) {
            WaitpidOperator.registerChildProcess(pid, process);
        } else {
            RuntimeIO.registerChildProcess(process);
        }
    }

    /**
     * XS implementation of open3.
     * <p>
     * Arguments: ($wtr, $rdr, $err, @cmd)
     * - $wtr: handle for writing to child's stdin (output parameter)
     * - $rdr: handle for reading from child's stdout (output parameter)
     * - $err: handle for reading from child's stderr (output parameter, can be undef)
     * - @cmd: command and arguments to execute
     * <p>
     * Returns: PID of the child process
     */
    public static RuntimeList _open3(RuntimeArray args, int ctx) {
        if (args.size() < 4) {
            throw new RuntimeException("Not enough arguments for open3");
        }

        // Extract handles (these are references we need to modify)
        RuntimeScalar wtrRef = args.get(0);
        RuntimeScalar rdrRef = args.get(1);
        RuntimeScalar errRef = args.get(2);

        // Extract command - remaining arguments
        List<String> commandList = new ArrayList<>();
        for (int i = 3; i < args.size(); i++) {
            commandList.add(args.get(i).toString());
        }

        if (commandList.isEmpty()) {
            throw new RuntimeException("open3: no command specified");
        }

        try {
            // Build the command
            String[] command;
            if (commandList.size() == 1) {
                // Single string - use shell
                String cmd = commandList.get(0);
                if (IS_WINDOWS) {
                    command = new String[]{"cmd.exe", "/c", cmd};
                } else {
                    command = new String[]{"/bin/sh", "-c", cmd};
                }
            } else {
                // Multiple arguments - direct execution
                command = commandList.toArray(new String[0]);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));

            // Copy %ENV to the subprocess
            copyPerlEnvToProcessBuilder(processBuilder);

            // Check if stderr should be merged with stdout
            boolean mergeStderr = !errRef.getDefinedBoolean() ||
                    (rdrRef.type == RuntimeScalarType.REFERENCE &&
                            errRef.type == RuntimeScalarType.REFERENCE &&
                            rdrRef.value == errRef.value);

            if (mergeStderr) {
                processBuilder.redirectErrorStream(true);
            }

            // Start the process
            Process process = processBuilder.start();
            long pid = process.pid();

            // Register the process for waitpid() - works on both Windows and POSIX
            registerChildProcess(process);

            // Set up the write handle (to child's stdin)
            setupWriteHandle(wtrRef, process.getOutputStream());

            // Set up the read handle (from child's stdout)
            setupReadHandle(rdrRef, process.getInputStream());

            // Set up the error handle (from child's stderr) if not merged
            if (!mergeStderr && errRef.getDefinedBoolean()) {
                setupReadHandle(errRef, process.getErrorStream());
            }

            return new RuntimeScalar(pid).getList();

        } catch (Exception e) {
            getGlobalVariable("main::!").set(e.getMessage());
            throw new RuntimeException("open3: " + e.getMessage());
        }
    }

    /**
     * XS implementation of open2.
     * <p>
     * Arguments: ($rdr, $wtr, @cmd)
     * - $rdr: handle for reading from child's stdout (output parameter)
     * - $wtr: handle for writing to child's stdin (output parameter)
     * - @cmd: command and arguments to execute
     * <p>
     * Returns: PID of the child process
     * <p>
     * Note: stderr goes to parent's stderr (inherited)
     */
    public static RuntimeList _open2(RuntimeArray args, int ctx) {
        if (args.size() < 3) {
            throw new RuntimeException("Not enough arguments for open2");
        }

        // Extract handles (these are references we need to modify)
        RuntimeScalar rdrRef = args.get(0);
        RuntimeScalar wtrRef = args.get(1);

        // Extract command - remaining arguments
        List<String> commandList = new ArrayList<>();
        for (int i = 2; i < args.size(); i++) {
            commandList.add(args.get(i).toString());
        }

        if (commandList.isEmpty()) {
            throw new RuntimeException("open2: no command specified");
        }

        try {
            // Build the command
            String[] command;
            if (commandList.size() == 1) {
                // Single string - use shell
                String cmd = commandList.get(0);
                if (IS_WINDOWS) {
                    command = new String[]{"cmd.exe", "/c", cmd};
                } else {
                    command = new String[]{"/bin/sh", "-c", cmd};
                }
            } else {
                // Multiple arguments - direct execution
                command = commandList.toArray(new String[0]);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            String userDir = System.getProperty("user.dir");
            processBuilder.directory(new File(userDir));

            // Copy %ENV to the subprocess
            copyPerlEnvToProcessBuilder(processBuilder);

            // Inherit stderr (goes to parent's stderr)
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

            // Start the process
            Process process = processBuilder.start();
            long pid = process.pid();

            // Register the process for waitpid() - works on both Windows and POSIX
            registerChildProcess(process);

            // Set up the write handle (to child's stdin)
            setupWriteHandle(wtrRef, process.getOutputStream());

            // Set up the read handle (from child's stdout)
            setupReadHandle(rdrRef, process.getInputStream());

            return new RuntimeScalar(pid).getList();

        } catch (Exception e) {
            getGlobalVariable("main::!").set(e.getMessage());
            throw new RuntimeException("open2: " + e.getMessage());
        }
    }

    /**
     * Sets up a write handle from an OutputStream.
     */
    private static void setupWriteHandle(RuntimeScalar handleRef, OutputStream out) {
        RuntimeIO io = new RuntimeIO();
        io.ioHandle = new ProcessOutputHandle(out);

        // Create a new GLOB reference for the handle
        RuntimeGlob glob = new RuntimeGlob(null);
        glob.setIO(io);

        RuntimeScalar newHandle = new RuntimeScalar();
        newHandle.type = RuntimeScalarType.GLOBREFERENCE;
        newHandle.value = glob;

        // Dereference and set the handle
        if (handleRef.type == RuntimeScalarType.REFERENCE && handleRef.value instanceof RuntimeScalar) {
            ((RuntimeScalar) handleRef.value).set(newHandle);
        } else {
            handleRef.set(newHandle);
        }
    }

    /**
     * Sets up a read handle from an InputStream.
     */
    private static void setupReadHandle(RuntimeScalar handleRef, InputStream in) {
        RuntimeIO io = new RuntimeIO();
        io.ioHandle = new ProcessInputHandle(in);

        // Create a new GLOB reference for the handle
        RuntimeGlob glob = new RuntimeGlob(null);
        glob.setIO(io);

        RuntimeScalar newHandle = new RuntimeScalar();
        newHandle.type = RuntimeScalarType.GLOBREFERENCE;
        newHandle.value = glob;

        // Dereference and set the handle
        if (handleRef.type == RuntimeScalarType.REFERENCE && handleRef.value instanceof RuntimeScalar) {
            ((RuntimeScalar) handleRef.value).set(newHandle);
        } else {
            handleRef.set(newHandle);
        }
    }
}

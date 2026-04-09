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
            // errRef is "usable" if it's defined AND (if it's a reference) the inner value is also defined
            boolean errIsUsable = isUsableHandle(errRef);
            boolean mergeStderr = !errIsUsable ||
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
            // Check for redirection directive like "<&STDIN"
            if (isInputRedirection(wtrRef)) {
                // Input redirection - just close the process stdin
                process.getOutputStream().close();
            } else {
                setupWriteHandle(wtrRef, process.getOutputStream());
            }

            // Set up the read handle (from child's stdout)
            // Check for redirection directive like ">&STDERR"
            boolean rdrIsRedirection = isOutputRedirection(rdrRef);
            if (rdrIsRedirection) {
                // Output redirection - pipe stdout to the named handle
                handleOutputRedirection(rdrRef, process.getInputStream());
            } else {
                setupReadHandle(rdrRef, process.getInputStream(), process);
            }

            // Set up the error handle (from child's stderr) if not merged
            if (!mergeStderr && errIsUsable) {
                if (isOutputRedirection(errRef)) {
                    handleOutputRedirection(errRef, process.getErrorStream());
                } else {
                    setupReadHandle(errRef, process.getErrorStream(), process);
                }
            }

            return new RuntimeScalar(pid).getList();

        } catch (Exception e) {
            getGlobalVariable("main::!").set(e.getMessage());
            throw new RuntimeException("open3: " + e.getMessage());
        }
    }

    /**
     * Check if the handle is an output redirection directive like ">&STDERR"
     */
    private static boolean isOutputRedirection(RuntimeScalar handleRef) {
        // Get the actual string value (may need to dereference)
        String str = getStringValue(handleRef);
        return str != null && str.startsWith(">&");
    }

    /**
     * Check if the handle is an input redirection directive like "<&STDIN"
     */
    private static boolean isInputRedirection(RuntimeScalar handleRef) {
        // Get the actual string value (may need to dereference)
        String str = getStringValue(handleRef);
        return str != null && str.startsWith("<&");
    }

    /**
     * Check if a handle parameter is usable (not false or a reference to a false value).
     * Per IPC::Open3 docs: "If CHLD_ERR is false, or the same file descriptor as
     * CHLD_OUT, then STDOUT and STDERR of the child are on the same filehandle."
     * A false value includes undef, "", and 0.
     */
    private static boolean isUsableHandle(RuntimeScalar handleRef) {
        if (!handleRef.getDefinedBoolean()) {
            return false;
        }
        // If it's a reference, check if the inner value is true (not just defined)
        if (handleRef.type == RuntimeScalarType.REFERENCE && handleRef.value instanceof RuntimeScalar) {
            RuntimeScalar inner = (RuntimeScalar) handleRef.value;
            return inner.getBoolean();
        }
        return true;
    }

    /**
     * Get the string value from a scalar, dereferencing if needed
     */
    private static String getStringValue(RuntimeScalar scalar) {
        if (scalar == null) return null;
        
        // If it's a reference, dereference it
        if (scalar.type == RuntimeScalarType.REFERENCE) {
            if (scalar.value instanceof RuntimeScalar) {
                RuntimeScalar inner = (RuntimeScalar) scalar.value;
                // Check if the inner value is a string
                if (inner.type == RuntimeScalarType.STRING) {
                    return inner.toString();
                }
                // Also try getting the string directly
                return inner.toString();
            }
        }
        
        // Direct string type
        if (scalar.type == RuntimeScalarType.STRING) {
            return scalar.toString();
        }
        
        // Try toString and check if it looks like a redirect
        String str = scalar.toString();
        if (str.startsWith(">&") || str.startsWith("<&")) {
            return str;
        }
        
        return null;
    }

    /**
     * Handle output redirection like ">&STDERR" - pipe input stream to the named handle
     */
    private static void handleOutputRedirection(RuntimeScalar handleRef, InputStream in) {
        String directive = handleRef.toString();
        String handleName = directive.substring(2);  // Remove ">&"

        // Get the named handle
        RuntimeIO targetIO = null;
        if (handleName.equals("STDERR")) {
            targetIO = getGlobalVariable("main::STDERR").getRuntimeIO();
        } else if (handleName.equals("STDOUT")) {
            targetIO = getGlobalVariable("main::STDOUT").getRuntimeIO();
        }

        if (targetIO != null && targetIO.ioHandle != null) {
            final RuntimeIO finalTargetIO = targetIO;
            // Start a thread to copy data from process to target handle
            Thread copier = new Thread(() -> {
                try {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        String str = new String(buffer, 0, bytesRead);
                        finalTargetIO.ioHandle.write(str);
                        finalTargetIO.ioHandle.flush();
                    }
                } catch (Exception e) {
                    // Ignore - process may have terminated
                }
            });
            copier.setDaemon(true);
            copier.start();
        } else {
            // Fallback: just discard the stream
            Thread discarder = new Thread(() -> {
                try {
                    byte[] buffer = new byte[4096];
                    while (in.read(buffer) != -1) {
                        // discard
                    }
                } catch (Exception e) {
                    // Ignore
                }
            });
            discarder.setDaemon(true);
            discarder.start();
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
            setupReadHandle(rdrRef, process.getInputStream(), process);

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

        // Dereference to get the inner value
        RuntimeScalar inner;
        if (handleRef.type == RuntimeScalarType.REFERENCE && handleRef.value instanceof RuntimeScalar) {
            inner = (RuntimeScalar) handleRef.value;
        } else {
            inner = handleRef;
        }

        // If the inner value is already a GLOBREFERENCE (e.g., \*FOO typeglob),
        // set the IO slot on the existing glob so the bareword handle works
        if (inner.type == RuntimeScalarType.GLOBREFERENCE && inner.value instanceof RuntimeGlob) {
            ((RuntimeGlob) inner.value).setIO(io);
        } else {
            // Create a new GLOB reference for the handle
            RuntimeGlob glob = new RuntimeGlob(null);
            glob.setIO(io);

            RuntimeScalar newHandle = new RuntimeScalar();
            newHandle.type = RuntimeScalarType.GLOBREFERENCE;
            newHandle.value = glob;

            inner.set(newHandle);
        }
    }

    /**
     * Sets up a read handle from an InputStream.
     */
    private static void setupReadHandle(RuntimeScalar handleRef, InputStream in, Process process) {
        RuntimeIO io = new RuntimeIO();
        io.ioHandle = new ProcessInputHandle(in, process);

        // Dereference to get the inner value
        RuntimeScalar inner;
        if (handleRef.type == RuntimeScalarType.REFERENCE && handleRef.value instanceof RuntimeScalar) {
            inner = (RuntimeScalar) handleRef.value;
        } else {
            inner = handleRef;
        }

        // If the inner value is already a GLOBREFERENCE (e.g., \*FOO typeglob),
        // set the IO slot on the existing glob so the bareword handle works
        if (inner.type == RuntimeScalarType.GLOBREFERENCE && inner.value instanceof RuntimeGlob) {
            ((RuntimeGlob) inner.value).setIO(io);
        } else {
            // Create a new GLOB reference for the handle
            RuntimeGlob glob = new RuntimeGlob(null);
            glob.setIO(io);

            RuntimeScalar newHandle = new RuntimeScalar();
            newHandle.type = RuntimeScalarType.GLOBREFERENCE;
            newHandle.value = glob;

            inner.set(newHandle);
        }
    }
}

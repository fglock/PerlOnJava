package org.perlonjava.nativ;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import org.perlonjava.runtime.*;
import org.perlonjava.operators.WaitpidOperator;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Fork implementation for PerlOnJava with Windows emulation support
 */
public class ForkUtils {
    private static final boolean IS_WINDOWS = Platform.isWindows();

    // Process tracking for Windows fork emulation
    private static final AtomicInteger pseudoPidCounter = new AtomicInteger(10000);

    // Fork emulation state
    private static boolean isForkedChild = false;
    private static int parentPid = 0;
    private static int childPid = 0;

    static {
        // Check if we're a forked child process (Windows only)
        String forkFlag = System.getProperty("perlonjava.forked.child");
        if (forkFlag != null && forkFlag.equals("true")) {
            isForkedChild = true;
            parentPid = Integer.parseInt(System.getProperty("perlonjava.parent.pid", "0"));
            childPid = Integer.parseInt(System.getProperty("perlonjava.child.pid", "0"));
        }
    }

    /**
     * Perl fork operator implementation
     * @param ctx Context (unused)
     * @param args Arguments (unused)
     * @return Child PID to parent, 0 to child, or undef on failure
     */
    public static RuntimeScalar fork(int ctx, RuntimeBase... args) {
        // If we're already a forked child on Windows, return 0
        if (IS_WINDOWS && isForkedChild) {
            return new RuntimeScalar(0);
        }

        // Flush all open file handles before forking
        flushAllFileHandles();

        if (IS_WINDOWS) {
            return forkWindows();
        } else {
            return forkPosix();
        }
    }

    /**
     * POSIX fork implementation using native fork() system call
     */
    private static RuntimeScalar forkPosix() {
        try {
            int pid = PosixLibrary.INSTANCE.fork();

            if (pid == -1) {
                // Fork failed
                int errno = Native.getLastError();
                GlobalVariable.getGlobalVariable("main::!").set(PosixLibrary.INSTANCE.strerror(errno));
                return scalarUndef;
            } else if (pid == 0) {
                // We are in the child process
                isForkedChild = true;
                parentPid = PosixLibrary.INSTANCE.getppid();
            }

            return new RuntimeScalar(pid);
        } catch (LastErrorException e) {
            GlobalVariable.getGlobalVariable("main::!").set(e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * Windows fork emulation by creating a new process
     */
    private static RuntimeScalar forkWindows() {
        try {
            // Generate a pseudo PID for the child
            int newChildPid = pseudoPidCounter.getAndIncrement();

            // Get current process information
            String javaHome = System.getProperty("java.home");
            String classpath = System.getProperty("java.class.path");
            String mainClass = getMainClassName();

            if (mainClass == null) {
                throw new RuntimeException("Cannot determine main class for fork");
            }

            // Build command line for child process
            List<String> command = new ArrayList<>();
            command.add(javaHome + File.separator + "bin" + File.separator + "java");

            // Copy JVM arguments
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : jvmArgs) {
                // Skip certain arguments that shouldn't be duplicated
                if (!arg.startsWith("-agentlib") && !arg.startsWith("-javaagent")) {
                    command.add(arg);
                }
            }

            // Add fork emulation flags
            command.add("-Dperlonjava.forked.child=true");
            command.add("-Dperlonjava.parent.pid=" + getCurrentPid());
            command.add("-Dperlonjava.child.pid=" + newChildPid);

            // Copy relevant system properties
            copySystemProperties(command);

            // Add classpath and main class
            command.add("-cp");
            command.add(classpath);
            command.add(mainClass);

            // Copy program arguments
            command.addAll(getProgramArguments());

            // Create process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.inheritIO(); // Inherit file descriptors

            // Copy environment variables
            Map<String, String> env = pb.environment();
            env.putAll(System.getenv());

            // Start child process
            Process childProcess = pb.start();

            // Get the native handle for Windows process tracking
            try {
                // Get the actual Windows PID of the child process
                long childProcessId = getProcessId(childProcess);

                if (childProcessId > 0) {
                    // Open the process to get a handle
                    WinNT.HANDLE handle = Kernel32.INSTANCE.OpenProcess(
                        WinNT.PROCESS_QUERY_INFORMATION | WinNT.SYNCHRONIZE,
                        false,
                        (int) childProcessId
                    );

                    if (handle != null) {
                        // Register with WaitpidOperator for proper waitpid support
                        // Note: We're using our pseudo PID, not the Windows PID
                        WaitpidOperator.registerWindowsChildProcess(newChildPid, handle);
                    }
                }
            } catch (Exception e) {
                // If we can't get the handle, waitpid might not work properly
                System.err.println("Warning: Could not register child process handle: " + e.getMessage());
            }

            // In parent, return child PID
            return new RuntimeScalar(newChildPid);

        } catch (Exception e) {
            GlobalVariable.getGlobalVariable("main::!").set(e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * Get current process PID
     */
    private static int getCurrentPid() {
        if (IS_WINDOWS) {
            if (isForkedChild) {
                return childPid;
            }
            return Kernel32.INSTANCE.GetCurrentProcessId();
        } else {
            return PosixLibrary.INSTANCE.getpid();
        }
    }

    /**
     * Get main class name from stack trace or system property
     */
    private static String getMainClassName() {
        // First try system property
        String mainClass = System.getProperty("sun.java.command");
        if (mainClass != null) {
            // Remove any arguments
            int spaceIndex = mainClass.indexOf(' ');
            if (spaceIndex > 0) {
                mainClass = mainClass.substring(0, spaceIndex);
            }
            return mainClass;
        }

        // Fall back to examining stack trace
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = stack.length - 1; i >= 0; i--) {
            if (stack[i].getMethodName().equals("main")) {
                return stack[i].getClassName();
            }
        }

        return null;
    }

    /**
     * Get program arguments (excluding JVM arguments)
     */
    private static List<String> getProgramArguments() {
        List<String> args = new ArrayList<>();

        // Try to get from RuntimeArray if available
        try {
            RuntimeArray argv = (RuntimeArray) GlobalVariable.getGlobalArray("main::ARGV");
            if (argv != null) {
                for (int i = 0; i < argv.size(); i++) {
                    args.add(argv.get(i).toString());
                }
            }
        } catch (Exception e) {
            // If we can't get ARGV, return empty list
        }

        return args;
    }

    /**
     * Copy relevant system properties to child process
     */
    private static void copySystemProperties(List<String> command) {
        for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
            String key = entry.getKey().toString();
            String value = entry.getValue().toString();

            // Skip certain properties that shouldn't be copied
            if (key.startsWith("java.") ||
                    key.startsWith("sun.") ||
                    key.startsWith("os.") ||
                    key.startsWith("file.") ||
                    key.startsWith("path.") ||
                    key.startsWith("line.") ||
                    key.equals("user.dir") ||
                    key.equals("user.home")) {
                continue;
            }

            // Skip our own fork flags (they're set explicitly)
            if (key.startsWith("perlonjava.")) {
                continue;
            }

            command.add("-D" + key + "=" + value);
        }
    }

    /**
     * Flush all open file handles
     */
    private static void flushAllFileHandles() {
        // Flush standard streams
        System.out.flush();
        System.err.flush();

        // Try to flush any Perl file handles through GlobalFileHandle
        try {
            // This would need to be implemented in GlobalFileHandle
            // to iterate through all open handles and flush them
            // For now, we just flush the standard streams
        } catch (Exception e) {
            // Ignore flush errors
        }
    }

    /**
     * Check if current process is a forked child (Windows emulation)
     */
    public static boolean isForkedChild() {
        return isForkedChild;
    }

    /**
     * Get parent PID for forked child (Windows emulation)
     */
    public static int getParentPid() {
        if (IS_WINDOWS && isForkedChild) {
            return parentPid;
        } else if (!IS_WINDOWS) {
            return PosixLibrary.INSTANCE.getppid();
        }
        return 0;
    }

    /**
     * Get current process PID (public method for other operators)
     */
    public static int getpid() {
        return getCurrentPid();
    }

    /**
     * Process info for Windows fork emulation
     */
    private static class ProcessInfo {
        Process process;
        int pid;
        long startTime;

        ProcessInfo() {
            this.startTime = System.currentTimeMillis();
        }
    }

    /**
     * Get the process ID of a Process object
     * Works across different Java versions
     */
    private static long getProcessId(Process process) {
        try {
            // Java 9+ has Process.pid()
            java.lang.reflect.Method pidMethod = process.getClass().getMethod("pid");
            return (Long) pidMethod.invoke(process);
        } catch (Exception e) {
            // Fall back to older approach using reflection
            try {
                java.lang.reflect.Field handleField = null;
                if (Platform.isWindows()) {
                    // Windows: get handle field
                    handleField = process.getClass().getDeclaredField("handle");
                    handleField.setAccessible(true);
                    long handle = handleField.getLong(process);

                    // Get PID from handle using Windows API
                    return Kernel32.INSTANCE.GetProcessId(new WinNT.HANDLE(Pointer.createConstant(handle)));
                } else {
                    // Unix: get pid field
                    handleField = process.getClass().getDeclaredField("pid");
                    handleField.setAccessible(true);
                    return handleField.getInt(process);
                }
            } catch (Exception ex) {
                // If all else fails, return -1
                return -1;
            }
        }
    }
}

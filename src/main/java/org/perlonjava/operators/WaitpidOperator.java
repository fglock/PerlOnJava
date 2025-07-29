package org.perlonjava.operators;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import org.perlonjava.nativ.PosixLibrary;
import org.perlonjava.runtime.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;

/**
 * Native implementation of Perl's waitpid operator using JNA
 *
 * - On POSIX systems: Uses native waitpid() system call
 * - On Windows: Uses Windows process APIs for equivalent functionality
 * - Properly retrieves exit codes and signal information
 * - Supports WNOHANG for non-blocking waits
 * - Better performance (no external process spawning)
 */
public class WaitpidOperator {

    // POSIX wait flags
    public static final int WNOHANG = 1;      // Non-blocking wait
    public static final int WUNTRACED = 2;    // Also return if child stopped
    public static final int WCONTINUED = 8;   // Also return if stopped child continued

    // Windows-specific constants
    private static final int STILL_ACTIVE = 259;
    private static final int INFINITE = -1;
    private static final int WAIT_TIMEOUT = 0x00000102;

    // Track child processes on Windows (since Windows doesn't have parent-child waitpid semantics)
    private static final Map<Integer, WinNT.HANDLE> windowsChildProcesses = new ConcurrentHashMap<>();

    private static final boolean IS_WINDOWS = Platform.isWindows();

    /**
     * Implements Perl's waitpid operator using native system calls
     *
     * @param args RuntimeBase containing the PID to wait for; RuntimeBase containing wait flags
     * @return RuntimeScalar with:
     *         - PID: if the specified process has terminated
     *         - 0: if WNOHANG is set and process is still running
     *         - -1: for error conditions
     */
    public static RuntimeScalar waitpid(RuntimeBase... args) {
        var list = new RuntimeArray(args);

        int pid = list.get(0).getInt();
        int flags = list.get(1).getInt();

        if (IS_WINDOWS) {
            return waitpidWindows(pid, flags);
        } else {
            return waitpidPosix(pid, flags);
        }
    }

    /**
     * POSIX implementation using native waitpid()
     */
    private static RuntimeScalar waitpidPosix(int pid, int flags) {
        try {
            IntByReference status = new IntByReference();
            int result = PosixLibrary.INSTANCE.waitpid(pid, status, flags);

            if (result > 0) {
                // Child process state changed
                setExitStatus(status.getValue());
                return new RuntimeScalar(result);
            } else if (result == 0) {
                // WNOHANG was specified and no child was ready
                return new RuntimeScalar(0);
            } else {
                // Error occurred
                int errno = Native.getLastError();
                if (errno == 10) { // ECHILD - No child processes
                    return new RuntimeScalar(-1);
                }
                // Other error
                setExitStatus(-1);
                return new RuntimeScalar(-1);
            }
        } catch (LastErrorException e) {
            // No child processes or other error
            return new RuntimeScalar(-1);
        }
    }

    /**
     * Windows implementation using Windows process APIs
     */
    private static RuntimeScalar waitpidWindows(int pid, int flags) {
        boolean nonBlocking = (flags & WNOHANG) != 0;

        if (pid <= 0) {
            // Windows doesn't support process groups in the same way
            // Return -1 to indicate no matching processes
            return new RuntimeScalar(-1);
        }

        // First check if this is one of our tracked child processes
        WinNT.HANDLE childHandle = windowsChildProcesses.get(pid);

        WinNT.HANDLE processHandle;
        boolean isOurChild = false;

        if (childHandle != null) {
            processHandle = childHandle;
            isOurChild = true;
        } else {
            // Try to open the process (may fail if it doesn't exist or we lack permissions)
            processHandle = Kernel32.INSTANCE.OpenProcess(
                    WinNT.PROCESS_QUERY_INFORMATION | WinNT.SYNCHRONIZE,
                    false,
                    pid
            );

            if (processHandle == null) {
                // Process doesn't exist or we can't access it
                return new RuntimeScalar(-1);
            }
        }

        try {
            // Wait for the process
            int waitTime = nonBlocking ? 0 : INFINITE;
            int waitResult = Kernel32.INSTANCE.WaitForSingleObject(processHandle, waitTime);

            if (waitResult == WinBase.WAIT_OBJECT_0) {
                // Process has terminated
                IntByReference exitCode = new IntByReference();
                if (Kernel32.INSTANCE.GetExitCodeProcess(processHandle, exitCode)) {
                    // Windows exit codes are just the value, not encoded like POSIX
                    // Shift left by 8 to match POSIX convention
                    setExitStatus(exitCode.getValue() << 8);
                } else {
                    setExitStatus(0);
                }

                // Remove from our tracked children if it was there
                if (isOurChild) {
                    windowsChildProcesses.remove(pid);
                }

                return new RuntimeScalar(pid);
            } else if (waitResult == WAIT_TIMEOUT) {
                // WNOHANG was specified and process is still running
                return new RuntimeScalar(0);
            } else {
                // Error occurred
                return new RuntimeScalar(-1);
            }
        } finally {
            // Clean up handle if we opened it
            if (!isOurChild && processHandle != null) {
                Kernel32.INSTANCE.CloseHandle(processHandle);
            }
        }
    }

    /**
     * Register a child process on Windows
     * This should be called when creating child processes on Windows
     * to enable proper parent-child waitpid semantics
     */
    public static void registerWindowsChildProcess(int pid, WinNT.HANDLE handle) {
        if (IS_WINDOWS) {
            windowsChildProcesses.put(pid, handle);
        }
    }

    /**
     * Set Perl's exit status variables
     *
     * @param status The raw status value from waitpid
     */
    private static void setExitStatus(int status) {
        // Set $? (CHILD_ERROR)
        getGlobalVariable("main::?").set(new RuntimeScalar(status));

        // Set ${^CHILD_ERROR_NATIVE}
        try {
            getGlobalVariable("main::^CHILD_ERROR_NATIVE").set(new RuntimeScalar(status));
        } catch (Exception e) {
            // Variable might not exist in all Perl versions
        }
    }

    /**
     * Check if a process is running (utility method)
     *
     * @param pid Process ID to check
     * @return true if process appears to be running, false if terminated/not found
     */
    public static boolean isProcessRunning(int pid) {
        RuntimeScalar result = waitpid(
                new RuntimeScalar(pid),
                new RuntimeScalar(WNOHANG)
        );
        return result.getInt() == 0; // 0 means still running with WNOHANG
    }

    /**
     * Extract exit code from status value (WEXITSTATUS macro)
     */
    public static int getExitCode(int status) {
        return (status >> 8) & 0xFF;
    }

    /**
     * Check if process exited normally (WIFEXITED macro)
     */
    public static boolean exitedNormally(int status) {
        return (status & 0xFF) == 0;
    }

    /**
     * Get signal that terminated process (WTERMSIG macro)
     */
    public static int getTerminationSignal(int status) {
        return status & 0x7F;
    }

    /**
     * Check if process was terminated by signal (WIFSIGNALED macro)
     */
    public static boolean wasSignaled(int status) {
        int sig = getTerminationSignal(status);
        return sig != 0 && sig != 0x7F;
    }

    /**
     * Check if process was stopped (WIFSTOPPED macro)
     */
    public static boolean wasStopped(int status) {
        return (status & 0xFF) == 0x7F;
    }

    /**
     * Get signal that stopped process (WSTOPSIG macro)
     */
    public static int getStopSignal(int status) {
        return (status >> 8) & 0xFF;
    }

    /**
     * Clean up any remaining Windows handles on shutdown
     */
    static {
        if (IS_WINDOWS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (WinNT.HANDLE handle : windowsChildProcesses.values()) {
                    try {
                        Kernel32.INSTANCE.CloseHandle(handle);
                    } catch (Exception ignored) {
                    }
                }
                windowsChildProcesses.clear();
            }));
        }
    }
}
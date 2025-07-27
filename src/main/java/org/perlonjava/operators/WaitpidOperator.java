package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;

/**
 * Simplified implementation of Perl's waitpid operator for PerlOnJava
 *
 * IMPORTANT LIMITATIONS:
 * ===================
 * This is a simplified implementation that cannot fully replicate native waitpid() behavior
 * due to Java's security model and lack of direct system call access. Specifically:
 *
 * 1. CANNOT WAIT FOR ARBITRARY CHILD PROCESSES:
 *    - Real waitpid() can wait for any child process spawned by the current process
 *    - This implementation can only CHECK if a specific PID exists, not wait for it to terminate
 *    - Java has no way to establish parent-child relationships with arbitrary processes
 *
 * 2. NO PROCESS GROUP SUPPORT:
 *    - waitpid(0, flags)     - wait for any child in same process group: NOT SUPPORTED
 *    - waitpid(-1, flags)    - wait for any child process: NOT SUPPORTED
 *    - waitpid(-pgid, flags) - wait for any child in specific process group: NOT SUPPORTED
 *    - These all return -1 immediately (no such child)
 *
 * 3. LIMITED EXIT CODE INFORMATION:
 *    - Real waitpid() provides detailed exit status (exit code, signal info, etc.)
 *    - This implementation can only detect if a process exists or not
 *    - Always sets $? to 0 when process terminates (we can't get the real exit code)
 *    - No support for WIFEXITED(), WEXITSTATUS(), WIFSIGNALED(), etc.
 *
 * 4. NO SIGNAL HANDLING:
 *    - WUNTRACED flag (wait for stopped processes): NOT SUPPORTED
 *    - WCONTINUED flag (wait for continued processes): NOT SUPPORTED
 *    - Cannot detect if process was terminated by signal vs normal exit
 *
 * 5. POLLING-BASED IMPLEMENTATION:
 *    - Real waitpid() blocks efficiently at kernel level
 *    - This implementation polls by running external commands (tasklist/kill)
 *    - Less efficient, potential race conditions
 *    - Blocking wait does busy-waiting with sleep intervals
 *
 * 6. SECURITY LIMITATIONS:
 *    - Can only check processes visible to current user
 *    - Some systems may restrict process visibility
 *    - May fail if tasklist/kill commands are not available
 *
 * WHAT WORKS:
 * ==========
 * - waitpid($specific_pid, 0): Will poll until process $specific_pid disappears
 * - waitpid($specific_pid, WNOHANG): Returns 0 if process exists, $pid if terminated
 * - Cross-platform: Windows (tasklist), Unix/Linux/Mac (kill -0)
 * - Sets $? and ${^CHILD_ERROR_NATIVE} variables
 *
 * TYPICAL USE CASE:
 * ================
 * This implementation is most useful when you have a specific PID (from fork(), system(), etc.)
 * and want to check if that process is still running:
 *
 *   my $pid = system("long_running_command &");
 *   while (waitpid($pid, WNOHANG) == 0) {
 *       print "Still running...\n";
 *       sleep(1);
 *   }
 *   print "Process finished\n";
 */
public class WaitpidOperator {

    // POSIX wait flags - only WNOHANG is supported
    public static final int WNOHANG = 1;    // Non-blocking wait
    public static final int WUNTRACED = 2;  // NOT SUPPORTED - wait for stopped children
    public static final int WCONTINUED = 4; // NOT SUPPORTED - wait for continued children

    /**
     * Implements Perl's waitpid operator (simplified version)
     *
     * @param args RuntimeBase containing the PID to wait for; RuntimeBase containing wait flags (only WNOHANG is supported)
     * @return RuntimeScalar with:
     *         - PID: if the specified process has terminated (or never existed)
     *         - 0: if WNOHANG is set and process is still running
     *         - -1: for error conditions or unsupported operations (pid <= 0)
     *
     * BEHAVIOR BY PID VALUE:
     * - pid > 0: Check if specific process exists (SUPPORTED)
     * - pid == 0: Wait for any child in same process group (NOT SUPPORTED - returns -1)
     * - pid == -1: Wait for any child process (NOT SUPPORTED - returns -1)
     * - pid < -1: Wait for any child in process group -pid (NOT SUPPORTED - returns -1)
     */
    public static RuntimeScalar waitpid(RuntimeBase... args) {
        var list = new RuntimeArray(args);

        long pid = list.get(0).getInt();
        int flags = list.get(1).getInt();
        boolean nonBlocking = (flags & WNOHANG) != 0;

        // Check for unsupported flags
        if ((flags & WUNTRACED) != 0 || (flags & WCONTINUED) != 0) {
            // These flags are not supported in our implementation
            // Real Perl would handle stopped/continued processes
            // We ignore these flags and proceed with basic functionality
        }

        if (pid <= 0) {
            // LIMITATION: Cannot implement process group waiting or "wait for any child"
            // without maintaining a registry of child processes
            //
            // In real Perl:
            // - pid == 0: wait for any child whose process group ID equals current process
            // - pid == -1: wait for any child process
            // - pid < -1: wait for any child whose process group ID equals -pid
            //
            // We return -1 to indicate "no such child process" which is what real
            // waitpid() returns when there are no children to wait for
            setExitStatus(0);
            return new RuntimeScalar(-1);
        }

        // Only case we can handle: wait for specific process ID
        return checkProcess(pid, nonBlocking);
    }

    /**
     * Check if a specific process exists (cross-platform)
     *
     * IMPORTANT: This only checks if a process EXISTS, not if it's our child.
     * Real waitpid() only waits for child processes, but we can't distinguish
     * between child and non-child processes in Java.
     */
    private static RuntimeScalar checkProcess(long pid, boolean nonBlocking) {
        if (SystemUtils.osIsWindows()) {
            return checkProcessWindows(pid, nonBlocking);
        } else {
            return checkProcessUnix(pid, nonBlocking);
        }
    }

    /**
     * Windows implementation using tasklist command
     *
     * LIMITATIONS:
     * - Requires tasklist.exe to be available (standard on Windows)
     * - May be slow due to external process invocation
     * - Cannot get actual exit codes from terminated processes
     * - May fail if user lacks permission to query process list
     */
    private static RuntimeScalar checkProcessWindows(long pid, boolean nonBlocking) {
        try {
            // Use tasklist to check if process exists
            // Format: tasklist /FI "PID eq 1234"
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid);
            pb.redirectErrorStream(true); // Combine stdout and stderr
            Process proc = pb.start();

            boolean finished;
            if (nonBlocking) {
                // For non-blocking wait, give tasklist a reasonable timeout
                finished = proc.waitFor(500, TimeUnit.MILLISECONDS);
                if (!finished) {
                    // tasklist is taking too long, kill it and assume process exists
                    proc.destroyForcibly();
                    return new RuntimeScalar(0); // Return 0 for WNOHANG (still running)
                }
            } else {
                // Blocking wait - wait for tasklist to complete
                proc.waitFor();
                finished = true;
            }

            if (finished) {
                // Read tasklist output
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                // If tasklist output contains our PID, the process is running
                String outputStr = output.toString();
                if (outputStr.contains(String.valueOf(pid)) && !outputStr.contains("No tasks")) {
                    if (nonBlocking) {
                        return new RuntimeScalar(0); // Process still running
                    } else {
                        // Blocking wait - sleep and try again
                        // LIMITATION: This is inefficient busy-waiting
                        // Real waitpid() would block efficiently at kernel level
                        Thread.sleep(100);
                        return checkProcessWindows(pid, false);
                    }
                } else {
                    // Process not found - assume it has terminated
                    // LIMITATION: We set exit status to 0 because we can't get the real exit code
                    setExitStatus(0);
                    return new RuntimeScalar(pid);
                }
            }

            return new RuntimeScalar(0);

        } catch (Exception e) {
            // Error occurred (tasklist failed, etc.)
            // Set error status and return -1
            setExitStatus(-1);
            return new RuntimeScalar(-1);
        }
    }

    /**
     * Unix/Linux/Mac implementation using kill -0 command
     *
     * kill -0 sends signal 0 (null signal) which doesn't actually send a signal
     * but checks if the process exists and we have permission to signal it
     *
     * LIMITATIONS:
     * - Requires kill command to be available (standard on Unix systems)
     * - May fail if user lacks permission to signal the process
     * - Cannot distinguish between "process doesn't exist" and "permission denied"
     * - Cannot get actual exit codes from terminated processes
     */
    private static RuntimeScalar checkProcessUnix(long pid, boolean nonBlocking) {
        try {
            // Use kill -0 to check if process exists
            // kill -0 doesn't actually send a signal, just checks process existence
            ProcessBuilder pb = new ProcessBuilder("kill", "-0", String.valueOf(pid));
            pb.redirectErrorStream(true); // Combine stdout and stderr
            Process proc = pb.start();

            boolean finished;
            if (nonBlocking) {
                // For non-blocking wait, give kill command a reasonable timeout
                finished = proc.waitFor(500, TimeUnit.MILLISECONDS);
                if (!finished) {
                    // kill command is taking too long, kill it and assume process exists
                    proc.destroyForcibly();
                    return new RuntimeScalar(0); // Return 0 for WNOHANG
                }
            } else {
                // Blocking wait - wait for kill command to complete
                proc.waitFor();
                finished = true;
            }

            if (finished) {
                int exitCode = proc.exitValue();
                if (exitCode == 0) {
                    // kill -0 succeeded, process exists and we can signal it
                    if (nonBlocking) {
                        return new RuntimeScalar(0); // Process still running
                    } else {
                        // Blocking wait - sleep and try again
                        // LIMITATION: This is inefficient busy-waiting
                        // Real waitpid() would block efficiently at kernel level
                        Thread.sleep(100);
                        return checkProcessUnix(pid, false);
                    }
                } else {
                    // kill -0 failed, process doesn't exist or we can't signal it
                    // Assume process has terminated
                    // LIMITATION: We can't distinguish between "terminated" and "permission denied"
                    // LIMITATION: We set exit status to 0 because we can't get the real exit code
                    setExitStatus(0);
                    return new RuntimeScalar(pid);
                }
            }

            return new RuntimeScalar(0);

        } catch (Exception e) {
            // Error occurred (kill command failed, etc.)
            // Set error status and return -1
            setExitStatus(-1);
            return new RuntimeScalar(-1);
        }
    }

    /**
     * Set Perl's exit status variables
     *
     * LIMITATION: We can only set generic status values since we can't get
     * real exit codes or signal information from arbitrary processes
     *
     * In real Perl, $? contains detailed status information:
     * - Low 8 bits: signal number that killed the process (0 if exited normally)
     * - High 8 bits: exit code of the process
     * - Additional bits for core dump info, etc.
     *
     * Our implementation just sets simple values since we lack this information.
     */
    private static void setExitStatus(int status) {
        // Set $? (CHILD_ERROR) - this is the standard Perl variable for exit status
        getGlobalVariable("main::?").set(new RuntimeScalar(status));

        // Set ${^CHILD_ERROR_NATIVE} if it exists
        // This variable contains the native wait() status on systems that support it
        try {
            getGlobalVariable("main::^CHILD_ERROR_NATIVE").set(new RuntimeScalar(status));
        } catch (Exception e) {
            // Variable might not exist in all Perl versions, ignore silently
        }
    }

    /**
     * Simple helper to check if a process is running
     *
     * This is a convenience method for other parts of the system that just
     * want to know if a process exists without the full waitpid semantics.
     *
     * @param pid Process ID to check
     * @return true if process appears to be running, false if terminated/not found
     *
     * LIMITATION: Same limitations as waitpid() - only checks existence,
     * not parent-child relationship
     */
    public static boolean isProcessRunning(long pid) {
        RuntimeScalar result = checkProcess(pid, true);
        return result.getInt() == 0; // 0 means still running for WNOHANG
    }

    /**
     * Get a human-readable description of this implementation's limitations
     *
     * This could be useful for debugging or documentation purposes.
     */
    public static String getLimitationsDescription() {
        return "PerlOnJava waitpid() limitations:\n" +
                "- Cannot wait for arbitrary child processes (Java security model)\n" +
                "- No process group support (pid <= 0 cases)\n" +
                "- Cannot retrieve real exit codes from external processes\n" +
                "- No signal information (WIFEXITED, WIFSIGNALED, etc.)\n" +
                "- Uses polling instead of efficient kernel-level blocking\n" +
                "- Only works with processes visible to current user\n" +
                "- Requires external commands (tasklist on Windows, kill on Unix)\n" +
                "- May have race conditions due to polling nature\n" +
                "\n" +
                "Supported: waitpid(specific_pid, 0 or WNOHANG)";
    }
}
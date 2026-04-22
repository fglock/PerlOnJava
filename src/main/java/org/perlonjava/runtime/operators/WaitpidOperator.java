package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;

public class WaitpidOperator {

    public static final int WNOHANG = 1;
    public static final int WUNTRACED = 2;
    public static final int WCONTINUED = 8;

    private static final Map<Long, Process> windowsChildProcesses = new ConcurrentHashMap<>();

    private static final boolean IS_WINDOWS = NativeUtils.IS_WINDOWS;

    public static RuntimeScalar waitForChild() {
        return waitpid(SCALAR, new RuntimeScalar(-1), new RuntimeScalar(0));
    }

    public static RuntimeScalar waitpid(int ctx, RuntimeBase... args) {
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
     * Returns true if $SIG{CHLD} is currently set to 'IGNORE'. Under
     * that disposition POSIX mandates the kernel auto-reap children, so
     * subsequent waitpid() returns -1 with errno=ECHILD (see waitpid(2)
     * on Linux, and Perl's perlipc documentation). We simulate that so
     * test suites like System::Command's t/20-zombie.t can observe the
     * expected "BOGUS exit status" pattern instead of a clean reap.
     */
    private static boolean isChldIgnored() {
        try {
            RuntimeScalar h = getGlobalHash("main::SIG").get("CHLD");
            return h != null && "IGNORE".equals(h.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private static RuntimeScalar waitpidPosix(int pid, int flags) {
        if (pid > 0) {
            Process javaProcess = RuntimeIO.getChildProcess(pid);
            if (javaProcess != null) {
                return waitpidJavaProcess(pid, javaProcess, flags);
            }
        }
        try {
            int[] status = new int[1];
            long result = FFMPosix.get().waitpid(pid, status, flags);

            if (result > 0) {
                setExitStatus(status[0]);
                return new RuntimeScalar((int) result);
            } else if (result == 0) {
                return new RuntimeScalar(0);
            } else {
                int errno = FFMPosix.get().errno();
                if (errno == 10) { // ECHILD
                    return new RuntimeScalar(-1);
                }
                setExitStatus(-1);
                return new RuntimeScalar(-1);
            }
        } catch (Exception e) {
            return new RuntimeScalar(-1);
        }
    }

    private static RuntimeScalar waitpidJavaProcess(int pid, Process process, int flags) {
        boolean nonBlocking = (flags & WNOHANG) != 0;
        boolean chldIgnore = isChldIgnored();
        if (nonBlocking) {
            if (process.isAlive()) return new RuntimeScalar(0);
            int exitCode = process.exitValue();
            RuntimeIO.removeChildProcess(pid);
            if (chldIgnore) {
                // Kernel auto-reap simulation: discard status, signal
                // ECHILD by returning -1. Do NOT update $?.
                return new RuntimeScalar(-1);
            }
            setExitStatus(exitCode << 8);
            return new RuntimeScalar(pid);
        }
        try {
            int exitCode = process.waitFor();
            RuntimeIO.removeChildProcess(pid);
            if (chldIgnore) {
                return new RuntimeScalar(-1);
            }
            setExitStatus(exitCode << 8);
            return new RuntimeScalar(pid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RuntimeScalar(-1);
        }
    }

    private static RuntimeScalar waitpidWindows(int pid, int flags) {
        boolean nonBlocking = (flags & WNOHANG) != 0;

        if (pid <= 0) {
            return new RuntimeScalar(-1);
        }

        Process childProcess = windowsChildProcesses.get((long) pid);
        if (childProcess != null) {
            if (nonBlocking) {
                if (childProcess.isAlive()) return new RuntimeScalar(0);
            } else {
                try {
                    childProcess.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new RuntimeScalar(-1);
                }
            }
            int exitCode = childProcess.exitValue();
            windowsChildProcesses.remove((long) pid);
            setExitStatus(exitCode << 8);
            return new RuntimeScalar(pid);
        }

        var ph = ProcessHandle.of(pid);
        if (ph.isEmpty()) return new RuntimeScalar(-1);
        if (nonBlocking) {
            if (ph.get().isAlive()) return new RuntimeScalar(0);
            setExitStatus(0);
            return new RuntimeScalar(pid);
        }
        ph.get().onExit().join();
        setExitStatus(0);
        return new RuntimeScalar(pid);
    }

    public static void registerChildProcess(long pid, Process process) {
        windowsChildProcesses.put(pid, process);
    }

    private static void setExitStatus(int status) {
        getGlobalVariable("main::?").set(new RuntimeScalar(status));

        try {
            getGlobalVariable("main::^CHILD_ERROR_NATIVE").set(new RuntimeScalar(status));
        } catch (Exception e) {
        }
    }

    public static boolean isProcessRunning(int pid) {
        RuntimeScalar result = waitpid(SCALAR,
                new RuntimeScalar(pid),
                new RuntimeScalar(WNOHANG)
        );
        return result.getInt() == 0;
    }

    public static int getExitCode(int status) {
        return (status >> 8) & 0xFF;
    }

    public static boolean exitedNormally(int status) {
        return (status & 0xFF) == 0;
    }

    public static int getTerminationSignal(int status) {
        return status & 0x7F;
    }

    public static boolean wasSignaled(int status) {
        int sig = getTerminationSignal(status);
        return sig != 0 && sig != 0x7F;
    }

    public static boolean wasStopped(int status) {
        return (status & 0xFF) == 0x7F;
    }

    public static int getStopSignal(int status) {
        return (status >> 8) & 0xFF;
    }
}

package org.perlonjava.operators;

import com.sun.jna.LastErrorException;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Wincon;
import org.perlonjava.nativ.NativeUtils;
import org.perlonjava.nativ.PosixLibrary;
import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;

/**
 * Implementation of Perl's kill operator for PerlOnJava
 */
public class KillOperator {

    /**
     * Send a signal to a process (following Perl's kill operator)
     * @param args RuntimeBase array: [signal, pid1, pid2, ...]
     * @return RuntimeScalar with count of successfully signaled processes
     */
    public static RuntimeScalar kill(RuntimeBase... args) {
        if (args.length < 2) {
            return new RuntimeScalar(0);
        }

        // First argument is the signal
        RuntimeScalar signalArg = args[0].getFirst();
        int signal;

        // Handle named signals (e.g., "TERM", "KILL", "HUP")
        if (signalArg.isString()) {
            signal = getSignalNumber(signalArg.toString());
            if (signal == -1) {
                // Invalid signal name
                setErrno(22); // EINVAL
                return new RuntimeScalar(0);
            }
        } else {
            signal = signalArg.getInt();
        }

        int successCount = 0;

        // Process each PID starting from second argument
        for (int i = 1; i < args.length; i++) {
            for (RuntimeScalar scalar : args[i]) {
                int pid = scalar.getInt();

                // Special case: negative PID means process group
                if (pid < 0 && !NativeUtils.IS_WINDOWS) {
                    if (sendSignalToProcessGroup(-pid, signal)) {
                        successCount++;
                    }
                } else if (pid > 0) {
                    if (sendSignalToPid(pid, signal)) {
                        successCount++;
                    }
                }
                // pid == 0 would signal all processes in the current process group
                // Not implemented for safety reasons
            }
        }

        return new RuntimeScalar(successCount);
    }

    // Helper method for sending signals to a single process
    private static boolean sendSignalToPid(int pid, int signal) {
        if (NativeUtils.IS_WINDOWS) {
            switch (signal) {
                case 0: // Check if process exists
                    WinNT.HANDLE handle = Kernel32.INSTANCE.OpenProcess(
                            WinNT.PROCESS_QUERY_INFORMATION, false, pid);
                    if (handle != null) {
                        Kernel32.INSTANCE.CloseHandle(handle);
                        return true;
                    }
                    setErrno(3); // ESRCH - No such process
                    return false;

                case 2: // SIGINT - try Ctrl+C event
                    boolean result = Kernel32.INSTANCE.GenerateConsoleCtrlEvent(
                            Wincon.CTRL_C_EVENT, pid);
                    if (!result) {
                        setErrno(Kernel32.INSTANCE.GetLastError());
                    }
                    return result;

                case 3: // SIGQUIT - try Ctrl+Break event
                    result = Kernel32.INSTANCE.GenerateConsoleCtrlEvent(
                            Wincon.CTRL_BREAK_EVENT, pid);
                    if (!result) {
                        setErrno(Kernel32.INSTANCE.GetLastError());
                    }
                    return result;

                case 9: // SIGKILL
                case 15: // SIGTERM
                    WinNT.HANDLE processHandle = Kernel32.INSTANCE.OpenProcess(
                            WinNT.PROCESS_TERMINATE, false, pid);
                    if (processHandle != null) {
                        try {
                            result = Kernel32.INSTANCE.TerminateProcess(processHandle, 1);
                            if (!result) {
                                setErrno(Kernel32.INSTANCE.GetLastError());
                            }
                            return result;
                        } finally {
                            Kernel32.INSTANCE.CloseHandle(processHandle);
                        }
                    }
                    setErrno(Kernel32.INSTANCE.GetLastError());
                    return false;

                default:
                    // Other signals not supported on Windows
                    setErrno(22); // EINVAL
                    return false;
            }
        } else {
            try {
                int result = PosixLibrary.INSTANCE.kill(pid, signal);
                return result == 0;
            } catch (LastErrorException e) {
                setErrno(e.getErrorCode());
                return false;
            }
        }
    }

    // Helper method for sending signals to process groups (Unix only)
    private static boolean sendSignalToProcessGroup(int pgid, int signal) {
        try {
            // kill with negative PID targets process group
            int result = PosixLibrary.INSTANCE.kill(-pgid, signal);
            return result == 0;
        } catch (LastErrorException e) {
            setErrno(e.getErrorCode());
            return false;
        }
    }

    // Convert signal names to numbers
    private static int getSignalNumber(String signalName) {
        String name = signalName.toUpperCase();
        // Remove optional "SIG" prefix
        if (name.startsWith("SIG")) {
            name = name.substring(3);
        }

        switch (name) {
            case "HUP":    return 1;
            case "INT":    return 2;
            case "QUIT":   return 3;
            case "ILL":    return 4;
            case "TRAP":   return 5;
            case "ABRT":   return 6;
            case "BUS":    return 7;
            case "FPE":    return 8;
            case "KILL":   return 9;
            case "USR1":   return 10;
            case "SEGV":   return 11;
            case "USR2":   return 12;
            case "PIPE":   return 13;
            case "ALRM":   return 14;
            case "TERM":   return 15;
            case "STKFLT": return 16;
            case "CHLD":   return 17;
            case "CONT":   return 18;
            case "STOP":   return 19;
            case "TSTP":   return 20;
            case "TTIN":   return 21;
            case "TTOU":   return 22;
            case "URG":    return 23;
            case "XCPU":   return 24;
            case "XFSZ":   return 25;
            case "VTALRM": return 26;
            case "PROF":   return 27;
            case "WINCH":  return 28;
            case "IO":
            case "POLL":   return 29;
            case "PWR":    return 30;
            case "SYS":    return 31;
            default:       return -1;
        }
    }

    // Set errno for error reporting
    private static void setErrno(int errno) {
        getGlobalVariable("main::!").set(new RuntimeScalar(errno));
    }
}

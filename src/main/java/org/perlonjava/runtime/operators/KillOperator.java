package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.PosixLibrary;
import org.perlonjava.runtime.runtimetypes.PerlSignalQueue;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;

/**
 * Implementation of Perl's kill operator for PerlOnJava
 */
public class KillOperator {

    /**
     * Send a signal to a process (following Perl's kill operator)
     *
     * @param args RuntimeBase array: [signal, pid1, pid2, ...]
     * @return RuntimeScalar with count of successfully signaled processes
     */
    public static RuntimeScalar kill(int ctx, RuntimeBase... args) {
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

    private static String getSignalName(int signal) {
        return switch (signal) {
            case 1 -> "HUP";
            case 2 -> "INT";
            case 3 -> "QUIT";
            case 4 -> "ILL";
            case 5 -> "TRAP";
            case 6 -> "ABRT";
            case 7 -> "BUS";
            case 8 -> "FPE";
            case 9 -> "KILL";
            case 10 -> "USR1";
            case 11 -> "SEGV";
            case 12 -> "USR2";
            case 13 -> "PIPE";
            case 14 -> "ALRM";
            case 15 -> "TERM";
            default -> null;
        };
    }

    private static boolean sendSignalToPid(int pid, int signal) {
        long myPid = ProcessHandle.current().pid();
        if (pid == myPid && signal != 0) {
            String sigName = getSignalName(signal);
            if (sigName != null) {
                RuntimeScalar handler = getGlobalHash("main::SIG").get(sigName);
                if (handler.getDefinedBoolean()) {
                    PerlSignalQueue.enqueue(sigName, handler);
                    PerlSignalQueue.checkPendingSignals();
                    return true;
                }
            }
            return true;
        }
        if (NativeUtils.IS_WINDOWS) {
            switch (signal) {
                case 0:
                    var ph = ProcessHandle.of(pid);
                    if (ph.isPresent() && ph.get().isAlive()) return true;
                    setErrno(3);
                    return false;

                case 2:
                case 3:
                    var proc = ProcessHandle.of(pid);
                    if (proc.isPresent()) {
                        proc.get().destroy();
                        return true;
                    }
                    setErrno(3);
                    return false;

                case 9:
                case 15:
                    var p = ProcessHandle.of(pid);
                    if (p.isPresent()) {
                        if (signal == 9) p.get().destroyForcibly();
                        else p.get().destroy();
                        return true;
                    }
                    setErrno(3);
                    return false;

                default:
                    // Other signals not supported on Windows
                    setErrno(22); // EINVAL
                    return false;
            }
        } else {
            int result = PosixLibrary.INSTANCE.kill(pid, signal);
            if (result != 0) {
                setErrno(PosixLibrary.INSTANCE.errno());
                return false;
            }
            return true;
        }
    }

    // Helper method for sending signals to process groups (Unix only)
    private static boolean sendSignalToProcessGroup(int pgid, int signal) {
        int result = PosixLibrary.INSTANCE.kill(-pgid, signal);
        if (result != 0) {
            setErrno(PosixLibrary.INSTANCE.errno());
            return false;
        }
        return true;
    }

    // Convert signal names to numbers
    private static int getSignalNumber(String signalName) {
        String name = signalName.toUpperCase();
        // Remove optional "SIG" prefix
        if (name.startsWith("SIG")) {
            name = name.substring(3);
        }

        switch (name) {
            case "HUP":
                return 1;
            case "INT":
                return 2;
            case "QUIT":
                return 3;
            case "ILL":
                return 4;
            case "TRAP":
                return 5;
            case "ABRT":
                return 6;
            case "BUS":
                return 7;
            case "FPE":
                return 8;
            case "KILL":
                return 9;
            case "USR1":
                return 10;
            case "SEGV":
                return 11;
            case "USR2":
                return 12;
            case "PIPE":
                return 13;
            case "ALRM":
                return 14;
            case "TERM":
                return 15;
            case "STKFLT":
                return 16;
            case "CHLD":
                return 17;
            case "CONT":
                return 18;
            case "STOP":
                return 19;
            case "TSTP":
                return 20;
            case "TTIN":
                return 21;
            case "TTOU":
                return 22;
            case "URG":
                return 23;
            case "XCPU":
                return 24;
            case "XFSZ":
                return 25;
            case "VTALRM":
                return 26;
            case "PROF":
                return 27;
            case "WINCH":
                return 28;
            case "IO":
            case "POLL":
                return 29;
            case "PWR":
                return 30;
            case "SYS":
                return 31;
            default:
                return -1;
        }
    }

    // Set errno for error reporting
    private static void setErrno(int errno) {
        getGlobalVariable("main::!").set(new RuntimeScalar(errno));
    }
}

package org.perlonjava.operators;

import com.sun.jna.LastErrorException;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Wincon;
import org.perlonjava.nativ.NativeUtils;
import org.perlonjava.nativ.PosixLibrary;
import org.perlonjava.runtime.*;

import java.awt.image.renderable.RenderableImage;
import java.util.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;

/**
 * Implementation of Perl's kill operator for PerlOnJava
 */
public class KillOperator {

    /**
     * Send a signal to a process (following operator API pattern)
     * @param args RuntimeBase array: [signal, pid1, pid2, ...]
     * @return RuntimeScalar with count of successfully signaled processes
     */
    public static RuntimeScalar kill(RuntimeBase... args) {
        if (args.length < 2) {
            return new RuntimeScalar(0);
        }

        // First argument is the signal
        int signal = args[0].getFirst().getInt();
        int successCount = 0;

        // Process each PID starting from second argument
        for (int i = 1; i < args.length; i++) {
            for (RuntimeScalar scalar : args[i]) {
                int pid = scalar.getInt();
                if (sendSignalToPid(pid, signal)) {
                    successCount++;
                }
            }
        }

        return new RuntimeScalar(successCount);
    }

    // Helper method for sending signals
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
                    return false;

                case 2: // SIGINT - try Ctrl+C event
                    return Kernel32.INSTANCE.GenerateConsoleCtrlEvent(
                            Wincon.CTRL_C_EVENT, pid);

                case 9: // SIGKILL
                case 15: // SIGTERM
                    WinNT.HANDLE processHandle = Kernel32.INSTANCE.OpenProcess(
                            WinNT.PROCESS_TERMINATE, false, pid);
                    if (processHandle != null) {
                        try {
                            return Kernel32.INSTANCE.TerminateProcess(processHandle, 1);
                        } finally {
                            Kernel32.INSTANCE.CloseHandle(processHandle);
                        }
                    }
                    return false;

                default:
                    // Other signals not supported on Windows
                    return false;
            }
        } else {
            try {
                return PosixLibrary.INSTANCE.kill(pid, signal) == 0;
            } catch (LastErrorException e) {
                return false;
            }
        }
    }
}


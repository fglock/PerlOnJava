package org.perlonjava.nativ;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import org.perlonjava.runtime.*;

/**
 * Platform-agnostic native operations utility following PerlOnJava operator API
 */
public class NativeUtils {
    public static final boolean IS_WINDOWS = Platform.isWindows();

    // Constants for default IDs
    private static final int DEFAULT_UID = 1000;
    private static final int DEFAULT_GID = 1000;
    private static final int ID_RANGE = 65536;

    /**
     * Get current process ID
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with process ID
     */
    public static RuntimeScalar getpid(RuntimeBase... args) {
        if (IS_WINDOWS) {
            return new RuntimeScalar(Kernel32.INSTANCE.GetCurrentProcessId());
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.getpid());
        }
    }

    /**
     * Get parent process ID
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with parent process ID
     */
    public static RuntimeScalar getppid(RuntimeBase... args) {
        if (IS_WINDOWS) {
            // Windows implementation
            int currentPid = Kernel32.INSTANCE.GetCurrentProcessId();
            WinNT.HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
                    Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));

            try {
                Tlhelp32.PROCESSENTRY32 entry = new Tlhelp32.PROCESSENTRY32();
                if (Kernel32.INSTANCE.Process32First(snapshot, entry)) {
                    do {
                        if (entry.th32ProcessID.intValue() == currentPid) {
                            return new RuntimeScalar(entry.th32ParentProcessID.intValue());
                        }
                    } while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(snapshot);
            }
            return new RuntimeScalar(0);
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.getppid());
        }
    }

    /**
     * Change file permissions
     * @param args RuntimeBase array: [filename, mode]
     * @return RuntimeScalar with success (1) or failure (0)
     */
    public static RuntimeScalar chmod(RuntimeBase... args) {
        if (args.length < 2) {
            return new RuntimeScalar(0);
        }

        String path = args[0].getFirst().toString();
        int mode = (int) args[1].getFirst().getInt();

        if (IS_WINDOWS) {
            // Windows: use File attributes
            int attributes = 0;
            if ((mode & 0200) == 0) { // Write bit not set
                attributes |= WinNT.FILE_ATTRIBUTE_READONLY;
            }

            boolean success = Kernel32.INSTANCE.SetFileAttributes(path, new WinDef.DWORD(attributes));
            return new RuntimeScalar(success ? 1 : 0);
        } else {
            try {
                int result = PosixLibrary.INSTANCE.chmod(path, mode);
                return new RuntimeScalar(result == 0 ? 1 : 0);
            } catch (LastErrorException e) {
                return new RuntimeScalar(0);
            }
        }
    }

    /**
     * Get user ID (returns username-based hash on Windows)
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with user ID
     */
    public static RuntimeScalar getuid(RuntimeBase... args) {
        if (IS_WINDOWS) {
            // Simplified approach: use username hash
            try {
                String userName = System.getProperty("user.name");
                if (userName != null && !userName.isEmpty()) {
                    return new RuntimeScalar(Math.abs(userName.hashCode()) % ID_RANGE);
                }
            } catch (Exception e) {
                // Fall through to default
            }
            return new RuntimeScalar(DEFAULT_UID);
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.getuid());
        }
    }

    /**
     * Get effective user ID
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with effective user ID
     */
    public static RuntimeScalar geteuid(RuntimeBase... args) {
        if (IS_WINDOWS) {
            // On Windows, effective UID is same as UID
            return getuid(args);
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.geteuid());
        }
    }

    /**
     * Get group ID (returns computer name hash on Windows)
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with group ID
     */
    public static RuntimeScalar getgid(RuntimeBase... args) {
        if (IS_WINDOWS) {
            // Simplified: use computer name for consistent group ID
            try {
                String computerName = System.getenv("COMPUTERNAME");
                if (computerName != null && !computerName.isEmpty()) {
                    return new RuntimeScalar(Math.abs(computerName.hashCode()) % ID_RANGE);
                }
            } catch (Exception e) {
                // Fall through to default
            }
            return new RuntimeScalar(DEFAULT_GID);
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.getgid());
        }
    }

    /**
     * Get effective group ID
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with effective group ID
     */
    public static RuntimeScalar getegid(RuntimeBase... args) {
        if (IS_WINDOWS) {
            // On Windows, effective GID is same as GID
            return getgid(args);
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.getegid());
        }
    }

    /**
     * Send signal to process (limited support on Windows)
     * @param args RuntimeBase array: [pid, signal]
     * @return RuntimeScalar with 0 on success, -1 on failure
     */
    public static RuntimeScalar kill(RuntimeBase... args) {
        if (args.length < 2) {
            return new RuntimeScalar(-1);
        }

        int pid = (int) args[0].getFirst().getInt();
        int sig = (int) args[1].getFirst().getInt();

        if (IS_WINDOWS) {
            // Windows doesn't have signals, but can terminate process
            if (sig == 9 || sig == 15) { // SIGKILL or SIGTERM
                WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                        WinNT.PROCESS_TERMINATE, false, pid);
                if (process != null) {
                    try {
                        boolean result = Kernel32.INSTANCE.TerminateProcess(process, 1);
                        return new RuntimeScalar(result ? 0 : -1);
                    } finally {
                        Kernel32.INSTANCE.CloseHandle(process);
                    }
                }
            }
            return new RuntimeScalar(-1);
        } else {
            try {
                int result = PosixLibrary.INSTANCE.kill(pid, sig);
                return new RuntimeScalar(result);
            } catch (LastErrorException e) {
                return new RuntimeScalar(-1);
            }
        }
    }

    /**
     * Delete a file
     * @param args RuntimeBase array: [filename]
     * @return RuntimeScalar with 1 on success, 0 on failure
     */
    public static RuntimeScalar unlink(RuntimeBase... args) {
        if (args.length < 1) {
            return new RuntimeScalar(0);
        }

        String path = args[0].getFirst().toString();

        if (IS_WINDOWS) {
            boolean result = Kernel32.INSTANCE.DeleteFile(path);
            return new RuntimeScalar(result ? 1 : 0);
        } else {
            try {
                int result = PosixLibrary.INSTANCE.unlink(path);
                return new RuntimeScalar(result == 0 ? 1 : 0);
            } catch (LastErrorException e) {
                return new RuntimeScalar(0);
            }
        }
    }

    /**
     * Change file ownership (limited support on Windows)
     * @param args RuntimeBase array: [filename, uid, gid]
     * @return RuntimeScalar with 1 on success, 0 on failure
     */
    public static RuntimeScalar chown(RuntimeBase... args) {
        if (args.length < 3) {
            return new RuntimeScalar(0);
        }

        String path = args[0].getFirst().toString();
        int uid = (int) args[1].getFirst().getInt();
        int gid = (int) args[2].getFirst().getInt();

        if (IS_WINDOWS) {
            // Windows doesn't have chown, always return success
            // In a real implementation, you might use Windows security APIs
            return new RuntimeScalar(1);
        } else {
            try {
                int result = PosixLibrary.INSTANCE.chown(path, uid, gid);
                return new RuntimeScalar(result == 0 ? 1 : 0);
            } catch (LastErrorException e) {
                return new RuntimeScalar(0);
            }
        }
    }

    /**
     * Set file creation mask
     * @param args RuntimeBase array: [mask]
     * @return RuntimeScalar with previous mask value
     */
    public static RuntimeScalar umask(RuntimeBase... args) {
        if (args.length < 1) {
            return new RuntimeScalar(0);
        }

        int mask = (int) args[0].getFirst().getInt();

        if (IS_WINDOWS) {
            // Windows doesn't have umask, return the input value
            return new RuntimeScalar(mask);
        } else {
            int oldMask = PosixLibrary.INSTANCE.umask(mask);
            return new RuntimeScalar(oldMask);
        }
    }
}

package org.perlonjava.nativ;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Platform-agnostic native operations utility following PerlOnJava operator API
 */
public class NativeUtils {
    public static final boolean IS_WINDOWS = Platform.isWindows();

    // Constants for default IDs
    private static final int DEFAULT_UID = 1000;
    private static final int DEFAULT_GID = 1000;
    private static final int ID_RANGE = 65536;

    // Direct function mapping for CreateHardLink on Windows
    private static final Function CREATE_HARD_LINK = IS_WINDOWS ?
            Function.getFunction("kernel32", "CreateHardLinkA") : null;

    /**
     * Create a symbolic link
     * @param args RuntimeScalar array containing [oldfile, newfile]
     * @return RuntimeScalar with 1 for success, 0 for failure
     */
    public static RuntimeScalar symlink(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            return new RuntimeScalar(0);
        }

        String oldFile = RuntimeIO.resolvePath(args[0].toString()).toString();
        String newFile = RuntimeIO.resolvePath(args[1].toString()).toString();

        if (oldFile == null || newFile == null || oldFile.isEmpty() || newFile.isEmpty()) {
            return new RuntimeScalar(0);
        }

        try {
            Path target = Paths.get(oldFile);
            Path link = Paths.get(newFile);

            // Check if the link already exists
            if (Files.exists(link)) {
                // Set $! to "File exists"
                GlobalVariable.getGlobalVariable("main::!").set("File exists");
                Native.setLastError(17); // EEXIST
                return new RuntimeScalar(0);
            }

            // Create the symbolic link
            Files.createSymbolicLink(link, target);

            return new RuntimeScalar(1);

        } catch (UnsupportedOperationException e) {
            // Symbolic links not supported on this platform
            throw new RuntimeException("Symbolic links are not implemented on this platform");
        } catch (IOException e) {
            // Set $! based on the specific IOException
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("privilege")) {
                GlobalVariable.getGlobalVariable("main::!").set("Permission denied");
                Native.setLastError(13); // EACCES
            } else if (errorMessage != null && errorMessage.contains("Access is denied")) {
                GlobalVariable.getGlobalVariable("main::!").set("Permission denied");
                Native.setLastError(13); // EACCES
            } else if (errorMessage != null && errorMessage.contains("No such file")) {
                GlobalVariable.getGlobalVariable("main::!").set("No such file or directory");
                Native.setLastError(2); // ENOENT
            } else {
                GlobalVariable.getGlobalVariable("main::!").set(errorMessage != null ? errorMessage : "I/O error");
            }
            return new RuntimeScalar(0);
        } catch (SecurityException e) {
            GlobalVariable.getGlobalVariable("main::!").set("Permission denied");
            Native.setLastError(13); // EACCES
            return new RuntimeScalar(0);
        } catch (Exception e) {
            GlobalVariable.getGlobalVariable("main::!").set(e.getMessage() != null ? e.getMessage() : "Unknown error");
            return new RuntimeScalar(0);
        }
    }

    /**
     * Create a hard link between two files
     * @param args RuntimeScalar array containing [oldfile, newfile]
     * @return RuntimeScalar with 1 for success, 0 for failure
     */
    public static RuntimeScalar link(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            return new RuntimeScalar(0);
        }

        String oldFile = RuntimeIO.resolvePath(args[0].toString()).toString();
        String newFile = RuntimeIO.resolvePath(args[1].toString()).toString();

        if (oldFile == null || newFile == null || oldFile.isEmpty() || newFile.isEmpty()) {
            return new RuntimeScalar(0);
        }

        try {
            if (IS_WINDOWS) {
                // Windows implementation using CreateHardLinkA via direct function call
                Object[] args_array = {newFile, oldFile, null};
                Object result = CREATE_HARD_LINK.invoke(Boolean.class, args_array);
                boolean success = (Boolean) result;
                return new RuntimeScalar(success ? 1 : 0);
            } else {
                // POSIX implementation using link() system call
                int result = PosixLibrary.INSTANCE.link(oldFile, newFile);
                return new RuntimeScalar(result == 0 ? 1 : 0);
            }
        } catch (Exception e) {
            return new RuntimeScalar(0);
        }
    }

    /**
     * Get parent process ID
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with parent process ID
     */
    public static RuntimeScalar getppid(int ctx, RuntimeBase... args) {
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
     * Get user ID (returns username-based hash on Windows)
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with user ID
     */
    public static RuntimeScalar getuid(int ctx, RuntimeBase... args) {
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
    public static RuntimeScalar geteuid(int ctx, RuntimeBase... args) {
        if (IS_WINDOWS) {
            // On Windows, effective UID is same as UID
            return getuid(ctx, args);
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.geteuid());
        }
    }

    /**
     * Get group ID (returns computer name hash on Windows)
     * @param args Unused (for API consistency)
     * @return RuntimeScalar with group ID
     */
    public static RuntimeScalar getgid(int ctx, RuntimeBase... args) {
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
    public static RuntimeScalar getegid(int ctx, RuntimeBase... args) {
        if (IS_WINDOWS) {
            // On Windows, effective GID is same as GID
            return getgid(ctx, args);
        } else {
            return new RuntimeScalar(PosixLibrary.INSTANCE.getegid());
        }
    }
}

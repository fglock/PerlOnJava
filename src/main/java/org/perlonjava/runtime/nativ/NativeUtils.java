package org.perlonjava.runtime.nativ;

import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMPosixInterface;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class NativeUtils {
    public static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    public static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    // FFM POSIX implementation (handles platform-specific logic internally)
    private static final FFMPosixInterface posix = FFMPosix.get();

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

            if (Files.exists(link)) {
                GlobalVariable.getGlobalVariable("main::!").set("File exists");
                return new RuntimeScalar(0);
            }

            Files.createSymbolicLink(link, target);

            return new RuntimeScalar(1);

        } catch (UnsupportedOperationException e) {
            throw new RuntimeException("Symbolic links are not implemented on this platform");
        } catch (IOException e) {
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("privilege")) {
                GlobalVariable.getGlobalVariable("main::!").set("Permission denied");
            } else if (errorMessage != null && errorMessage.contains("Access is denied")) {
                GlobalVariable.getGlobalVariable("main::!").set("Permission denied");
            } else if (errorMessage != null && errorMessage.contains("No such file")) {
                GlobalVariable.getGlobalVariable("main::!").set("No such file or directory");
            } else {
                GlobalVariable.getGlobalVariable("main::!").set(errorMessage != null ? errorMessage : "I/O error");
            }
            return new RuntimeScalar(0);
        } catch (SecurityException e) {
            GlobalVariable.getGlobalVariable("main::!").set("Permission denied");
            return new RuntimeScalar(0);
        } catch (Exception e) {
            GlobalVariable.getGlobalVariable("main::!").set(e.getMessage() != null ? e.getMessage() : "Unknown error");
            return new RuntimeScalar(0);
        }
    }

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
            int result = posix.link(oldFile, newFile);
            return new RuntimeScalar(result == 0 ? 1 : 0);
        } catch (Exception e) {
            return new RuntimeScalar(0);
        }
    }

    public static RuntimeScalar getppid(int ctx, RuntimeBase... args) {
        return new RuntimeScalar(posix.getppid());
    }

    public static RuntimeScalar getuid(int ctx, RuntimeBase... args) {
        return new RuntimeScalar(posix.getuid());
    }

    public static RuntimeScalar geteuid(int ctx, RuntimeBase... args) {
        return new RuntimeScalar(posix.geteuid());
    }

    public static RuntimeScalar getgid(int ctx, RuntimeBase... args) {
        return new RuntimeScalar(posix.getgid());
    }

    public static RuntimeScalar getegid(int ctx, RuntimeBase... args) {
        return new RuntimeScalar(posix.getegid());
    }
}

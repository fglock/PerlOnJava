package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UtimeOperator {

    private static final boolean IS_WINDOWS = NativeUtils.IS_WINDOWS;

    public static RuntimeScalar utime(int ctx, RuntimeBase... args) {
        // Flatten args into a list to handle utime(@array, $file) correctly.
        // Without flattening, @array arrives as a single RuntimeArray element
        // and args[0].scalar() returns the array length instead of the first element.
        var flat = new java.util.ArrayList<RuntimeScalar>();
        for (RuntimeBase arg : args) {
            for (RuntimeScalar s : arg) {
                flat.add(s);
            }
        }

        if (flat.size() < 3) {
            return new RuntimeScalar(0);
        }

        RuntimeScalar accessTimeArg = flat.get(0);
        RuntimeScalar modTimeArg = flat.get(1);

        boolean useCurrentTime = !accessTimeArg.getDefinedBoolean() && !modTimeArg.getDefinedBoolean();

        long accessTime;
        long modTime;

        if (useCurrentTime) {
            long currentTime = System.currentTimeMillis() / 1000;
            accessTime = currentTime;
            modTime = currentTime;
        } else {
            accessTime = accessTimeArg.getDefinedBoolean() ? (long) accessTimeArg.getDouble() : 0;
            modTime = modTimeArg.getDefinedBoolean() ? (long) modTimeArg.getDouble() : 0;
        }

        int successCount = 0;

        for (int i = 2; i < flat.size(); i++) {
            if (changeFileTimes(flat.get(i), accessTime, modTime)) {
                successCount++;
            }
        }

        return new RuntimeScalar(successCount);
    }

    private static boolean changeFileTimes(RuntimeScalar fileArg, long accessTime, long modTime) {
        try {
            if (fileArg.type == RuntimeScalarType.GLOB ||
                    fileArg.type == RuntimeScalarType.GLOBREFERENCE) {
                return changeFilehandleTimes(fileArg, accessTime, modTime);
            }

            String filename = RuntimeIO.sanitizePathname("utime", fileArg.toString());
            if (filename == null || filename.isEmpty()) {
                return false;
            }

            if (IS_WINDOWS) {
                return changeFileTimesWindows(filename, accessTime, modTime);
            } else {
                return changeFileTimesPosix(filename, accessTime, modTime);
            }

        } catch (Exception e) {
            return false;
        }
    }

    private static boolean changeFileTimesPosix(String filename, long accessTime, long modTime) {
        try {
            int result = FFMPosix.get().utimes(filename, accessTime, modTime);
            if (result == 0) return true;
            return changeFileTimesJava(filename, accessTime, modTime);
        } catch (Exception e) {
            return changeFileTimesJava(filename, accessTime, modTime);
        }
    }

    private static boolean changeFileTimesWindows(String filename, long accessTime, long modTime) {
        return changeFileTimesJava(filename, accessTime, modTime);
    }

    private static boolean changeFilehandleTimes(RuntimeScalar filehandle,
                                                 long accessTime, long modTime) {
        try {
            String filename = getFilenameFromHandle(filehandle);
            if (filename != null) {
                if (IS_WINDOWS) return changeFileTimesWindows(filename, accessTime, modTime);
                return changeFileTimesPosix(filename, accessTime, modTime);
            }
        } catch (Exception e) {
        }
        return false;
    }

    private static boolean changeFileTimesJava(String filename, long accessTime, long modTime) {
        try {
            Path path = Paths.get(filename);
            if (!Files.exists(path)) {
                return false;
            }

            long accessMillis = accessTime * 1000L;
            long modMillis = modTime * 1000L;

            Files.setLastModifiedTime(path,
                    java.nio.file.attribute.FileTime.fromMillis(modMillis));

            java.nio.file.attribute.BasicFileAttributeView view =
                    Files.getFileAttributeView(path,
                            java.nio.file.attribute.BasicFileAttributeView.class);

            if (view != null) {
                view.setTimes(
                        java.nio.file.attribute.FileTime.fromMillis(modMillis),
                        java.nio.file.attribute.FileTime.fromMillis(accessMillis),
                        null
                );
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int getFileDescriptor(RuntimeScalar filehandle) {
        return -1;
    }

    private static String getFilenameFromHandle(RuntimeScalar filehandle) {
        return null;
    }

    public static boolean supportsHighPrecision() {
        return true;
    }
}

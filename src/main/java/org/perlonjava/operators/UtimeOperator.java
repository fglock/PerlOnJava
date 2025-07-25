package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of Perl's utime operator for PerlOnJava
 */
public class UtimeOperator {

    /**
     * Implements Perl's utime operator
     * Changes the access and modification times on each file
     *
     * @param args RuntimeList containing access time, modification time, and file paths
     * @return RuntimeScalar with count of successfully changed files
     */
    public static RuntimeScalar utime(RuntimeBase... args) {
        if (args.length < 2) {
            // Need at least access time and modification time
            return new RuntimeScalar(0);
        }

        // Get access and modification times from first two arguments
        RuntimeScalar accessTimeArg = args[0].scalar();
        RuntimeScalar modTimeArg = args[1].scalar();

        // Check if both are undef (use current time)
        boolean useCurrentTime = !accessTimeArg.getDefinedBoolean() && !modTimeArg.getDefinedBoolean();

        long accessTimeMillis;
        long modTimeMillis;

        if (useCurrentTime) {
            // Both undef - use current time
            long currentTimeMillis = System.currentTimeMillis();
            accessTimeMillis = currentTimeMillis;
            modTimeMillis = currentTimeMillis;
        } else {
            // Convert times from seconds to milliseconds
            // If one is undef but not both, treat as 0
            accessTimeMillis = accessTimeArg.getDefinedBoolean() ?
                (long)(accessTimeArg.getDouble() * 1000) : 0;
            modTimeMillis = modTimeArg.getDefinedBoolean() ?
                (long)(modTimeArg.getDouble() * 1000) : 0;
        }

        int successCount = 0;

        // Process each file starting from index 2
        for (int i = 2; i < args.length; i++) {
            RuntimeBase arg = args[i];

            // Handle both scalar filenames and lists of filenames
            for (RuntimeScalar fileArg : arg) {
                if (changeFileTimes(fileArg, accessTimeMillis, modTimeMillis)) {
                    successCount++;
                }
            }
        }

        return new RuntimeScalar(successCount);
    }

    /**
     * Changes the access and modification times for a single file
     *
     * @param fileArg RuntimeScalar containing the file path or filehandle
     * @param accessTimeMillis Access time in milliseconds
     * @param modTimeMillis Modification time in milliseconds
     * @return true if successful, false otherwise
     */
    private static boolean changeFileTimes(RuntimeScalar fileArg,
                                         long accessTimeMillis,
                                         long modTimeMillis) {
        try {
            // Check if this is a filehandle (glob reference)
            if (fileArg.type == RuntimeScalarType.GLOB ||
                fileArg.type == RuntimeScalarType.GLOBREFERENCE) {
                // Handle filehandle case
                return changeFilehandleTimes(fileArg, accessTimeMillis, modTimeMillis);
            }

            // Regular filename case
            String filename = fileArg.toString();
            if (filename.isEmpty()) {
                return false;
            }

            Path path = Paths.get(filename);

            // Check if file exists
            if (!Files.exists(path)) {
                return false;
            }

            // Set modification time
            FileTime modTime = FileTime.from(modTimeMillis, TimeUnit.MILLISECONDS);
            Files.setLastModifiedTime(path, modTime);

            // Set access time (requires BasicFileAttributeView)
            BasicFileAttributeView view = Files.getFileAttributeView(path,
                BasicFileAttributeView.class);
            if (view != null) {
                FileTime accessTime = FileTime.from(accessTimeMillis, TimeUnit.MILLISECONDS);
                view.setTimes(modTime, accessTime, null); // null for creation time (don't change)
            }

            return true;

        } catch (IOException | SecurityException e) {
            // Failed to change times
            return false;
        } catch (Exception e) {
            // Any other exception
            return false;
        }
    }

    /**
     * Changes times for a filehandle (if supported)
     *
     * @param filehandle RuntimeScalar containing the filehandle
     * @param accessTimeMillis Access time in milliseconds
     * @param modTimeMillis Modification time in milliseconds
     * @return true if successful, false otherwise
     */
    private static boolean changeFilehandleTimes(RuntimeScalar filehandle,
                                               long accessTimeMillis,
                                               long modTimeMillis) {
        // Java doesn't have direct support for futimes(2) equivalent
        // We would need to extract the file path from the filehandle
        // For now, this is a placeholder that returns false

        // In a full implementation, we would:
        // 1. Extract the File object from the RuntimeGlob
        // 2. Get the path from the File
        // 3. Use the same logic as changeFileTimes

        // TODO: Implement filehandle support when RuntimeGlob structure is available
        return false;
    }

    /**
     * Utility method to check if the system supports changing file times
     *
     * @return true if the system supports utime operations
     */
    public static boolean isSupported() {
        try {
            // Try to get BasicFileAttributeView for temp directory
            Path tempPath = Paths.get(System.getProperty("java.io.tmpdir"));
            BasicFileAttributeView view = Files.getFileAttributeView(tempPath,
                BasicFileAttributeView.class);
            return view != null;
        } catch (Exception e) {
            return false;
        }
    }
}
package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;

/**
 * Implementation of Perl's chown operator for PerlOnJava
 */
public class ChownOperator {

    /**
     * Implements Perl's chown operator
     * Changes the owner and group of a list of files
     *
     * @param args RuntimeList containing uid, gid, and file paths
     * @return RuntimeScalar with count of successfully changed files
     */
    public static RuntimeScalar chown(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            // Need at least uid and gid
            return new RuntimeScalar(0);
        }

        // Get uid and gid from first two arguments
        RuntimeScalar uidArg = args[0].scalar();
        RuntimeScalar gidArg = args[1].scalar();

        // Convert to numeric values (-1 means don't change)
        int uid = uidArg.getDefinedBoolean() ? uidArg.getInt() : -1;
        int gid = gidArg.getDefinedBoolean() ? gidArg.getInt() : -1;

        // If both are -1, nothing to do
        if (uid == -1 && gid == -1) {
            return new RuntimeScalar(0);
        }

        int successCount = 0;

        // Process each file starting from index 2
        for (int i = 2; i < args.length; i++) {
            RuntimeBase arg = args[i];

            // Handle both scalar filenames and lists of filenames
            for (RuntimeScalar fileArg : arg) {
                boolean result = false;
                try {
                    // Check if this is a filehandle (glob reference)
                    if (fileArg.type == RuntimeScalarType.GLOB ||
                            fileArg.type == RuntimeScalarType.GLOBREFERENCE) {
                        // Handle filehandle case
                        result = changeFilehandleOwnership(fileArg, uid, gid);
                    } else {// Regular filename case
                        String filename = RuntimeIO.resolvePath(fileArg.toString()).toString();
                        if (!filename.isEmpty()) {// Different implementation for different OS
                            if (SystemUtils.osIsWindows()) {
                                result = changeOwnershipWindows(filename, uid, gid);
                            } else {
                                result = changeOwnershipUnix(filename, uid, gid);
                            }
                        }
                    }

                } catch (Exception e) {
                }
                if (result) {
                    successCount++;
                }
            }
        }

        return new RuntimeScalar(successCount);
    }

    /**
     * Changes ownership on Unix-like systems
     *
     * @param filename Path to the file
     * @param uid      User ID (-1 to leave unchanged)
     * @param gid      Group ID (-1 to leave unchanged)
     * @return true if successful, false otherwise
     */
    private static boolean changeOwnershipUnix(String filename, int uid, int gid) {
        try {
            // Check if file exists
            Path path = Paths.get(filename);
            if (!Files.exists(path)) {
                return false;
            }

            // Try Java NIO first (may not work without proper permissions)
            if (tryJavaNioChown(path, uid, gid)) {
                return true;
            }

            // Fall back to system command
            return trySystemChown(filename, uid, gid);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Attempts to use Java NIO to change ownership
     *
     * @param path Path object
     * @param uid  User ID (-1 to leave unchanged)
     * @param gid  Group ID (-1 to leave unchanged)
     * @return true if successful, false otherwise
     */
    private static boolean tryJavaNioChown(Path path, int uid, int gid) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(path,
                    PosixFileAttributeView.class);

            if (view == null) {
                return false;
            }

            UserPrincipalLookupService lookupService =
                    path.getFileSystem().getUserPrincipalLookupService();

            // Change owner if requested
            if (uid != -1) {
                try {
                    // This typically requires looking up username from uid
                    // For now, we'll skip this as it's complex
                    return false;
                } catch (Exception e) {
                    return false;
                }
            }

            // Change group if requested
            if (gid != -1) {
                try {
                    // This typically requires looking up group name from gid
                    // For now, we'll skip this as it's complex
                    return false;
                } catch (Exception e) {
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Uses system chown command to change ownership
     *
     * @param filename Path to the file
     * @param uid      User ID (-1 to leave unchanged)
     * @param gid      Group ID (-1 to leave unchanged)
     * @return true if successful, false otherwise
     */
    private static boolean trySystemChown(String filename, int uid, int gid) {
        try {
            RuntimeList cmdArgs = new RuntimeList();
            cmdArgs.elements.add(new RuntimeScalar("chown"));

            // Build ownership string
            StringBuilder ownership = new StringBuilder();
            if (uid != -1) {
                ownership.append(uid);
            }
            if (gid != -1) {
                ownership.append(":").append(gid);
            }

            // If we have something to change
            if (ownership.length() > 0) {
                cmdArgs.elements.add(new RuntimeScalar(ownership.toString()));
                cmdArgs.elements.add(new RuntimeScalar(filename));

                RuntimeScalar exitCode = SystemOperator.system(cmdArgs, false,
                        RuntimeContextType.SCALAR);
                return exitCode.getInt() == 0;
            }

            return false;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Changes ownership on Windows systems
     * Note: Windows file ownership works differently than Unix
     *
     * @param filename Path to the file
     * @param uid      User ID (not directly applicable on Windows)
     * @param gid      Group ID (not directly applicable on Windows)
     * @return true if successful, false otherwise
     */
    private static boolean changeOwnershipWindows(String filename, int uid, int gid) {
        // Windows doesn't use numeric UIDs/GIDs like Unix
        // File ownership is handled through Security Identifiers (SIDs)
        // This would require complex Windows API calls

        // For now, return false as Windows doesn't support Unix-style chown
        return false;
    }

    /**
     * Changes ownership for a filehandle (if supported)
     *
     * @param filehandle RuntimeScalar containing the filehandle
     * @param uid        User ID (-1 to leave unchanged)
     * @param gid        Group ID (-1 to leave unchanged)
     * @return true if successful, false otherwise
     */
    private static boolean changeFilehandleOwnership(RuntimeScalar filehandle,
                                                     int uid, int gid) {
        // Java doesn't have direct support for fchown(2) equivalent
        // We would need to extract the file path from the filehandle
        // For now, this is a placeholder that returns false

        // TODO: Implement filehandle support when RuntimeGlob structure is available
        return false;
    }

    /**
     * Checks if the current system supports chown operations
     *
     * @return true if chown is supported, false otherwise
     */
    public static boolean isSupported() {
        if (SystemUtils.osIsWindows()) {
            // Windows doesn't support Unix-style chown
            return false;
        }

        try {
            // Check if we can access PosixFileAttributeView
            Path tempPath = Paths.get(System.getProperty("java.io.tmpdir"));
            PosixFileAttributeView view = Files.getFileAttributeView(tempPath,
                    PosixFileAttributeView.class);
            return view != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if chown operations are restricted on this system
     * Equivalent to checking _PC_CHOWN_RESTRICTED
     *
     * @return true if chown is restricted (typical), false if unrestricted
     */
    public static boolean isChownRestricted() {
        // Most modern systems restrict chown to root/superuser
        // This is a safe default assumption
        return true;
    }
}
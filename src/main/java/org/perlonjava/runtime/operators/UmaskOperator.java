package org.perlonjava.runtime.operators;

import com.sun.jna.Platform;
import org.perlonjava.runtime.nativ.PosixLibrary;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Native implementation of Perl's umask operator using JNA
 * <p>
 * This implementation provides direct access to the system umask:
 * - On POSIX systems: Uses native umask() system call
 * - On Windows: Simulates umask behavior (Windows has no umask concept)
 * - No external process spawning
 * - Thread-safe implementation
 */
public class UmaskOperator {

    // Default umask value (022 = no write permission for group/others)
    private static final int DEFAULT_UMASK = 022;
    private static final boolean IS_WINDOWS = Platform.isWindows();
    // Windows simulation of umask (thread-safe)
    private static volatile int windowsSimulatedUmask = DEFAULT_UMASK;

    /**
     * Implements Perl's umask operator
     *
     * @param args RuntimeBase array containing the new umask value (optional)
     * @return RuntimeScalar with the previous umask value
     */
    public static RuntimeScalar umask(int ctx, RuntimeBase... args) {
        RuntimeList argList = new RuntimeList(args);

        if (IS_WINDOWS) {
            return umaskWindows(argList);
        } else {
            return umaskPosix(argList);
        }
    }

    /**
     * POSIX implementation using native umask() system call
     */
    private static RuntimeScalar umaskPosix(RuntimeList args) {
        try {
            int newMask;
            boolean hasNewMask = false;

            if (!args.elements.isEmpty() && args.elements.get(0).scalar().getDefinedBoolean()) {
                // Get new umask value
                RuntimeScalar maskArg = args.elements.get(0).scalar();

                // Handle both decimal and octal string input
                String maskStr = maskArg.toString();
                if (maskStr.startsWith("0") && maskStr.length() > 1) {
                    // Parse as octal
                    newMask = Integer.parseInt(maskStr, 8);
                } else {
                    // Get as integer (might already be decimal)
                    newMask = maskArg.getInt();
                }

                // Ensure mask is within valid range (0-0777)
                newMask &= 0777;
                hasNewMask = true;
            } else {
                // No argument - just get current umask
                // umask() always sets a value, so we need to call it twice
                // to get the current value without changing it
                int current = PosixLibrary.INSTANCE.umask(0);
                PosixLibrary.INSTANCE.umask(current); // Restore original
                return new RuntimeScalar(current);
            }

            // Set new umask and get previous value
            int previousMask = PosixLibrary.INSTANCE.umask(newMask);

            // Check Perl's special behavior: die if trying to restrict self
            if ((newMask & 0700) > 0) {
                // Restore previous umask before throwing
                PosixLibrary.INSTANCE.umask(previousMask);
                throw new PerlCompilerException("umask not implemented");
            }

            return new RuntimeScalar(previousMask);

        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            // JNA not available or native call failed
            throw new PerlCompilerException("Native umask not available: " + e.getMessage());
        }
    }

    /**
     * Windows implementation (simulation since Windows has no umask)
     * <p>
     * Windows uses ACLs instead of Unix permissions, so umask doesn't
     * directly apply. We simulate it for compatibility.
     */
    private static RuntimeScalar umaskWindows(RuntimeList args) {
        if (args.elements.isEmpty() || !args.elements.get(0).scalar().getDefinedBoolean()) {
            // Return current simulated umask
            return new RuntimeScalar(windowsSimulatedUmask);
        }

        // Get new umask value
        RuntimeScalar maskArg = args.elements.get(0).scalar();

        // Handle both decimal and octal string input
        String maskStr = maskArg.toString();
        int newMask;
        if (maskStr.startsWith("0") && maskStr.length() > 1) {
            // Parse as octal
            newMask = Integer.parseInt(maskStr, 8);
        } else {
            // Get as integer
            newMask = maskArg.getInt();
        }

        // Ensure mask is within valid range (0-0777)
        newMask &= 0777;

        // Check Perl's special behavior: die if trying to restrict self
        if ((newMask & 0700) > 0) {
            throw new PerlCompilerException("umask not implemented");
        }

        // Atomically update and return previous value
        int previousMask = windowsSimulatedUmask;
        windowsSimulatedUmask = newMask;

        return new RuntimeScalar(previousMask);
    }

    /**
     * Apply umask to file permissions
     * This is used by file creation operations to apply the umask
     *
     * @param permissions The desired permissions
     * @return The permissions after applying umask
     */
    public static int applyUmask(int permissions) {
        int currentMask = getCurrentUmask();
        return permissions & ~currentMask;
    }

    /**
     * Get current umask value without changing it
     * Useful for debugging and file operations
     *
     * @return Current umask value
     */
    public static int getCurrentUmask() {
        if (IS_WINDOWS) {
            return windowsSimulatedUmask;
        } else {
            try {
                // Get current umask by setting and immediately restoring
                int current = PosixLibrary.INSTANCE.umask(0);
                PosixLibrary.INSTANCE.umask(current);
                return current;
            } catch (Exception e) {
                // If native call fails, return default
                return DEFAULT_UMASK;
            }
        }
    }

    /**
     * Convert umask to human-readable string (like shell umask command)
     *
     * @param umask The umask value
     * @return Octal string representation (e.g., "022")
     */
    public static String umaskToString(int umask) {
        return String.format("%04o", umask & 0777);
    }

    /**
     * Convert permissions to symbolic notation considering umask
     *
     * @param permissions The full permissions
     * @param umask       The umask to apply
     * @return String like "rwxr-xr-x"
     */
    public static String permissionsToString(int permissions, int umask) {
        int effective = permissions & ~umask;

        // Owner permissions

        String sb = String.valueOf((effective & 0400) != 0 ? 'r' : '-') +
                ((effective & 0200) != 0 ? 'w' : '-') +
                ((effective & 0100) != 0 ? 'x' : '-') +

                // Group permissions
                ((effective & 040) != 0 ? 'r' : '-') +
                ((effective & 020) != 0 ? 'w' : '-') +
                ((effective & 010) != 0 ? 'x' : '-') +

                // Other permissions
                ((effective & 04) != 0 ? 'r' : '-') +
                ((effective & 02) != 0 ? 'w' : '-') +
                ((effective & 01) != 0 ? 'x' : '-');

        return sb;
    }

    /**
     * Reset umask to system default
     * Useful for testing or initialization
     */
    public static void resetToDefault() {
        if (IS_WINDOWS) {
            windowsSimulatedUmask = DEFAULT_UMASK;
        } else {
            try {
                PosixLibrary.INSTANCE.umask(DEFAULT_UMASK);
            } catch (Exception ignored) {
                // Best effort
            }
        }
    }
}

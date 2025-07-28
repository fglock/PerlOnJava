package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.GlobalVariable.setGlobalVariable;

/**
 * Implementation of Perl's umask operator for PerlOnJava
 */
public class UmaskOperator {

    // Store the current umask value for Windows simulation
    private static int currentUmask = 022; // Default umask value

    /**
     * Implements Perl's umask operator
     * @param args RuntimeBase array containing the new umask value (optional)
     * @return RuntimeScalar with the previous umask value
     */
    public static RuntimeScalar umask(RuntimeBase... args) {
        if (SystemUtils.osIsWindows()) {
            return umaskWindows(new RuntimeList(args));
        } else {
            return umaskUnix(new RuntimeList(args));
        }
    }

    /**
     * Implements umask for Unix-like systems (Linux, macOS)
     */
    private static RuntimeScalar umaskUnix(RuntimeList args) {
        try {
            if (args.elements.isEmpty()) {
                // Get current umask
                RuntimeScalar output = SystemOperator.systemCommand(
                        new RuntimeScalar("umask"),
                        RuntimeContextType.SCALAR
                ).scalar();
                String umaskStr = output.toString().trim();

                try {
                    // Parse octal string to integer
                    return new RuntimeScalar(Integer.parseInt(umaskStr, 8));
                } catch (NumberFormatException e) {
                    return RuntimeScalarCache.scalarUndef;
                }
            } else {
                // Set new umask and get previous value
                RuntimeScalar newUmask = args.elements.get(0).scalar();
                int umaskValue = newUmask.getInt();

                // First get current umask
                RuntimeScalar currentOutput = SystemOperator.systemCommand(
                        new RuntimeScalar("umask"),
                        RuntimeContextType.SCALAR
                ).scalar();
                String currentUmaskStr = currentOutput.toString().trim();
                int previousUmask;

                try {
                    previousUmask = Integer.parseInt(currentUmaskStr, 8);
                } catch (NumberFormatException e) {
                    previousUmask = 022; // Default if we can't parse
                }

                // Now set the new umask
                RuntimeList cmdArgs = new RuntimeList();
                cmdArgs.elements.add(new RuntimeScalar(String.format("umask %04o", umaskValue)));

                RuntimeScalar exitCode = SystemOperator.system(cmdArgs, false, RuntimeContextType.SCALAR);

                if (exitCode.getInt() != 0) {
                    // Check if trying to restrict access for self (EXPR & 0700) > 0
                    if ((umaskValue & 0700) > 0) {
                        throw new PerlCompilerException("umask not implemented");
                    }
                    return RuntimeScalarCache.scalarUndef;
                }

                // Update process umask using native call if available
                updateProcessUmask(umaskValue);

                return new RuntimeScalar(previousUmask);
            }
        } catch (Exception e) {
            // Check if trying to restrict access for self
            if (!args.elements.isEmpty()) {
                int umaskValue = args.elements.get(0).scalar().getInt();
                if ((umaskValue & 0700) > 0) {
                    throw new PerlCompilerException("umask not implemented");
                }
            }
            return RuntimeScalarCache.scalarUndef;
        }
    }

    /**
     * Simulates umask for Windows systems
     * Windows doesn't have umask, so we simulate it with a static variable
     */
    private static RuntimeScalar umaskWindows(RuntimeList args) {
        if (args.elements.isEmpty()) {
            // Return current simulated umask
            return new RuntimeScalar(currentUmask);
        } else {
            // Set new umask and return previous
            RuntimeScalar newUmask = args.elements.get(0).scalar();
            int newValue = newUmask.getInt();

            // Check if trying to restrict access for self (EXPR & 0700) > 0
            if ((newValue & 0700) > 0) {
                // throw new PerlCompilerException("umask not implemented");
            }

            int previousUmask = currentUmask;
            currentUmask = newValue & 0777; // Ensure it's within valid range

            return new RuntimeScalar(previousUmask);
        }
    }

    /**
     * Attempts to update the process umask using reflection to access native methods
     * This is a best-effort approach for Unix systems
     */
    private static void updateProcessUmask(int umaskValue) {
        try {
            // Try to use jnr-posix if available
            Class<?> posixClass = Class.forName("jnr.posix.POSIXFactory");
            Object posix = posixClass.getMethod("getPOSIX").invoke(null);
            posixClass.getMethod("umask", int.class).invoke(posix, umaskValue);
        } catch (Exception e) {
            // jnr-posix not available, fall back to system command
            // The umask has already been set via shell command above
        }
    }

    /**
     * Apply umask to file permissions
     * This is used by file creation operations to apply the umask
     */
    public static int applyUmask(int permissions) {
        if (SystemUtils.osIsWindows()) {
            // On Windows, simulate umask behavior
            return permissions & ~currentUmask;
        } else {
            // On Unix, the OS handles this automatically after umask is set
            // But we can still calculate it for consistency
            try {
                RuntimeScalar output = SystemOperator.systemCommand(
                        new RuntimeScalar("umask"),
                        RuntimeContextType.SCALAR
                ).scalar();
                String umaskStr = output.toString().trim();
                int umaskValue = Integer.parseInt(umaskStr, 8);

                return permissions & ~umaskValue;
            } catch (Exception e) {
                // Fall back to default umask
                return permissions & ~022;
            }
        }
    }

    /**
     * Get current umask value without changing it
     * Useful for debugging and file operations
     */
    public static int getCurrentUmask() {
        if (SystemUtils.osIsWindows()) {
            return currentUmask;
        } else {
            try {
                RuntimeScalar output = SystemOperator.systemCommand(
                        new RuntimeScalar("umask"),
                        RuntimeContextType.SCALAR
                ).scalar();
                String umaskStr = output.toString().trim();
                return Integer.parseInt(umaskStr, 8);
            } catch (Exception e) {
                return 022; // Default umask
            }
        }
    }
}
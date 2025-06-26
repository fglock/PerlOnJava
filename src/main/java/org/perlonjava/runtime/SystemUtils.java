package org.perlonjava.runtime;

/**
 * Utility class for system and platform-related operations.
 */
public final class SystemUtils {

    // Prevent instantiation
    private SystemUtils() {
    }

    /**
     * Checks if the current operating system is Windows.
     *
     * @return true if running on Windows, false otherwise
     */
    public static boolean osIsWindows() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase().startsWith("win");
    }

    /**
     * Gets the Perl-compatible OS name for $^O variable.
     * Returns a string normalized to match Perl conventions.
     *
     * @return the OS name suitable for Perl's $^O variable
     */
    public static String getPerlOsName() {
        String osName = System.getProperty("os.name", "unknown").toLowerCase();

        // Normalize OS name to match Perl conventions
        String perlOsName;
        if (osName.startsWith("win")) {
            perlOsName = "MSWin32";
        } else if (osName.startsWith("mac")) {
            perlOsName = "darwin";
        } else if (osName.contains("nix") || osName.contains("nux")) {
            perlOsName = "linux";
        } else if (osName.contains("sunos") || osName.contains("solaris")) {
            perlOsName = "solaris";
        } else if (osName.contains("aix")) {
            perlOsName = "aix";
        } else if (osName.contains("freebsd")) {
            perlOsName = "freebsd";
        } else if (osName.contains("openbsd")) {
            perlOsName = "openbsd";
        } else {
            // Use the raw OS name if we don't have a specific mapping
            perlOsName = osName.replace(" ", "");
        }

        return perlOsName;
    }
}
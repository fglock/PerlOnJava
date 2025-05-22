package org.perlonjava;

/**
 * Central configuration class for the Perl-to-Java compiler.
 * Contains constants that control compiler behavior and runtime settings.
 * <p>
 * Note: Configuration values can be set using the Configure.pl script.
 * For example, to set the Perl version, you can run:
 * <p>
 * ./Configure.pl -D perlVersion=v5.40.0
 * <p>
 * This will update the perlVersion constant in this configuration class.
 */
public final class Configuration {

    // Perl version information
    public static final String perlVersion = "v5.40.0";
    public static final String jarVersion = "3.0.0";

    // Prevent instantiation
    private Configuration() {
    }

    public static String getPerlVersionNoV() {
        return perlVersion.startsWith("v") ? perlVersion.substring(1) : perlVersion;
    }

    public static String getPerlVersionOld() {
        String versionNoV = getPerlVersionNoV();
        String[] parts = versionNoV.split("\\.");
        StringBuilder formattedVersion = new StringBuilder();

        // First part without padding
        formattedVersion.append(parts[0]).append(".");

        // Remaining parts with 3 digit padding
        for (int i = 1; i < parts.length; i++) {
            formattedVersion.append(String.format("%03d", Integer.parseInt(parts[i])));
        }

        return formattedVersion.toString();
    }
}

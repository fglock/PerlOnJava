package org.perlonjava.core;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/**
 * Central configuration class for the Perl-to-Java compiler.
 * Contains constants that control compiler behavior and runtime settings.
 * <p>
 * Configuration values are managed using the Configure.pl script:
 * <p>
 * ./Configure.pl -D version=5.43.0    # Update version everywhere
 * ./Configure.pl                       # Show current configuration
 * <p>
 * This will update the version constant in this class and replace all
 * occurrences of perlonjava-X.Y.Z.jar throughout the repository.
 * <p>
 * Git commit information (gitCommitId, gitCommitDate) is automatically
 * injected by the build system (Gradle or Maven) before compilation.
 * Do not edit these values manually - they will be overwritten on each build.
 */
public final class Configuration {

    /**
     * Unified version number for PerlOnJava.
     * This version is used for both the JAR artifact and Perl compatibility version.
     * Updated via: ./Configure.pl -D version=X.Y.Z
     */
    public static final String version = "5.42.0";

    /**
     * Git commit ID (short hash) of the build.
     * Automatically populated by Gradle/Maven during build.
     * DO NOT EDIT MANUALLY - this value is replaced at build time.
     */
    public static final String gitCommitId = "3652e0b19";

    /**
     * Git commit date of the build (ISO format: YYYY-MM-DD).
     * Automatically populated by Gradle/Maven during build.
     * DO NOT EDIT MANUALLY - this value is replaced at build time.
     */
    public static final String gitCommitDate = "2026-04-08";

    /**
     * Build timestamp in Perl 5 "Compiled at" format (e.g., "Apr  7 2026 11:20:00").
     * Automatically populated by Gradle during build.
     * Parsed by App::perlbrew and other tools via: perl -V | grep "Compiled at"
     * DO NOT EDIT MANUALLY - this value is replaced at build time.
     */
    public static final String buildTimestamp = "Apr  8 2026 09:23:25";

    // Prevent instantiation
    private Configuration() {
    }

    /**
     * Returns the version for use with "use VERSION" feature bundles.
     * For version 5.42.0, returns ":5.42"
     */
    public static String getPerlVersionBundle() {
        int lastDot = version.lastIndexOf('.');
        return ":" + version.substring(0, lastDot);
    }

    /**
     * Returns the version string without 'v' prefix.
     * Since version is already stored without 'v', this returns it directly.
     */
    public static String getPerlVersionNoV() {
        return version.startsWith("v") ? version.substring(1) : version;
    }

    /**
     * Returns the version in old Perl $] format (e.g., "5.042000" for 5.42.0).
     */
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

    /**
     * Returns the Perl version as a vstring RuntimeScalar.
     * For example, 5.42.0 becomes a vstring with bytes \u0005*\u0000
     * where each version component is represented as a character.
     *
     * @return RuntimeScalar with type VSTRING containing the version
     */
    public static RuntimeScalar getPerlVersionVString() {
        String versionNoV = getPerlVersionNoV();
        String[] parts = versionNoV.split("\\.");

        StringBuilder vstring = new StringBuilder();
        for (String part : parts) {
            int value = Integer.parseInt(part);
            vstring.append((char) value);
        }

        RuntimeScalar result = new RuntimeScalar(vstring.toString());
        result.type = RuntimeScalarType.VSTRING;
        return result;
    }
}

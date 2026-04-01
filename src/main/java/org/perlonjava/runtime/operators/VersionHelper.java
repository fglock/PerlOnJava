package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

public class VersionHelper {
    // Helper method to normalize version to a comparable decimal format for require
    private static String normalizeVersionToDecimalForRequire(String version) {
        // Remove v prefix if present
        String v = version.startsWith("v") ? version.substring(1) : version;
        
        // Handle underscore versions like 5.005_63 -> 5.00563
        v = v.replace("_", "");
        
        // Check if this is a dot-separated version like 5.42.0 (more than one dot)
        String[] parts = v.split("\\.");
        if (parts.length > 2 || (parts.length == 2 && version.startsWith("v"))) {
            // Multi-component version like 5.42.0 or v5.42.0 -> 5.042000
            StringBuilder normalized = new StringBuilder(parts[0]);
            normalized.append(".");
            for (int i = 1; i < parts.length; i++) {
                // Pad each component to 3 digits with leading zeros
                String part = parts[i];
                while (part.length() < 3) {
                    part = "0" + part;  // Pad with leading zeros
                }
                normalized.append(part);
            }
            // Ensure at least 6 decimal digits for consistency
            String decimalPart = normalized.substring(normalized.indexOf(".") + 1);
            while (decimalPart.length() < 6) {
                normalized.append("0");
                decimalPart = normalized.substring(normalized.indexOf(".") + 1);
            }
            return normalized.toString();
        } else if (parts.length == 1) {
            // Single number like "10" -> "10.000000"
            return v + ".000000";
        }
        
        // Already in decimal format like 5.042000
        return v;
    }

    // Helper method to normalize version for require comparison
    static String normalizeVersionForRequireComparison(RuntimeScalar versionScalar) {
        switch (versionScalar.type) {
            case VSTRING:
                // For VSTRING, extract the version components
                if (versionScalar.value instanceof String vstr) {
                    StringBuilder normalized = new StringBuilder();
                    for (int i = 0; i < vstr.length(); i++) {
                        if (i > 0) normalized.append(".");
                        normalized.append((int) vstr.charAt(i));
                    }
                    return normalizeVersionToDecimalForRequire("v" + normalized);
                }
                return normalizeVersionToDecimalForRequire(versionScalar.toString());
            case DOUBLE:
            case INTEGER:
                return normalizeVersionToDecimalForRequire(versionScalar.toString());
            default:
                return normalizeVersionToDecimalForRequire(versionScalar.toString());
        }
    }

    // Helper method to compare versions for require
    static boolean isVersionLessForRequire(String currentVersion, String requiredVersion) {
        String normalizedCurrent = normalizeVersionToDecimalForRequire(currentVersion);
        String normalizedRequired = normalizeVersionToDecimalForRequire(requiredVersion);

        try {
            double current = Double.parseDouble(normalizedCurrent);
            double required = Double.parseDouble(normalizedRequired);
            return current < required;
        } catch (NumberFormatException e) {
            return normalizedCurrent.compareTo(normalizedRequired) < 0;
        }
    }

    // Helper method to get display version string for require error messages
    static String getDisplayVersionForRequire(RuntimeScalar versionScalar) {
        switch (versionScalar.type) {
            case VSTRING:
                // For VSTRING like v5.42, display as "5.42.0"
                if (versionScalar.value instanceof String vstr) {
                    StringBuilder display = new StringBuilder();
                    for (int i = 0; i < vstr.length(); i++) {
                        if (i > 0) display.append(".");
                        display.append((int) vstr.charAt(i));
                    }
                    // Ensure at least 3 components for vstrings in display
                    String result = display.toString();
                    String[] parts = result.split("\\.");
                    if (parts.length == 2) {
                        result += ".0";
                    }
                    return result;
                }
                return versionScalar.toString();
            case DOUBLE:
                // For decimal versions, we need special handling for underscore versions
                String version = versionScalar.toString();

                // Check if this looks like a converted underscore version (e.g., 10.00002 from 10.000_02)
                if (version.matches("\\d+\\.\\d{5,}")) {
                    String[] parts = version.split("\\.");
                    if (parts.length == 2) {
                        String decimal = parts[1];
                        // For versions like 10.00002, we want 10.0.20
                        String minor = decimal.substring(0, 3);
                        String patch = decimal.substring(3);
                        // Remove leading zeros but keep at least one digit
                        minor = minor.replaceFirst("^0+", "");
                        if (minor.isEmpty()) minor = "0";
                        // For patch, if it's "02", we want "20", not "2"
                        if (patch.length() == 2 && patch.startsWith("0") && !patch.equals("00")) {
                            patch = patch.substring(1) + "0";  // "02" -> "20"
                        } else {
                            patch = patch.replaceFirst("^0+", "");
                            if (patch.isEmpty()) patch = "0";
                        }
                        return parts[0] + "." + minor + "." + patch;
                    }
                }

                if (version.contains(".")) {
                    String[] parts = version.split("\\.");
                    if (parts.length >= 2) {
                        // Format as major.minor.patch for display
                        StringBuilder display = new StringBuilder(parts[0]);
                        display.append(".");

                        if (parts.length == 2) {
                            // 10.2 -> 10.200.0 for display
                            String minor = parts[1];
                            if (minor.length() == 1) {
                                display.append(minor).append("00.0");
                            } else if (minor.length() == 2) {
                                display.append(minor).append("0.0");
                            } else {
                                display.append(minor).append(".0");
                            }
                        } else {
                            // 10.0.2 -> 10.0.2
                            for (int i = 1; i < parts.length; i++) {
                                if (i > 1) display.append(".");
                                display.append(parts[i]);
                            }
                        }
                        return display.toString();
                    }
                }
                return version;
            case INTEGER:
                return versionScalar + ".0.0";
            default:
                String ver = versionScalar.toString();
                // Handle underscore versions like 10.000_02 -> 10.0.20 for display
                if (ver.contains("_")) {
                    ver = ver.replace("_", "");
                    // Convert 10.00002 to 10.0.20 for display
                    if (ver.matches("\\d+\\.\\d{5,}")) {
                        String[] parts = ver.split("\\.");
                        if (parts.length == 2) {
                            String decimal = parts[1];
                            // For versions like 10.00002, we want 10.0.20
                            String minor = decimal.substring(0, 3);
                            String patch = decimal.substring(3);
                            // Remove leading zeros but keep at least one digit
                            minor = minor.replaceFirst("^0+", "");
                            if (minor.isEmpty()) minor = "0";
                            // For patch, if it's "02", we want "20", not "2"
                            if (patch.length() == 2 && patch.startsWith("0") && !patch.equals("00")) {
                                patch = patch.substring(1) + "0";  // "02" -> "20"
                            } else {
                                patch = patch.replaceFirst("^0+", "");
                                if (patch.isEmpty()) patch = "0";
                            }
                            return parts[0] + "." + minor + "." + patch;
                        }
                    }
                }
                return ver;
        }
    }

    public static RuntimeScalar compareVersion(RuntimeScalar hasVersion, RuntimeScalar wantVersion, String perlClassName) {
        // Use decimal comparison for correctness (handles 5.6 > 5.042 properly)
        String hasDecimal = normalizeVersionForRequireComparison(hasVersion);
        String wantDecimal = normalizeVersionForRequireComparison(wantVersion);
        if (wantVersion.getDefinedBoolean()) {
            if (isVersionLessForRequire(hasDecimal, wantDecimal)) {
                if (perlClassName.equals("Perl")) {
                    String wantDisplay = normalizeVersionWithPadding(wantVersion);
                    String hint = getDidYouMeanHint(wantVersion, wantDisplay);
                    throw new PerlCompilerException("Perl v" + wantDisplay + " required" + hint + "--this is only " + hasVersion.toString() + ", stopped");
                } else {
                    String hasStr = normalizeVersion(hasVersion);
                    String wantStr = normalizeVersion(wantVersion);
                    throw new PerlCompilerException(perlClassName + " version " + wantStr + " required--this is only version " + hasVersion);
                }
            }
        }
        return hasVersion;
    }

    /**
     * Implements "no VERSION" - fails if current Perl >= VERSION.
     */
    public static void compareVersionNoDeclaration(RuntimeScalar hasVersion, RuntimeScalar wantVersion) {
        String hasDecimal = normalizeVersionForRequireComparison(hasVersion);
        String wantDecimal = normalizeVersionForRequireComparison(wantVersion);
        if (wantVersion.getDefinedBoolean()) {
            try {
                double has = Double.parseDouble(hasDecimal);
                double want = Double.parseDouble(wantDecimal);
                if (has >= want) {
                    String wantDisplay = normalizeVersionWithPadding(wantVersion);
                    throw new PerlCompilerException("Perls since v" + wantDisplay + " too modern--this is " + hasVersion.toString() + ", stopped");
                }
            } catch (NumberFormatException e) {
                // fallback to string comparison
                if (hasDecimal.compareTo(wantDecimal) >= 0) {
                    String wantDisplay = normalizeVersionWithPadding(wantVersion);
                    throw new PerlCompilerException("Perls since v" + wantDisplay + " too modern--this is " + hasVersion.toString() + ", stopped");
                }
            }
        }
    }

    /**
     * Returns a "did you mean" hint for ambiguous decimal version specifiers.
     * E.g., "use 5.6" normalizes to v5.600.0 but the user likely meant v5.6.0.
     */
    private static String getDidYouMeanHint(RuntimeScalar wantVersion, String normalizedVersion) {
        if (wantVersion.type == VSTRING) return "";
        String verStr = wantVersion.toString();
        if (verStr.startsWith("v")) return "";
        String[] dotParts = verStr.split("\\.");
        if (dotParts.length != 2) return "";
        String decimalPart = dotParts[1];
        // If decimal part is 1-2 digits, the user probably meant v5.X.0 not v5.X00.0
        if (decimalPart.length() >= 1 && decimalPart.length() <= 2) {
            String[] normParts = normalizedVersion.split("\\.");
            if (normParts.length >= 2) {
                int minor = Integer.parseInt(normParts[1]);
                if (minor >= 100) {
                    // Strip leading zeros from the decimal part for display
                    String displayMinor = decimalPart.replaceFirst("^0+", "");
                    if (displayMinor.isEmpty()) displayMinor = "0";
                    return " (did you mean v" + dotParts[0] + "." + displayMinor + ".0?)";
                }
            }
        }
        return "";
    }

    /**
     * Normalizes a version with right-padding for Perl version display.
     * E.g., 5.6 -> 5.600.0, 5.04 -> 5.40.0, 5.042 -> 5.42.0
     */
    static String normalizeVersionWithPadding(RuntimeScalar wantVersion) {
        String normalizedVersion = wantVersion.toString();

        if (normalizedVersion.startsWith("v")) {
            normalizedVersion = normalizedVersion.substring(1);
        }
        if (wantVersion.type == RuntimeScalarType.VSTRING) {
            normalizedVersion = toDottedString(normalizedVersion);
            // Ensure at least 3 components
            String[] parts = normalizedVersion.split("\\.");
            if (parts.length == 2) {
                normalizedVersion += ".0";
            }
            return normalizedVersion;
        }

        normalizedVersion = normalizedVersion.replaceAll("_", "");
        String[] parts = normalizedVersion.split("\\.");
        if (parts.length < 3) {
            String major = parts[0];
            String minor = parts.length > 1 ? parts[1] : "0";
            // Right-pad minor to at least 3 digits (5.6 -> 600, 5.04 -> 040, 5.10 -> 100)
            while (minor.length() < 3) {
                minor = minor + "0";
            }
            String patch = minor.length() > 3 ? minor.substring(3) : "0";
            if (minor.length() > 3) {
                minor = minor.substring(0, 3);
            }
            if (patch.length() > 3) {
                patch = patch.substring(0, 3);
            }
            int majorNumber, minorNumber, patchNumber;
            try {
                majorNumber = Integer.parseInt(major);
                minorNumber = Integer.parseInt(minor);
                patchNumber = Integer.parseInt(patch);
            } catch (NumberFormatException e) {
                return "0.0.0";
            }
            return String.format("%d.%d.%d", majorNumber, minorNumber, patchNumber);
        }
        return normalizedVersion;
    }

    public static String normalizeVersion(RuntimeScalar wantVersion) {
        String normalizedVersion = wantVersion.toString();
        
        // Handle special case: "undef" is returned by MM_Unix.parse_version when no version found
        if (normalizedVersion.equals("undef") || normalizedVersion.isEmpty()) {
            return "0.0.0";
        }
        
        if (normalizedVersion.startsWith("v")) {
            normalizedVersion = normalizedVersion.substring(1);
        }
        if (wantVersion.type == RuntimeScalarType.VSTRING) {
            normalizedVersion = toDottedString(normalizedVersion);
        } else {
            normalizedVersion = normalizedVersion.replaceAll("_", "");
            String[] parts = normalizedVersion.split("\\.");
            if (parts.length < 3) {
                String major = parts[0];
                String minor = parts.length > 1 ? parts[1] : "0";
                // Right-pad minor with zeros to at least 3 chars.
                // In Perl's version system, decimal digits are grouped in 3s:
                // 0.01 -> "010" -> v0.10.0, not v0.1.0
                // 0.5  -> "500" -> v0.500.0
                while (minor.length() < 3) {
                    minor = minor + "0";
                }
                String patch = minor.length() > 3 ? minor.substring(3) : "0";
                if (minor.length() > 3) {
                    minor = minor.substring(0, 3);
                }
                if (patch.length() > 3) {
                    patch = patch.substring(0, 3);
                }
                // Handle non-numeric version parts gracefully
                int majorNumber;
                int minorNumber;
                int patchNumber;
                try {
                    majorNumber = Integer.parseInt(major);
                    minorNumber = Integer.parseInt(minor);
                    patchNumber = Integer.parseInt(patch);
                } catch (NumberFormatException e) {
                    // If version parts aren't numeric, return 0.0.0
                    return "0.0.0";
                }
                normalizedVersion = String.format("%d.%d.%d", majorNumber, minorNumber, patchNumber);
            }
        }
        return normalizedVersion;
    }

    public static String toDottedString(String input) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            int value = input.charAt(i);
            if (i > 0) {
                result.append(".");
            }
            result.append(value);
        }

        return result.toString();
    }

    /**
     * Checks if a version string is a lax version number.
     *
     * @param version The version string to check.
     * @return True if the version is a lax version number, false otherwise.
     */
    private static boolean isLaxVersion(String version) {
        // Implement a simple check for lax version numbers
        return version.matches("\\d+(\\.\\d+)*");
    }

    /**
     * Compares two version strings.
     *
     * @param v1 The first version string.
     * @param v2 The second version string.
     * @return A negative integer, zero, or a positive integer as the first version is less than, equal to, or greater than the second.
     */
    public static int compareVersions(String v1, String v2) {
        String[] v1Parts = v1.split("\\.");
        String[] v2Parts = v2.split("\\.");
        int length = Math.max(v1Parts.length, v2Parts.length);
        for (int i = 0; i < length; i++) {
            int v1Part = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2Part = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;
            if (v1Part != v2Part) {
                return v1Part - v2Part;
            }
        }
        return 0;
    }
}

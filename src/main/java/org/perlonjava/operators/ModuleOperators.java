package org.perlonjava.operators;

import org.perlonjava.CompilerOptions;
import org.perlonjava.Configuration;
import org.perlonjava.perlmodule.Universal;
import org.perlonjava.runtime.*;
import org.perlonjava.scriptengine.PerlLanguageProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.perlonjava.runtime.ExceptionFormatter.findInnermostCause;
import static org.perlonjava.runtime.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.RuntimeScalarType.*;

public class ModuleOperators {
    public static RuntimeScalar doFile(RuntimeScalar runtimeScalar) {
        return doFile(runtimeScalar, true, false); // do FILE always sets %INC and keeps it
    }

    private static RuntimeScalar doFile(RuntimeScalar runtimeScalar, boolean setINC, boolean isRequire) {
        // `do` file
        String fileName = runtimeScalar.toString();
        Path fullName = null;
        String code = null;
        String actualFileName = null;

        // Check if the filename is an absolute path or starts with ./ or ../
        Path filePath = Paths.get(fileName);
        if (filePath.isAbsolute() || fileName.startsWith("./") || fileName.startsWith("../")) {
            // For absolute or explicit relative paths, resolve using RuntimeIO.getPath
            filePath = RuntimeIO.resolvePath(fileName);
            fullName = Files.exists(filePath) ? filePath : null;
            actualFileName = fullName != null ? fullName.toString() : null;
        } else {
            // Otherwise, search in INC directories
            List<RuntimeScalar> inc = GlobalVariable.getGlobalArray("main::INC").elements;

            // Make sure the jar files are in @INC - the Perl test files can remove it
            boolean seen = false;
            for (RuntimeBase dir : inc) {
                if (dir.toString().equals(GlobalContext.JAR_PERLLIB)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                inc.add(new RuntimeScalar(GlobalContext.JAR_PERLLIB));
            }

            for (RuntimeBase dir : inc) {
                String dirName = dir.toString();
                if (dirName.equals(GlobalContext.JAR_PERLLIB)) {
                    // Try to find in jar at "src/main/perl/lib"
                    String resourcePath = "/lib/" + fileName;
                    URL resource = RuntimeScalar.class.getResource(resourcePath);
                    if (resource != null) {
                        String path = resource.getPath();
                        // Remove leading slash if on Windows
                        if (SystemUtils.osIsWindows() && path.startsWith("/")) {
                            path = path.substring(1);
                        }
                        fullName = Paths.get(path);
                        actualFileName = fullName.toString();

                        try (InputStream is = resource.openStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                            StringBuilder content = new StringBuilder();
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                content.append(line).append("\n");
                            }
                            code = content.toString();
                            break;
                        } catch (IOException e1) {
                            // Continue to next directory
                        }
                    }
                } else {
                    // Use RuntimeIO.getPath to properly resolve the directory path first
                    Path dirPath = RuntimeIO.resolvePath(dirName);
                    if (fileName.endsWith(".pm")) {
                        // Try to find a .pmc file
                        Path fullPath = dirPath.resolve(fileName + "c");
                        if (Files.exists(fullPath)) {
                            fullName = fullPath;
                            actualFileName = fullName.toString();
                            break;
                        }
                    }
                    Path fullPath = dirPath.resolve(fileName);
                    if (Files.exists(fullPath)) {
                        fullName = fullPath;
                        actualFileName = fullName.toString();
                        break;
                    }
                }
            }
        }

        if (fullName == null) {
            GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
            return new RuntimeScalar(); // return undef
        }

        CompilerOptions parsedArgs = new CompilerOptions();
        parsedArgs.fileName = actualFileName;
        if (code == null) {
            try {
                code = FileUtils.readFileWithEncodingDetection(Paths.get(parsedArgs.fileName), parsedArgs);
            } catch (IOException e) {
                GlobalVariable.setGlobalVariable("main::!", "Unable to read file " + parsedArgs.fileName);
                return new RuntimeScalar(); // return undef
            }
        }
        parsedArgs.code = code;

        // Set %INC if requested (before execution)
        if (setINC) {
            getGlobalHash("main::INC").put(fileName, new RuntimeScalar(parsedArgs.fileName));
        }

        RuntimeList result;
        try {
            result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
        } catch (Throwable t) {
            GlobalVariable.setGlobalVariable("main::@", "Error in file " + parsedArgs.fileName +
                    "\n" + findInnermostCause(t).getMessage());
            return new RuntimeScalar(); // return undef
        }

        RuntimeScalar finalResult = result == null ? scalarUndef : result.scalar();

        // For require, remove from %INC if result is false (but not if undef or error)
        if (isRequire && setINC && finalResult.defined().getBoolean() && !finalResult.getBoolean()) {
            getGlobalHash("main::INC").elements.remove(fileName);
        }

        return finalResult;
    }

    public static RuntimeScalar require(RuntimeScalar runtimeScalar, boolean moduleTrue) {
        // https://perldoc.perl.org/functions/require

        if (runtimeScalar.type == RuntimeScalarType.INTEGER || runtimeScalar.type == RuntimeScalarType.DOUBLE || runtimeScalar.type == RuntimeScalarType.VSTRING || runtimeScalar.type == RuntimeScalarType.BOOLEAN) {
            // `require VERSION` - use version comparison
            String currentVersionStr = Configuration.perlVersion;
            String displayVersion = getDisplayVersionForRequire(runtimeScalar);
            String normalizedRequired = normalizeVersionForRequireComparison(runtimeScalar);

            if (isVersionLessForRequire(currentVersionStr, normalizedRequired)) {
                throw new PerlCompilerException("Perl v" + displayVersion + " required");
            }
            return getScalarInt(1);
        }

        // Look up the file name in %INC
        String fileName = runtimeScalar.toString();
        if (getGlobalHash("main::INC").elements.containsKey(fileName)) {
            // module was already loaded
            return getScalarInt(1);
        }

        // Call doFile with require-specific behavior
        RuntimeScalar result = doFile(runtimeScalar, true, true);

        // Check if `do` returned undef (file not found or I/O error)
        if (!result.defined().getBoolean()) {
            String err = getGlobalVariable("main::@").toString();
            String ioErr = getGlobalVariable("main::!").toString();

            String message;
            if (err.isEmpty() && ioErr.isEmpty()) {
                if (!moduleTrue) {
                    message = fileName + " did not return a true value";
                } else {
                    // For moduleTrue, set %INC and return 1
                    getGlobalHash("main::INC").put(fileName, new RuntimeScalar(fileName));
                    return getScalarInt(1);
                }
            } else if (err.isEmpty()) {
                message = "Can't locate " + fileName + ": " + ioErr;
            } else {
                message = "Compilation failed in require: " + err;
            }

            throw new PerlCompilerException(message);
        }

        // Check if the result is false (0 or empty string)
        if (!result.getBoolean()) {
            if (!moduleTrue) {
                String message = fileName + " did not return a true value";
                throw new PerlCompilerException(message);
            } else {
                // For moduleTrue, restore %INC entry and return 1
                getGlobalHash("main::INC").put(fileName, new RuntimeScalar(fileName));
                return getScalarInt(1);
            }
        }

        // If moduleTrue is enabled, always return 1
        if (moduleTrue) {
            return getScalarInt(1);
        }

        return result;
    }

    // Helper method to normalize version to a comparable decimal format for require
    private static String normalizeVersionToDecimalForRequire(String version) {
        if (version.startsWith("v")) {
            // v-string like v5.42.0 -> 5.042000, v5.5.630 -> 5.005630
            String[] parts = version.substring(1).split("\\.");
            if (parts.length > 0) {
                StringBuilder normalized = new StringBuilder(parts[0]);
                if (parts.length > 1) {
                    normalized.append(".");
                    for (int i = 1; i < parts.length; i++) {
                        // Pad each component to 3 digits with leading zeros
                        String part = parts[i];
                        while (part.length() < 3) {
                            part = "0" + part;  // Pad with leading zeros
                        }
                        normalized.append(part);
                    }
                } else {
                    // Handle cases like "v5" -> "5.000000"
                    normalized.append(".000000");
                }
                return normalized.toString();
            }
        }
        // Handle underscore versions like 5.005_63 -> 5.005063
        return version.replace("_", "");
    }

    // Helper method to normalize version for require comparison
    private static String normalizeVersionForRequireComparison(RuntimeScalar versionScalar) {
        switch (versionScalar.type) {
            case VSTRING:
                // For VSTRING, extract the version components
                if (versionScalar.value instanceof String) {
                    String vstr = (String) versionScalar.value;
                    StringBuilder normalized = new StringBuilder();
                    for (int i = 0; i < vstr.length(); i++) {
                        if (i > 0) normalized.append(".");
                        normalized.append((int) vstr.charAt(i));
                    }
                    return normalizeVersionToDecimalForRequire("v" + normalized.toString());
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
    private static boolean isVersionLessForRequire(String currentVersion, String requiredVersion) {
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
    private static String getDisplayVersionForRequire(RuntimeScalar versionScalar) {
        switch (versionScalar.type) {
            case VSTRING:
                // For VSTRING like v5.42, display as "5.42.0"
                if (versionScalar.value instanceof String) {
                    String vstr = (String) versionScalar.value;
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
                return versionScalar.toString() + ".0.0";
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
}
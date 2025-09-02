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

public class ModuleOperators {
    public static RuntimeScalar doFile(RuntimeScalar runtimeScalar) {
        // `do` file
        String fileName = runtimeScalar.toString();
        Path fullName = null;
        String code = null;

        // Check if the filename is an absolute path or starts with ./ or ../
        Path filePath = Paths.get(fileName);
        if (filePath.isAbsolute() || fileName.startsWith("./") || fileName.startsWith("../")) {
            // For absolute or explicit relative paths, resolve using RuntimeIO.getPath
            filePath = RuntimeIO.resolvePath(fileName);
            fullName = Files.exists(filePath) ? filePath : null;
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
                    // System.out.println("Found resource " + resource);
                    if (resource != null) {

                        String path = resource.getPath();
                        // Remove leading slash if on Windows
                        if (SystemUtils.osIsWindows() && path.startsWith("/")) {
                            path = path.substring(1);
                        }
                        fullName = Paths.get(path);

                        try (InputStream is = resource.openStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                            StringBuilder content = new StringBuilder();
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                content.append(line).append("\n");
                            }
                            // System.out.println("Content of " + resourcePath + ": " + content.toString());
                            code = content.toString();
                            break;
                        } catch (IOException e1) {
                            //GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
                            //return new RuntimeScalar();
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
                            break;
                        }
                    }
                    Path fullPath = dirPath.resolve(fileName);
                    if (Files.exists(fullPath)) {
                        fullName = fullPath;
                        break;
                    }
                }
            }
        }
        if (fullName == null) {
            GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
            return new RuntimeScalar();
        }

        CompilerOptions parsedArgs = new CompilerOptions();
        parsedArgs.fileName = fullName.toString();
        if (code == null) {
            try {
                code = FileUtils.readFileWithEncodingDetection(Paths.get(parsedArgs.fileName), parsedArgs);
            } catch (IOException e) {
                GlobalVariable.setGlobalVariable("main::!", "Unable to read file " + parsedArgs.fileName);
                return new RuntimeScalar();
            }
        }
        parsedArgs.code = code;

        // set %INC
        getGlobalHash("main::INC").put(fileName, new RuntimeScalar(parsedArgs.fileName));

        RuntimeList result;
        try {
            result = PerlLanguageProvider.executePerlCode(parsedArgs, false);
        } catch (Throwable t) {
            GlobalVariable.setGlobalVariable("main::@", "Error in file " + parsedArgs.fileName +
                    "\n" + findInnermostCause(t).getMessage());
            return new RuntimeScalar();
        }

        return result == null ? scalarUndef : result.scalar();
    }

    public static RuntimeScalar require(RuntimeScalar runtimeScalar, boolean moduleTrue) {
        // https://perldoc.perl.org/functions/require

        if (runtimeScalar.type == RuntimeScalarType.INTEGER || runtimeScalar.type == RuntimeScalarType.DOUBLE || runtimeScalar.type == RuntimeScalarType.VSTRING || runtimeScalar.type == RuntimeScalarType.BOOLEAN) {
            // `require VERSION`
            Universal.compareVersion(
                    new RuntimeScalar(Configuration.perlVersion),
                    runtimeScalar,
                    "Perl");
            return getScalarInt(1);
        }

        // Look up the file name in %INC
        String fileName = runtimeScalar.toString();
        if (getGlobalHash("main::INC").elements.containsKey(fileName)) {
            // module was already loaded
            return getScalarInt(1);
        }

        // Call `do` operator
        RuntimeScalar result = doFile(runtimeScalar); // `do "fileName"`
        // Check if `do` returned a true value
        if (!result.defined().getBoolean()) {
            // `do FILE` returned undef
            String err = getGlobalVariable("main::@").toString();
            String ioErr = getGlobalVariable("main::!").toString();

            String message;
            if (err.isEmpty() && ioErr.isEmpty()) {
                if (!moduleTrue) {
                    message = fileName + " did not return a true value";
                } else {
                    // When moduleTrue is enabled, return 1 instead of the actual result
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
                // When moduleTrue is enabled, return 1 instead of the actual result
                return getScalarInt(1);
            }
        }

        // If moduleTrue is enabled, always return 1
        if (moduleTrue) {
            return getScalarInt(1);
        }

        return result;
    }
}
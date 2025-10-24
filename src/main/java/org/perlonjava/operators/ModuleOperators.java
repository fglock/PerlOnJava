package org.perlonjava.operators;

import org.perlonjava.CompilerOptions;
import org.perlonjava.Configuration;
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

import static org.perlonjava.perlmodule.Feature.featureManager;
import static org.perlonjava.runtime.ExceptionFormatter.findInnermostCause;
import static org.perlonjava.runtime.GlobalVariable.getGlobalHash;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

public class ModuleOperators {
    public static RuntimeBase doFile(RuntimeScalar runtimeScalar, int ctx) {
        return doFile(runtimeScalar, true, false, ctx); // do FILE always sets %INC and keeps it
    }

    private static RuntimeBase doFile(RuntimeScalar runtimeScalar, boolean setINC, boolean isRequire, int ctx) {
        // Clear error variables at start
        GlobalVariable.setGlobalVariable("main::@", "");
        GlobalVariable.setGlobalVariable("main::!", "");

        String fileName = runtimeScalar.toString();
        Path fullName = null;
        String code = null;
        String actualFileName = null;

        // Check if the argument is an ARRAY reference (for @INC filter with state support)
        if (runtimeScalar.type == RuntimeScalarType.REFERENCE &&
                runtimeScalar.value instanceof RuntimeArray) {
            // `do` ARRAY reference - array should contain [coderef, state...]
            RuntimeArray arr = (RuntimeArray) runtimeScalar.value;
            if (arr.size() > 0) {
                RuntimeScalar firstElem = arr.get(0);
                // The first element should be a CODE ref or filehandle
                if (firstElem.type == RuntimeScalarType.CODE ||
                        (firstElem.type == RuntimeScalarType.REFERENCE &&
                                firstElem.scalarDeref() != null &&
                                firstElem.scalarDeref().type == RuntimeScalarType.CODE) ||
                        firstElem.type == RuntimeScalarType.GLOB ||
                        firstElem.type == RuntimeScalarType.GLOBREFERENCE) {
                    // Recursively handle the first element
                    // Pass remaining array elements as potential state
                    RuntimeBase result = doFile(firstElem, setINC, isRequire, ctx);
                    return result;
                }
            }
        }
        // Check if the argument is a CODE reference (for @INC filter support)
        else if (runtimeScalar.type == RuntimeScalarType.CODE ||
                (runtimeScalar.type == RuntimeScalarType.REFERENCE &&
                        runtimeScalar.scalarDeref() != null &&
                        runtimeScalar.scalarDeref().type == RuntimeScalarType.CODE)) {
            // `do` CODE reference - execute the subroutine as an @INC filter
            // The subroutine should populate $_ with file content
            // Return value of 0 means EOF

            RuntimeCode codeRef = null;
            if (runtimeScalar.type == RuntimeScalarType.CODE) {
                codeRef = (RuntimeCode) runtimeScalar.value;
            } else {
                // For REFERENCE type, the value is already the RuntimeCode
                if (runtimeScalar.value instanceof RuntimeCode) {
                    codeRef = (RuntimeCode) runtimeScalar.value;
                } else {
                    RuntimeScalar deref = runtimeScalar.scalarDeref();
                    if (deref != null && deref.value instanceof RuntimeCode) {
                        codeRef = (RuntimeCode) deref.value;
                    }
                }
            }

            if (codeRef == null) {
                // Not a valid CODE reference
                code = null;
            } else {
                // Save current $_ 
                RuntimeScalar savedDefaultVar = GlobalVariable.getGlobalVariable("main::_");
                StringBuilder accumulatedCode = new StringBuilder();

                try {
                    // Call the CODE reference repeatedly as a generator
                    // Each call should populate $_ with the next chunk of code
                    // Continue until it returns 0/false (EOF)
                    RuntimeArray args = new RuntimeArray();
                    boolean continueReading = true;
                    
                    while (continueReading) {
                        GlobalVariable.getGlobalVariable("main::_").set("");
                        
                        // Call the CODE reference
                        RuntimeBase result = codeRef.apply(args, RuntimeContextType.SCALAR);
                        
                        // Get the content from $_
                        RuntimeScalar defaultVar = GlobalVariable.getGlobalVariable("main::_");
                        String chunk = defaultVar.toString();
                        
                        // Accumulate the chunk if not empty
                        if (!chunk.isEmpty()) {
                            accumulatedCode.append(chunk);
                        }
                        
                        // Check if we should continue
                        // Return value of 0/false means EOF
                        continueReading = result.scalar().getBoolean();
                    }
                    
                    code = accumulatedCode.toString();
                    if (code.isEmpty()) {
                        code = null;
                    }
                } catch (Exception e) {
                    // If there's an error executing the CODE ref, treat as no content
                    code = null;
                    throw e; // Re-throw to maintain error handling
                } finally {
                    // Restore $_
                    GlobalVariable.getGlobalVariable("main::_").set(savedDefaultVar.toString());
                }
            }
        } else if (runtimeScalar.type == RuntimeScalarType.GLOB || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE) {
            // `do` filehandle
            code = Readline.readline(runtimeScalar, RuntimeContextType.LIST).toString();
        } else {
            // `do` filename

            // Check if the filename is an absolute path or starts with ./ or ../
            Path filePath = Paths.get(fileName);
            if (filePath.isAbsolute() || fileName.startsWith("./") || fileName.startsWith("../")) {
                // For absolute or explicit relative paths, resolve using RuntimeIO.getPath
                filePath = RuntimeIO.resolvePath(fileName);
                if (Files.exists(filePath)) {
                    // Check if it's a directory
                    if (Files.isDirectory(filePath)) {
                        GlobalVariable.setGlobalVariable("main::!", "Is a directory");
                        return new RuntimeScalar(); // return undef
                    }
                    fullName = filePath;
                    actualFileName = fullName.toString();
                }
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
                            if (Files.exists(fullPath) && !Files.isDirectory(fullPath)) {
                                fullName = fullPath;
                                actualFileName = fullName.toString();
                                break;
                            }
                        }
                        Path fullPath = dirPath.resolve(fileName);
                        if (Files.exists(fullPath)) {
                            // Check if it's a directory
                            if (Files.isDirectory(fullPath)) {
                                // Continue searching in other @INC directories
                                continue;
                            }
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
        FeatureFlags outerFeature = featureManager;
        try {
            featureManager = new FeatureFlags();

            result = PerlLanguageProvider.executePerlCode(parsedArgs, false, ctx);

            boolean moduleTrue = featureManager.isFeatureEnabled("module_true");
            if (moduleTrue) {
                result = scalarTrue.getList();
            }
        } catch (Throwable t) {
            // For require, if there was a compilation failure, we need to handle %INC specially
            if (isRequire && setINC) {
                // Remove the entry we just added, we'll handle this in require() method
                getGlobalHash("main::INC").elements.remove(fileName);
            }
            GlobalVariable.setGlobalVariable("main::@", findInnermostCause(t).getMessage());
            return new RuntimeScalar(); // return undef
        } finally {
            featureManager = outerFeature;
        }

        // Return result based on context
        if (result == null) {
            if (ctx == RuntimeContextType.LIST) {
                return new RuntimeList();
            } else {
                return scalarUndef;
            }
        }

        RuntimeScalar scalarResult = result.scalar();

        // For require, remove from %INC if result is false (but not if undef or error)
        if (isRequire && setINC && scalarResult.defined().getBoolean() && !scalarResult.getBoolean()) {
            getGlobalHash("main::INC").elements.remove(fileName);
        }

        // Return appropriate result based on context
        if (ctx == RuntimeContextType.LIST) {
            return result;
        } else {
            return scalarResult;
        }
    }

    public static RuntimeScalar require(RuntimeScalar runtimeScalar) {
        // https://perldoc.perl.org/functions/require

        if (runtimeScalar.type == RuntimeScalarType.INTEGER || runtimeScalar.type == RuntimeScalarType.DOUBLE || runtimeScalar.type == RuntimeScalarType.VSTRING || runtimeScalar.type == RuntimeScalarType.BOOLEAN) {
            // `require VERSION` - use version comparison
            String currentVersionStr = Configuration.perlVersion;
            String displayVersion = VersionHelper.getDisplayVersionForRequire(runtimeScalar);
            String normalizedRequired = VersionHelper.normalizeVersionForRequireComparison(runtimeScalar);

            if (VersionHelper.isVersionLessForRequire(currentVersionStr, normalizedRequired)) {
                throw new PerlCompilerException("Perl v" + displayVersion + " required");
            }
            return getScalarInt(1);
        }

        // Look up the file name in %INC
        String fileName = runtimeScalar.toString();
        RuntimeHash incHash = getGlobalHash("main::INC");
        if (incHash.elements.containsKey(fileName)) {
            // Check if this was a compilation failure (stored as undef)
            RuntimeScalar incEntry = incHash.elements.get(fileName);
            if (!incEntry.defined().getBoolean()) {
                // This was a compilation failure, throw the cached error
                throw new PerlCompilerException("Compilation failed in require at " + fileName);
            }
            // module was already loaded successfully - always return exactly 1
            return getScalarInt(1);
        }

        // Call doFile with require-specific behavior - set %INC optimistically
        // require always runs in scalar context
        RuntimeBase baseResult = doFile(runtimeScalar, true, true, RuntimeContextType.SCALAR);
        RuntimeScalar result = baseResult.scalar();

        // Check if `do` returned undef (file not found or I/O error)
        if (!result.defined().getBoolean()) {
            String err = getGlobalVariable("main::@").toString();
            String ioErr = getGlobalVariable("main::!").toString();

            String message;
            if (err.isEmpty() && ioErr.isEmpty()) {
                // File executed but returned undef
                // For non-moduleTrue, undef means failure
                message = fileName + " did not return a true value";
                throw new PerlCompilerException(message);
            } else if (err.isEmpty()) {
                message = "Can't locate " + fileName + " in @INC";
                // Don't set %INC for file not found errors
                throw new PerlCompilerException(message);
            } else {
                message = "Compilation failed in require: " + err;
                // Set %INC as undef to mark compilation failure
                incHash.put(fileName, new RuntimeScalar());
                throw new PerlCompilerException(message);
            }
        }

        // Check if the result is false (0 or empty string but not undef)
        if (!result.getBoolean()) {
            // False values cause failure in require
            String message = fileName + " did not return a true value";
            // Remove from %INC since it didn't return true
            incHash.elements.remove(fileName);
            throw new PerlCompilerException(message);
        }

        // Success - %INC was already set by doFile
        // Return the actual result - doFile already applied module_true logic if needed
        // If module_true was enabled, result will be 1
        // If module_true was disabled, result will be the module's actual return value
        return result;
    }
}
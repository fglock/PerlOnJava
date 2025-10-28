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

/**
 * ModuleOperators implements Perl's module loading operators: `do`, `require`, and `use`.
 * 
 * <p>This class handles multiple forms of code loading:
 * <ul>
 *   <li><b>do FILE</b> - Executes a file without checking %INC</li>
 *   <li><b>do \&coderef</b> - Executes code reference as @INC filter (generator pattern)</li>
 *   <li><b>do [\&coderef, state...]</b> - @INC filter with state parameters</li>
 *   <li><b>do $filehandle</b> - Reads and executes from filehandle</li>
 *   <li><b>do [$filehandle, \&filter, state...]</b> - Filehandle with filter chain</li>
 *   <li><b>require FILE</b> - Loads module once, checks %INC, requires true value</li>
 *   <li><b>require VERSION</b> - Version checking</li>
 * </ul>
 * 
 * <h2>@INC Filter Support</h2>
 * <p>When a code reference is passed to `do`, it's called repeatedly as a generator:
 * <ul>
 *   <li>Each call should populate $_ with a chunk of code</li>
 *   <li>Return 0/false to signal EOF</li>
 *   <li>Return true to continue reading</li>
 *   <li>Optional state parameters are passed as @_ (starting at $_[1])</li>
 * </ul>
 * 
 * <h2>Error Handling</h2>
 * <p>Errors are stored in special variables:
 * <ul>
 *   <li><b>$@</b> - Compilation/execution errors</li>
 *   <li><b>$!</b> - I/O errors (file not found, permissions, etc.)</li>
 * </ul>
 * 
 * @see <a href="https://perldoc.perl.org/functions/do">perldoc do</a>
 * @see <a href="https://perldoc.perl.org/functions/require">perldoc require</a>
 */
public class ModuleOperators {
    
    /**
     * Public entry point for `do` operator.
     * 
     * <p>Always sets %INC and keeps the entry regardless of execution result.
     * This differs from `require` which removes %INC entries on failure.
     * 
     * @param runtimeScalar The file, coderef, filehandle, or array reference to execute
     * @param ctx Execution context (scalar or list)
     * @return Result of execution (undef on error)
     */
    public static RuntimeBase doFile(RuntimeScalar runtimeScalar, int ctx) {
        return doFile(runtimeScalar, true, false, ctx); // do FILE always sets %INC and keeps it
    }

    /**
     * Internal implementation of `do` and `require` operators.
     * 
     * <p>This method handles the complex dispatch logic for different argument types:
     * 
     * <h3>1. Array Reference: [\&coderef, state...]</h3>
     * <p>When the first element is a code reference:
     * <ul>
     *   <li>Extract the coderef from array[0]</li>
     *   <li>Pass array[1..N] as state parameters to the coderef</li>
     *   <li>Call coderef repeatedly until it returns false</li>
     *   <li>Each call populates $_ with code chunks</li>
     * </ul>
     * 
     * <h3>2. Code Reference: \&generator</h3>
     * <p>When a coderef is passed directly:
     * <ul>
     *   <li>Call repeatedly as generator (no state parameters)</li>
     *   <li>Each call should set $_ to next chunk</li>
     *   <li>Return false to signal EOF</li>
     * </ul>
     * 
     * <h3>3. Filehandle: $fh</h3>
     * <p>Read entire contents from filehandle and execute.
     * 
     * <h3>4. Filename: "Module/Name.pm"</h3>
     * <p>Standard file loading:
     * <ul>
     *   <li>Search @INC directories</li>
     *   <li>Check for .pmc (compiled) version first</li>
     *   <li>Read file and execute</li>
     * </ul>
     * 
     * @param runtimeScalar The argument to do/require
     * @param setINC Whether to set %INC entry for this file
     * @param isRequire True if called from require (affects %INC cleanup on failure)
     * @param ctx Execution context (scalar or list)
     * @return Result of execution (undef on error, with $@ or $! set)
     */
    private static RuntimeBase doFile(RuntimeScalar runtimeScalar, boolean setINC, boolean isRequire, int ctx) {
        // Clear error variables at start
        GlobalVariable.setGlobalVariable("main::@", "");
        GlobalVariable.setGlobalVariable("main::!", "");

        String fileName = runtimeScalar.toString();
        Path fullName = null;
        String code = null;
        String actualFileName = null;

        // Variables for handling array references with state
        RuntimeCode codeRef = null;
        RuntimeArray stateArgs = null;
        
        // Variable for storing @INC hook reference
        RuntimeScalar incHookRef = null;

        // ===== STEP 1: Handle ARRAY reference =====
        // Array format: [coderef|filehandle, state...]
        if (runtimeScalar.type == RuntimeScalarType.ARRAYREFERENCE &&
                runtimeScalar.value instanceof RuntimeArray) {
            RuntimeArray arr = (RuntimeArray) runtimeScalar.value;
            if (arr.size() > 0) {
                RuntimeScalar firstElem = arr.get(0);
                
                // Case 1a: Array with CODE reference [&coderef, state...]
                // Extract coderef and state parameters for later execution
                if (firstElem.type == RuntimeScalarType.CODE ||
                        (firstElem.type == RuntimeScalarType.REFERENCE &&
                                firstElem.scalarDeref() != null &&
                                firstElem.scalarDeref().type == RuntimeScalarType.CODE)) {
                    // Extract the coderef from first element
                    if (firstElem.type == RuntimeScalarType.CODE) {
                        codeRef = (RuntimeCode) firstElem.value;
                    } else if (firstElem.value instanceof RuntimeCode) {
                        codeRef = (RuntimeCode) firstElem.value;
                    } else {
                        RuntimeScalar deref = firstElem.scalarDeref();
                        if (deref != null && deref.value instanceof RuntimeCode) {
                            codeRef = (RuntimeCode) deref.value;
                        }
                    }
                    
                    // Create arguments array from remaining elements (state parameters)
                    // These will be passed as @_ to the coderef
                    // Note: $_[0] is reserved for filename (undef for generators), state starts at $_[1]
                    stateArgs = new RuntimeArray();
                    stateArgs.push(new RuntimeScalar());  // $_[0] = undef (filename placeholder)
                    for (int i = 1; i < arr.size(); i++) {
                        stateArgs.push(arr.get(i));       // $_[1..N] = state parameters
                    }
                    // Fall through to CODE handling below
                }
                // Case 1b: Array with filehandle [$fh, &filter, state...]
                // Read from filehandle, apply filter if present, then execute
                else if (firstElem.type == RuntimeScalarType.GLOB ||
                        firstElem.type == RuntimeScalarType.GLOBREFERENCE) {
                    // Read content from filehandle
                    code = Readline.readline(firstElem, RuntimeContextType.LIST).toString();
                    
                    // Check if there's a filter (second element)
                    if (arr.size() > 1) {
                        RuntimeScalar secondElem = arr.get(1);
                        if (secondElem.type == RuntimeScalarType.CODE ||
                                (secondElem.type == RuntimeScalarType.REFERENCE &&
                                        secondElem.scalarDeref() != null &&
                                        secondElem.scalarDeref().type == RuntimeScalarType.CODE)) {
                            // Extract filter coderef
                            RuntimeCode filterRef = null;
                            if (secondElem.type == RuntimeScalarType.CODE) {
                                filterRef = (RuntimeCode) secondElem.value;
                            } else if (secondElem.value instanceof RuntimeCode) {
                                filterRef = (RuntimeCode) secondElem.value;
                            } else {
                                RuntimeScalar deref = secondElem.scalarDeref();
                                if (deref != null && deref.value instanceof RuntimeCode) {
                                    filterRef = (RuntimeCode) deref.value;
                                }
                            }
                            
                            if (filterRef != null) {
                                // Apply filter to the content
                                RuntimeScalar savedDefaultVar = GlobalVariable.getGlobalVariable("main::_");
                                try {
                                    // Set $_ to the content
                                    GlobalVariable.getGlobalVariable("main::_").set(code);
                                    
                                    // Build filter args: $_[0] = undef, $_[1..N] = state
                                    RuntimeArray filterArgs = new RuntimeArray();
                                    filterArgs.push(new RuntimeScalar());  // $_[0] = undef
                                    for (int i = 2; i < arr.size(); i++) {
                                        filterArgs.push(arr.get(i));  // $_[1..N] = state
                                    }
                                    
                                    // Call the filter
                                    filterRef.apply(filterArgs, RuntimeContextType.SCALAR);
                                    
                                    // Get modified content from $_
                                    code = GlobalVariable.getGlobalVariable("main::_").toString();
                                } finally {
                                    // Restore $_
                                    GlobalVariable.getGlobalVariable("main::_").set(savedDefaultVar.toString());
                                }
                            }
                        }
                    }
                    // Continue to execution phase with the (possibly filtered) code
                }
            }
        }
        
        // ===== STEP 2: Handle direct CODE reference =====
        // Check if the argument is a CODE reference (not already extracted from array)
        if (codeRef == null && (runtimeScalar.type == RuntimeScalarType.CODE ||
                (runtimeScalar.type == RuntimeScalarType.REFERENCE &&
                        runtimeScalar.scalarDeref() != null &&
                        runtimeScalar.scalarDeref().type == RuntimeScalarType.CODE))) {
            // Extract the coderef
            if (runtimeScalar.type == RuntimeScalarType.CODE) {
                codeRef = (RuntimeCode) runtimeScalar.value;
            } else {
                if (runtimeScalar.value instanceof RuntimeCode) {
                    codeRef = (RuntimeCode) runtimeScalar.value;
                } else {
                    RuntimeScalar deref = runtimeScalar.scalarDeref();
                    if (deref != null && deref.value instanceof RuntimeCode) {
                        codeRef = (RuntimeCode) deref.value;
                    }
                }
            }
            
            // Create args with filename placeholder if not already set (no state for direct coderef)
            if (stateArgs == null) {
                stateArgs = new RuntimeArray();
                stateArgs.push(new RuntimeScalar());  // $_[0] = undef (filename placeholder)
            }
        }

        // ===== STEP 3: Execute CODE reference as generator =====
        // This handles both array-extracted and direct code references
        if (codeRef != null) {
            RuntimeScalar savedDefaultVar = GlobalVariable.getGlobalVariable("main::_");
            StringBuilder accumulatedCode = new StringBuilder();

            try {
                // Generator pattern: call repeatedly until false is returned
                // Each call should populate $_ with a chunk of code
                // State parameters (if any) are passed as @_
                boolean continueReading = true;
                
                while (continueReading) {
                    // Clear $_ before each call
                    GlobalVariable.getGlobalVariable("main::_").set("");
                    
                    // Call the CODE reference with state arguments
                    // The coderef should populate $_ with content
                    RuntimeBase result = codeRef.apply(stateArgs, RuntimeContextType.SCALAR);
                    
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
                // Restore $_ to its previous value
                GlobalVariable.getGlobalVariable("main::_").set(savedDefaultVar.toString());
            }
        } 
        // ===== STEP 4: Handle filehandle =====
        else if (runtimeScalar.type == RuntimeScalarType.GLOB || runtimeScalar.type == RuntimeScalarType.GLOBREFERENCE) {
            // Read entire contents from filehandle
            code = Readline.readline(runtimeScalar, RuntimeContextType.LIST).toString();
        } 
        // ===== STEP 5: Handle filename (standard file loading) =====
        // Only process as filename if code hasn't been set yet
        else if (code == null) {

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
                    RuntimeScalar dirScalar = dir.scalar();
                    
                    // Check if this @INC entry is a CODE reference, ARRAY reference, or blessed object
                    if (dirScalar.type == RuntimeScalarType.CODE || 
                        dirScalar.type == RuntimeScalarType.REFERENCE ||
                        dirScalar.type == RuntimeScalarType.ARRAYREFERENCE ||
                        dirScalar.type == RuntimeScalarType.HASHREFERENCE) {
                        
                        RuntimeBase hookResult = tryIncHook(dirScalar, fileName);
                        if (hookResult != null) {
                            // Hook returned something useful
                            RuntimeScalar hookResultScalar = hookResult.scalar();
                            
                            // Check if it's a filehandle (GLOB) or array ref with filehandle
                            RuntimeScalar filehandle = null;
                            
                            if (hookResultScalar.type == RuntimeScalarType.GLOB || 
                                hookResultScalar.type == RuntimeScalarType.GLOBREFERENCE) {
                                filehandle = hookResultScalar;
                            } else if (hookResultScalar.type == RuntimeScalarType.ARRAYREFERENCE &&
                                    hookResultScalar.value instanceof RuntimeArray) {
                                RuntimeArray resultArray = (RuntimeArray) hookResultScalar.value;
                                if (resultArray.size() > 0) {
                                    RuntimeScalar firstElem = resultArray.get(0);
                                    if (firstElem.type == RuntimeScalarType.GLOB || 
                                        firstElem.type == RuntimeScalarType.GLOBREFERENCE) {
                                        filehandle = firstElem;
                                    }
                                }
                            }
                            
                            if (filehandle != null) {
                                // Read content from the filehandle using the same method as STEP 4
                                try {
                                    code = Readline.readline(filehandle, RuntimeContextType.LIST).toString();
                                    actualFileName = fileName;
                                    incHookRef = dirScalar;
                                    break;
                                } catch (Exception e) {
                                    // Continue to next @INC entry
                                }
                            }
                        }
                        // If hook returned undef or we couldn't use the result, continue to next @INC entry
                        continue;
                    }
                    
                    // Original string handling for directory paths
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

            if (fullName == null && code == null) {
                GlobalVariable.setGlobalVariable("main::!", "No such file or directory");
                return new RuntimeScalar(); // return undef
            }
        }

        CompilerOptions parsedArgs = new CompilerOptions();
        parsedArgs.fileName = actualFileName;
        parsedArgs.incHook = incHookRef;
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
            // Check if the hook already set %INC to a custom value
            RuntimeHash incHash = getGlobalHash("main::INC");
            RuntimeScalar existingIncValue = incHash.elements.get(fileName);
            
            // Only set %INC if the hook didn't already set it
            if (existingIncValue == null || !existingIncValue.defined().getBoolean()) {
                // If we used an @INC hook, store the hook reference; otherwise store the filename
                RuntimeScalar incValue = (parsedArgs.incHook != null) 
                    ? parsedArgs.incHook 
                    : new RuntimeScalar(parsedArgs.fileName);
                incHash.put(fileName, incValue);
            } else if (parsedArgs.incHook != null) {
                // Hook set %INC to a custom value - use that for actualFileName if it's a string
                if (existingIncValue.type == RuntimeScalarType.STRING) {
                    parsedArgs.fileName = existingIncValue.toString();
                }
            }
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

    /**
     * Implements Perl's `require` operator.
     * 
     * <p>The `require` operator has two distinct behaviors:
     * 
     * <h3>1. Version Checking: require VERSION</h3>
     * <p>When given a numeric or vstring value:
     * <ul>
     *   <li>Compare against current Perl version</li>
     *   <li>Throw exception if version requirement not met</li>
     *   <li>Return 1 if version is sufficient</li>
     * </ul>
     * 
     * <h3>2. Module Loading: require MODULE</h3>
     * <p>When given a string (module name or filename):
     * <ul>
     *   <li>Check if already loaded in %INC (return 1 if so)</li>
     *   <li>Search @INC directories for the file</li>
     *   <li>Compile and execute the file</li>
     *   <li>Require that execution returns a true value</li>
     *   <li>Add entry to %INC on success</li>
     *   <li>Remove from %INC or mark as undef on failure</li>
     * </ul>
     * 
     * <h3>Error Handling</h3>
     * <p>`require` is stricter than `do`:
     * <ul>
     *   <li>Throws exception if file not found</li>
     *   <li>Throws exception if compilation fails</li>
     *   <li>Throws exception if module returns false value</li>
     *   <li>Marks compilation failures as undef in %INC (cached failure)</li>
     * </ul>
     * 
     * @param runtimeScalar Module name, filename, or version to require
     * @return Always returns 1 on success (or throws exception)
     * @throws PerlCompilerException if version insufficient, file not found, 
     *         compilation fails, or module returns false
     * @see <a href="https://perldoc.perl.org/functions/require">perldoc require</a>
     */
    public static RuntimeScalar require(RuntimeScalar runtimeScalar) {
        // https://perldoc.perl.org/functions/require

        // ===== CASE 1: Version checking =====
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

    /**
     * Try to call an @INC hook to load a module.
     * 
     * <p>@INC can contain:
     * <ul>
     *   <li>CODE reference: call it with ($coderef, $filename)</li>
     *   <li>ARRAY reference: call $array->[0] with ($array, $filename)</li>
     *   <li>Blessed object: call $obj->INC($filename) if the method exists</li>
     * </ul>
     * 
     * <p>The hook can return:
     * <ul>
     *   <li>undef: this hook can't handle it, continue to next @INC entry</li>
     *   <li>A filehandle: read the module code from this filehandle</li>
     *   <li>An array ref [$fh, \&filter, state...]: filehandle with optional filter and state</li>
     * </ul>
     * 
     * @param hook The @INC hook (CODE, ARRAY, or blessed reference)
     * @param fileName The file name being required
     * @return The result from the hook (undef, filehandle, or array ref), or null if hook can't be called
     */
    private static RuntimeBase tryIncHook(RuntimeScalar hook, String fileName) {
        RuntimeCode codeRef = null;
        RuntimeScalar selfArg = hook;
        
        // First check if it's a blessed object (takes priority over plain refs)
        int blessIdInt = RuntimeScalarType.blessedId(hook);
        
        // Case 1: Blessed object - try to call INC method
        if (blessIdInt != 0) {
            String blessId = NameNormalizer.getBlessStr(blessIdInt);
            if (blessId != null && !blessId.equals("main")) {
                // Try to find the INC method or AUTOLOAD
                try {
                    // Try direct INC method first
                    RuntimeScalar method = GlobalVariable.getGlobalCodeRef(blessId + "::INC");
                    if (method.defined().getBoolean() && method.type == RuntimeScalarType.CODE) {
                        codeRef = (RuntimeCode) method.value;
                    } else {
                        // Try AUTOLOAD
                        method = GlobalVariable.getGlobalCodeRef(blessId + "::AUTOLOAD");
                        if (method.defined().getBoolean() && method.type == RuntimeScalarType.CODE) {
                            // Set up $AUTOLOAD variable
                            GlobalVariable.getGlobalVariable(blessId + "::AUTOLOAD").set(blessId + "::INC");
                            codeRef = (RuntimeCode) method.value;
                        }
                    }
                } catch (Exception e) {
                    // Method not found, return null
                    return null;
                }
            }
        }
        // Case 2: CODE reference
        else if (hook.type == RuntimeScalarType.CODE) {
            codeRef = (RuntimeCode) hook.value;
        }
        // Case 3: REFERENCE to CODE
        else if (hook.type == RuntimeScalarType.REFERENCE && hook.value instanceof RuntimeCode) {
            codeRef = (RuntimeCode) hook.value;
        }
        // Case 4: ARRAY reference (not blessed) - call first element as coderef with array as $self
        else if (hook.type == RuntimeScalarType.ARRAYREFERENCE && hook.value instanceof RuntimeArray) {
            RuntimeArray arr = (RuntimeArray) hook.value;
            if (arr.size() > 0) {
                RuntimeScalar firstElem = arr.get(0);
                if (firstElem.type == RuntimeScalarType.CODE) {
                    codeRef = (RuntimeCode) firstElem.value;
                } else if (firstElem.type == RuntimeScalarType.REFERENCE && firstElem.value instanceof RuntimeCode) {
                    codeRef = (RuntimeCode) firstElem.value;
                }
            }
        }
        
        if (codeRef == null) {
            return null;
        }
        
        // Call the hook with ($self, $filename)
        RuntimeArray args = new RuntimeArray();
        args.push(selfArg);
        args.push(new RuntimeScalar(fileName));
        
        try {
            RuntimeBase result = codeRef.apply(args, RuntimeContextType.SCALAR);
            
            // If result is undef, return null to continue to next @INC entry
            if (result == null || !result.scalar().defined().getBoolean()) {
                return null;
            }
            
            return result;
        } catch (Exception e) {
            // If hook throws an exception, continue to next @INC entry
            return null;
        }
    }
}
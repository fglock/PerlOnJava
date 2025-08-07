package org.perlonjava.runtime;

import org.perlonjava.codegen.CustomClassLoader;
import org.perlonjava.parser.DataSection;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.RuntimeScalarType.GLOB;
import static org.perlonjava.runtime.RuntimeScalarType.GLOBREFERENCE;

/**
 * The GlobalVariable class manages global variables, arrays, hashes, and references
 * within the runtime environment. It provides methods to retrieve, set, and check
 * the existence of these global entities, initializing them as necessary.
 */
public class GlobalVariable {
    // Global variables and subroutines
    static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    static final Map<String, RuntimeHash> globalHashes = new HashMap<>();
    static final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();
    static final Map<String, RuntimeGlob> globalIORefs = new HashMap<>();

    // Global class loader for all generated classes - not final so we can replace it
    public static CustomClassLoader globalClassLoader =
            new CustomClassLoader(GlobalVariable.class.getClassLoader());

    // Cache for package existence checks
    private static final Map<String, Boolean> packageExistsCache = new HashMap<>();

    // Flags used by operator override

    // globalGlobs: Tracks typeglob assignments (e.g., *CORE::GLOBAL::hex = sub {...})
    // Used to detect when built-in operators have been globally overridden
    static final Map<String, Boolean> globalGlobs = new HashMap<>();

    // isSubs: Tracks subroutines declared via 'use subs' pragma (e.g., use subs 'hex')
    // Maps fully-qualified names (package::subname) to indicate they should be called
    // as user-defined subroutines instead of built-in operators
    public static final Map<String, Boolean> isSubs = new HashMap<>();

    // Regular expression for regex variables like $main::1
    static Pattern regexVariablePattern = Pattern.compile("^main::(\\d+)$");

    /**
     * Resets all global variables, arrays, hashes, code references, and IO references.
     * Also destroys and recreates the global class loader to allow GC of old classes.
     */
    public static void resetAllGlobals() {
        globalVariables.clear();
        globalArrays.clear();
        globalHashes.clear();
        globalCodeRefs.clear();
        globalIORefs.clear();
        globalGlobs.clear();
        isSubs.clear();
        clearPackageCache();

        RuntimeCode.clearCaches();

        // Destroy the old classloader and create a new one
        // This allows the old generated classes to be garbage collected
        globalClassLoader = new CustomClassLoader(GlobalVariable.class.getClassLoader());
    }

    /**
     * Retrieves a global variable by its key, initializing it if necessary.
     * If the key matches a regex capture variable pattern, it initializes a special variable.
     *
     * @param key The key of the global variable.
     * @return The RuntimeScalar representing the global variable.
     */
    public static RuntimeScalar getGlobalVariable(String key) {
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            // Need to initialize global variable
            Matcher matcher = regexVariablePattern.matcher(key);
            if (matcher.matches() && !key.equals("main::0")) {
                // Regex capture variable like $1
                // Extract the numeric capture group as a string
                String capturedNumber = matcher.group(1);
                // Convert the capture group to an integer
                int position = Integer.parseInt(capturedNumber);
                // Initialize the regex capture variable
                var = new ScalarSpecialVariable(ScalarSpecialVariable.Id.CAPTURE, position);
            } else {
                // Normal "non-magic" global variable
                var = new RuntimeScalar();
            }
            globalVariables.put(key, var);
        }
        return var;
    }

    public static RuntimeScalar aliasGlobalVariable(String key, String to) {
        RuntimeScalar var = globalVariables.get(to);
        globalVariables.put(key, var);
        return var;
    }

    /**
     * Sets the value of a global variable.
     *
     * @param key   The key of the global variable.
     * @param value The value to set.
     */
    public static void setGlobalVariable(String key, String value) {
        getGlobalVariable(key).set(value);
    }

    /**
     * Checks if a global variable exists.
     *
     * @param key The key of the global variable.
     * @return True if the global variable exists, false otherwise.
     */
    public static boolean existsGlobalVariable(String key) {
        return globalVariables.containsKey(key)
                || key.endsWith("::a")  // $a, $b always exist
                || key.endsWith("::b");
    }

    /**
     * Removes a global variable by its key.
     *
     * @param key The key of the global variable.
     * @return The removed RuntimeScalar, or null if it did not exist.
     */
    public static RuntimeScalar removeGlobalVariable(String key) {
        return globalVariables.remove(key);
    }

    /**
     * Retrieves a global array by its key, initializing it if necessary.
     *
     * @param key The key of the global array.
     * @return The RuntimeArray representing the global array.
     */
    public static RuntimeArray getGlobalArray(String key) {
        RuntimeArray var = globalArrays.get(key);
        if (var == null) {
            var = new RuntimeArray();
            globalArrays.put(key, var);
        }
        return var;
    }

    /**
     * Checks if a global array exists.
     *
     * @param key The key of the global array.
     * @return True if the global array exists, false otherwise.
     */
    public static boolean existsGlobalArray(String key) {
        return globalArrays.containsKey(key);
    }

    /**
     * Removes a global array by its key.
     *
     * @param key The key of the global array.
     * @return The removed RuntimeArray, or null if it did not exist.
     */
    public static RuntimeArray removeGlobalArray(String key) {
        return globalArrays.remove(key);
    }

    /**
     * Retrieves a global hash by its key, initializing it if necessary.
     *
     * @param key The key of the global hash.
     * @return The RuntimeHash representing the global hash.
     */
    public static RuntimeHash getGlobalHash(String key) {
        RuntimeHash var = globalHashes.get(key);
        if (var == null) {
            var = new RuntimeHash();
            globalHashes.put(key, var);
        }
        return var;
    }

    /**
     * Checks if a global hash exists.
     *
     * @param key The key of the global hash.
     * @return True if the global hash exists, false otherwise.
     */
    public static boolean existsGlobalHash(String key) {
        return globalHashes.containsKey(key);
    }

    /**
     * Removes a global hash by its key.
     *
     * @param key The key of the global hash.
     * @return The removed RuntimeHash, or null if it did not exist.
     */
    public static RuntimeHash removeGlobalHash(String key) {
        return globalHashes.remove(key);
    }

    /**
     * Retrieves a global code reference by its key, initializing it if necessary.
     *
     * @param key The key of the global code reference.
     * @return The RuntimeScalar representing the global code reference.
     */
    public static RuntimeScalar getGlobalCodeRef(String key) {
        RuntimeScalar var = globalCodeRefs.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            var.type = RuntimeScalarType.CODE;  // value is null
            var.value = new RuntimeCode(null, null);
            globalCodeRefs.put(key, var);
        }
        return var;
    }

    /**
     * Checks if a global code reference exists.
     *
     * @param key The key of the global code reference.
     * @return True if the global code reference exists, false otherwise.
     */
    public static boolean existsGlobalCodeRef(String key) {
        return globalCodeRefs.containsKey(key);
    }

    public static RuntimeScalar existsGlobalCodeRefAsScalar(String key) {
        return globalCodeRefs.containsKey(key) ? scalarTrue : scalarFalse;
    }

    /**
     * Clears the package existence cache.
     * Should be called when new packages are loaded or code refs are modified.
     */
    public static void clearPackageCache() {
        packageExistsCache.clear();
    }

    /**
     * Checks if a Perl package is loaded by scanning for any methods in its namespace
     *
     * @param className The name of the package/class to check
     * @return true if any methods exist in the class namespace
     */
    public static boolean isPackageLoaded(String className) {
        // Check cache first
        Boolean cached = packageExistsCache.get(className);
        if (cached != null) {
            return cached;
        }

        // Ensure we have the :: suffix for the prefix check
        final String prefix = className.endsWith("::") ? className : className + "::";

        // Check if any code references exist with this class prefix
        boolean exists = globalCodeRefs.keySet().stream()
                .anyMatch(key -> key.startsWith(prefix));

        // Cache the result
        packageExistsCache.put(className, exists);
        return exists;
    }

    /**
     * Retrieves a global IO reference by its key, initializing it if necessary.
     *
     * @param key The key of the global IO reference.
     * @return The RuntimeScalar representing the global IO reference.
     */
    public static RuntimeGlob getGlobalIO(String key) {
        RuntimeGlob glob = globalIORefs.get(key);
        if (glob == null) {
            glob = new RuntimeGlob(key);
            globalIORefs.put(key, glob);
        }
        return glob;
    }

    /**
     * Checks if a global IO reference exists.
     *
     * @param key The key of the global IO reference.
     * @return True if the global IO reference exists, false otherwise.
     */
    public static boolean existsGlobalIO(String key) {
        return globalIORefs.containsKey(key);
    }
}

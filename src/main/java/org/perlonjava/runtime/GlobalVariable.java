package org.perlonjava.runtime;

import org.perlonjava.codegen.CustomClassLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

/**
 * The GlobalVariable class manages global variables, arrays, hashes, and references
 * within the runtime environment. It provides methods to retrieve, set, and check
 * the existence of these global entities, initializing them as necessary.
 */
public class GlobalVariable {
    // Global variables and subroutines
    public static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    public static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    public static final Map<String, RuntimeHash> globalHashes = new HashMap<>();
    // Cache for package existence checks
    public static final Map<String, Boolean> packageExistsCache = new HashMap<>();
    // isSubs: Tracks subroutines declared via 'use subs' pragma (e.g., use subs 'hex')
    // Maps fully-qualified names (package::subname) to indicate they should be called
    // as user-defined subroutines instead of built-in operators
    public static final Map<String, Boolean> isSubs = new HashMap<>();
    static final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();
    static final Map<String, RuntimeGlob> globalIORefs = new HashMap<>();
    static final Map<String, RuntimeFormat> globalFormatRefs = new HashMap<>();

    // Flags used by operator override
    // globalGlobs: Tracks typeglob assignments (e.g., *CORE::GLOBAL::hex = sub {...})
    // Used to detect when built-in operators have been globally overridden
    static final Map<String, Boolean> globalGlobs = new HashMap<>();
    // Global class loader for all generated classes - not final so we can replace it
    public static CustomClassLoader globalClassLoader =
            new CustomClassLoader(GlobalVariable.class.getClassLoader());
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
        globalFormatRefs.clear();
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

    public static void aliasGlobalVariable(String key, RuntimeScalar var) {
        globalVariables.put(key, var);
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

    public static RuntimeScalar existsGlobalCodeRefAsScalar(RuntimeScalar key) {
        return existsGlobalCodeRefAsScalar(key.toString());
    }

    public static RuntimeScalar definedGlobalCodeRefAsScalar(String key) {
        return globalCodeRefs.containsKey(key) ? globalCodeRefs.get(key).defined() : scalarFalse;
    }

    public static RuntimeScalar definedGlobalCodeRefAsScalar(RuntimeScalar key) {
        return definedGlobalCodeRefAsScalar(key.toString());
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

    /**
     * Retrieves a global format reference by its key, initializing it if necessary.
     *
     * @param key The key of the global format reference.
     * @return The RuntimeFormat representing the global format reference.
     */
    public static RuntimeFormat getGlobalFormatRef(String key) {
        RuntimeFormat format = globalFormatRefs.get(key);
        if (format == null) {
            format = new RuntimeFormat(key);
            globalFormatRefs.put(key, format);
        }
        return format;
    }

    /**
     * Sets a global format reference to share the same format object.
     * Used for typeglob format assignments like *COPIED = *ORIGINAL.
     *
     * @param key The key of the global format reference.
     * @param format The RuntimeFormat object to set.
     */
    public static void setGlobalFormatRef(String key, RuntimeFormat format) {
        globalFormatRefs.put(key, format);
    }

    /**
     * Checks if a global format reference exists.
     *
     * @param key The key of the global format reference.
     * @return True if the global format reference exists, false otherwise.
     */
    public static boolean existsGlobalFormat(String key) {
        return globalFormatRefs.containsKey(key);
    }

    public static RuntimeScalar existsGlobalFormatAsScalar(String key) {
        return globalFormatRefs.containsKey(key) ? scalarTrue : scalarFalse;
    }

    public static RuntimeScalar existsGlobalFormatAsScalar(RuntimeScalar key) {
        return existsGlobalFormatAsScalar(key.toString());
    }

    public static RuntimeScalar definedGlobalFormatAsScalar(String key) {
        return globalFormatRefs.containsKey(key) ? 
            (globalFormatRefs.get(key).isFormatDefined() ? scalarTrue : scalarFalse) : scalarFalse;
    }

    public static RuntimeScalar definedGlobalFormatAsScalar(RuntimeScalar key) {
        return definedGlobalFormatAsScalar(key.toString());
    }

    /**
     * Resets all global variables whose names start with any of the specified characters
     *
     * @param resetChars     Set of characters to match variable names against
     * @param currentPackage The current package name with "::" suffix
     */
    public static void resetGlobalVariables(Set<Character> resetChars, String currentPackage) {
        // Reset scalar variables
        for (Map.Entry<String, RuntimeScalar> entry : globalVariables.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Reset to undef instead of removing to maintain reference integrity
                entry.getValue().set(RuntimeScalar.undef());
            }
        }

        // Reset array variables
        for (Map.Entry<String, RuntimeArray> entry : globalArrays.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Clear the array
                entry.getValue().elements.clear();
            }
        }

        // Reset hash variables
        for (Map.Entry<String, RuntimeHash> entry : globalHashes.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Clear the hash
                entry.getValue().elements.clear();
            }
        }

        // Note: We don't reset code references or IO references as per Perl behavior
    }

    /**
     * Determines if a variable should be reset based on its name and the reset characters
     *
     * @param fullKey       The full variable key (e.g. "main::myvar")
     * @param packagePrefix The current package prefix (e.g. "main::")
     * @param resetChars    The set of characters to match against
     * @return true if the variable should be reset
     */
    private static boolean shouldResetVariable(String fullKey, String packagePrefix, Set<Character> resetChars) {
        if (!fullKey.startsWith(packagePrefix)) {
            return false;
        }

        // Extract the variable name without the package prefix
        String varName = fullKey.substring(packagePrefix.length());

        // Skip special variables like $_, @ARGV, %ENV, etc.
        if (varName.length() == 1 && "_!@$".indexOf(varName.charAt(0)) >= 0) {
            return false;
        }

        // Don't reset important arrays and hashes
        if (varName.equals("ARGV") || varName.equals("INC") || varName.equals("ENV")) {
            return false;
        }

        // Check if the first character of the variable name matches any reset character
        if (varName.length() > 0) {
            return resetChars.contains(varName.charAt(0));
        }

        return false;
    }

    /**
     * Gets all ISA arrays for reverse ISA cache building.
     * This method should return all global arrays that end with "::ISA".
     */
    public static Map<String, RuntimeArray> getAllIsaArrays() {
        Map<String, RuntimeArray> result = new HashMap<>();
        // Implementation depends on how GlobalVariable stores its data
        // This is a placeholder - you'll need to implement based on your GlobalVariable structure
        return result;
    }
}

package org.perlonjava.runtime;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The RuntimeScalar class simulates Perl namespaces.
 */
public class GlobalContext {

    // Global variables and subroutines
    private static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    private static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    private static final Map<String, RuntimeHash> globalHashes = new HashMap<>();
    private static final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();
    private static final Map<String, RuntimeScalar> globalIORefs = new HashMap<>();

    // Cache to store previously normalized variables for faster lookup
    private static final Map<String, String> nameCache = new HashMap<>();
    private static final Map<String, Integer> blessIdCache = new HashMap<>();
    private static final ArrayList<String> blessStrCache = new ArrayList<>();
    private static final Set<String> SPECIAL_VARIABLES = Set.of(
            "ARGV", "ENV", "INC", "SIG", "STDOUT", "STDERR", "STDIN"
    );
    // Cache to store blessed class lookups
    private static int currentBlessId = 0;

    static {
        blessStrCache.add("");  // this starts with index 1
    }

    public static int getBlessId(String str) {
        Integer id = blessIdCache.get(str);
        if (id == null) {
            currentBlessId++;
            blessIdCache.put(str, currentBlessId);
            blessStrCache.add(currentBlessId, str);
            id = currentBlessId;
        }
        return id;
    }

    public static String getBlessStr(int id) {
        return blessStrCache.get(id);
    }

    public static void initializeGlobals() {
        getGlobalVariable("main::@");    // initialize $@ to "undef"
        getGlobalVariable("main::_");    // initialize $_ to "undef"
        getGlobalVariable("main::\"").set(" ");    // initialize $" to " "
        getGlobalVariable("main::a");    // initialize $a to "undef"
        getGlobalVariable("main::b");    // initialize $b to "undef"
        getGlobalVariable("main::!");    // initialize $! to "undef"
        getGlobalVariable("main::,").set("");    // initialize $, to ""
        getGlobalVariable("main::\\").set("");    // initialize $\ to ""
        getGlobalVariable("main::/").set("\n"); // initialize $/ to newline
        getGlobalArray("main::INC");
        getGlobalHash("main::INC");

        // Initialize STDOUT, STDERR, STDIN
        getGlobalIO("main::STDOUT").set(RuntimeIO.open(FileDescriptor.out, true));
        getGlobalIO("main::STDERR").set(RuntimeIO.open(FileDescriptor.err, true));
        getGlobalIO("main::STDIN").set(RuntimeIO.open(FileDescriptor.in, false));

        // Initialize UNIVERSAL class
        try {
            // UNIVERSAL methods are defined in RuntimeScalar class
            Class<?> clazz = RuntimeScalar.class;
            RuntimeScalar instance = new RuntimeScalar();

            Method mm = clazz.getMethod("can", RuntimeArray.class, int.class);
            getGlobalCodeRef("UNIVERSAL::can").set(new RuntimeScalar(new RuntimeCode(mm, instance)));

            mm = clazz.getMethod("isa", RuntimeArray.class, int.class);
            getGlobalCodeRef("UNIVERSAL::isa").set(new RuntimeScalar(new RuntimeCode(mm, instance)));
            getGlobalCodeRef("UNIVERSAL::DOES").set(new RuntimeScalar(new RuntimeCode(mm, instance)));
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing UNIVERSAL method: " + e.getMessage());
        }

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }

    /**
     * Normalizes a Perl variable name by ensuring it includes the default package if not already specified.
     *
     * @param variable       The variable name to normalize, without sigil. This should be a Perl variable name, potentially without a package.
     * @param defaultPackage The default package to prepend to the variable name if it does not already include a package.
     * @return The normalized variable name, without sigil, including the default package if it was not already specified.
     */
    public static String normalizeVariableName(String variable, String defaultPackage) {

        // Create a cache key based on both the variable and the default package
        String cacheKey = defaultPackage + "::" + variable;

        // Check the cache first
        if (nameCache.containsKey(cacheKey)) {
            return nameCache.get(cacheKey);
        }

        if (!Character.isLetter(variable.charAt(0)) || SPECIAL_VARIABLES.contains(variable)) {
            defaultPackage = "main";    // special variables are always in main
            if (variable.length() == 2 && variable.charAt(0) == '^' && variable.charAt(1) >= 'A' && variable.charAt(1) <= 'Z') {
                // For $^A to $^Z, convert the second character to the corresponding ASCII control character.
                // For example, $^A should become ${chr(1)}
                char controlChar = (char) (variable.charAt(1) - 64);
                variable = String.valueOf(controlChar);
            }
        }

        StringBuilder normalized = new StringBuilder(variable.length() + defaultPackage.length() + 2);
        if (variable.startsWith("::")) {
            // $::x
            normalized.append(defaultPackage).append(variable);
        } else if (variable.contains("::")) {
            // If already in a package, return as-is
            normalized.append(variable);
        } else {
            // Prepend default package
            normalized.append(defaultPackage).append("::").append(variable);
        }

        // Convert to string and store in cache
        String normalizedStr = normalized.toString();
        nameCache.put(cacheKey, normalizedStr);

        return normalizedStr;
    }

    public static RuntimeScalar getGlobalVariable(String key) {
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            globalVariables.put(key, var);
        }
        return var;
    }

    public static RuntimeScalar setGlobalVariable(String key, String value) {
        return getGlobalVariable(key).set(value);
    }

    public static boolean existsGlobalVariable(String key) {
        return globalVariables.containsKey(key);
    }

    public static RuntimeArray getGlobalArray(String key) {
        RuntimeArray var = globalArrays.get(key);
        if (var == null) {
            var = new RuntimeArray();
            globalArrays.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalArray(String key) {
        return globalArrays.containsKey(key);
    }

    public static RuntimeHash getGlobalHash(String key) {
        RuntimeHash var = globalHashes.get(key);
        if (var == null) {
            var = new RuntimeHash();
            globalHashes.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalHash(String key) {
        return globalHashes.containsKey(key);
    }

    public static RuntimeScalar getGlobalCodeRef(String key) {
        RuntimeScalar var = globalCodeRefs.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            var.type = RuntimeScalarType.GLOB;  // value is null
            globalCodeRefs.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalCodeRef(String key) {
        return globalCodeRefs.containsKey(key);
    }

    public static RuntimeScalar getGlobalIO(String key) {
        RuntimeScalar var = globalIORefs.get(key);
        if (var == null) {
            var = new RuntimeScalar().set(new RuntimeIO());
            globalIORefs.put(key, var);
        }
        return var;
    }

    public static boolean existsGlobalIO(String key) {
        return globalIORefs.containsKey(key);
    }
}


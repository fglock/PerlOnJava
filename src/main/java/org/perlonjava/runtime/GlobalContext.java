package org.perlonjava.runtime;

import org.perlonjava.ArgumentParser;
import org.perlonjava.perlmodule.Exporter;
import org.perlonjava.perlmodule.Symbol;
import org.perlonjava.perlmodule.Universal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.RuntimeIO.initStdHandles;

/**
 * The GlobalContext class simulates Perl namespaces.
 * It manages global variables, arrays, hashes, and other global entities.
 */
public class GlobalContext {

    // Global variables and subroutines
    private static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    private static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    private static final Map<String, RuntimeHash> globalHashes = new HashMap<>();
    private static final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();
    private static final Map<String, RuntimeScalar> globalIORefs = new HashMap<>();

    // Regular expression for regex variables like $main::1
    static Pattern regexVariablePattern = Pattern.compile("^main::(\\d+)$");

    /**
     * Initializes global variables, arrays, hashes, and other entities.
     *
     * @param compilerOptions The compiler options used for initialization.
     */
    public static void initializeGlobals(ArgumentParser.CompilerOptions compilerOptions) {

        // Initialize scalar variables
        for (char c = 'A'; c <= 'Z'; c++) {
            // Initialize $^A.. $^Z
            String varName = "main::" + Character.toString(c - 'A' + 1);
            getGlobalVariable(varName);
        }
        getGlobalVariable("main::" + Character.toString('O' - 'A' + 1)).set("jvm");    // initialize $^O to "jvm"
        getGlobalVariable("main::@");    // initialize $@ to "undef"
        getGlobalVariable("main::_");    // initialize $_ to "undef"
        getGlobalVariable("main::\"").set(" ");    // initialize $" to " "
        getGlobalVariable("main::a");    // initialize $a to "undef"
        getGlobalVariable("main::b");    // initialize $b to "undef"
        getGlobalVariable("main::!");    // initialize $! to "undef"
        getGlobalVariable("main::,").set("");    // initialize $, to ""
        getGlobalVariable("main::\\").set("");    // initialize $\ to ""
        getGlobalVariable("main::/").set("\n"); // initialize $/ to newline
        getGlobalVariable("main::$").set(ProcessHandle.current().pid()); // initialize `$$` to process id
        getGlobalVariable("main::0").set(compilerOptions.fileName);

        globalVariables.put("main::`", new ScalarSpecialVariable(ScalarSpecialVariable.Id.PREMATCH));
        globalVariables.put("main::&", new ScalarSpecialVariable(ScalarSpecialVariable.Id.MATCH));
        globalVariables.put("main::'", new ScalarSpecialVariable(ScalarSpecialVariable.Id.POSTMATCH));
        globalVariables.put("main::" + Character.toString('L' - 'A' + 1) + "AST_FH", new ScalarSpecialVariable(ScalarSpecialVariable.Id.LAST_FH)); // $^LAST_FH
        globalVariables.put("main::.", new ScalarSpecialVariable(ScalarSpecialVariable.Id.INPUT_LINE_NUMBER)); // $.

        // Initialize arrays
        getGlobalArray("main::+").elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_END);  // regex @+
        getGlobalArray("main::-").elements = new ArraySpecialVariable(ArraySpecialVariable.Id.LAST_MATCH_START);  // regex @-

        // Initialize hashes
        getGlobalHash("main::+").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE);  // regex %+
        getGlobalHash("main::-").elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE_ALL);  // regex %-

        // Initialize %ENV
        Map<String, RuntimeScalar> env = getGlobalHash("main::ENV").elements;
        System.getenv().forEach((k, v) -> env.put(k, new RuntimeScalar(v)));

        // Initialize @INC
        // https://stackoverflow.com/questions/2526804/how-is-perls-inc-constructed
        List<RuntimeScalar> inc = getGlobalArray("main::INC").elements;

        inc.addAll(compilerOptions.inc.elements);   // add from `-I`

        String[] directories = env.getOrDefault("PERL5LIB", new RuntimeScalar("")).toString().split(":");
        for (String directory : directories) {
            if (!directory.isEmpty()) {
                inc.add(new RuntimeScalar(directory)); // add from env PERL5LIB
            }
        }

        // Initialize %INC
        getGlobalHash("main::INC");

        // Initialize STDOUT, STDERR, STDIN
        initStdHandles();
        // ARGV file handle - If no files are specified, use standard input
        if (getGlobalArray("main::ARGV").size() == 0) {
            getGlobalIO("main::ARGV").set(getGlobalIO("main::STDIN"));
        }

        // Initialize built-in Perl classes
        Universal.initialize();
        Symbol.initialize();
        Exporter.initialize();

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }

    /**
     * Retrieves a global variable by its key, initializing it if necessary.
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
        return globalVariables.containsKey(key);
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
     * Retrieves a global code reference by its key, initializing it if necessary.
     *
     * @param key The key of the global code reference.
     * @return The RuntimeScalar representing the global code reference.
     */
    public static RuntimeScalar getGlobalCodeRef(String key) {
        RuntimeScalar var = globalCodeRefs.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            var.type = RuntimeScalarType.GLOB;  // value is null
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

    /**
     * Retrieves a global IO reference by its key, initializing it if necessary.
     *
     * @param key The key of the global IO reference.
     * @return The RuntimeScalar representing the global IO reference.
     */
    public static RuntimeScalar getGlobalIO(String key) {
        RuntimeScalar var = globalIORefs.get(key);
        if (var == null) {
            var = new RuntimeScalar().set(new RuntimeIO());
            globalIORefs.put(key, var);
        }
        return var;
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


package org.perlonjava.runtime;

import org.perlonjava.ArgumentParser;
import org.perlonjava.perlmodule.Exporter;
import org.perlonjava.perlmodule.Symbol;
import org.perlonjava.perlmodule.Universal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeIO.initStdHandles;

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
        getGlobalVariable("main::$").set(ProcessHandle.current().pid()); // initialize $$ to process id
        getGlobalVariable("main::0").set(compilerOptions.fileName);
        for (int i = 1; i <= 10; i++) {
            globalVariables.put("main::" + i, new RuntimeScalarRegexVariable(i));
        }

        // Initialize arrays
        getGlobalArray("main::+");  // regex @+
        getGlobalArray("main::-");  // regex @-

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

        // Initialize built-in Perl classes
        Universal.initialize();
        Symbol.initialize();
        Exporter.initialize();

        // Reset method cache after initializing UNIVERSAL
        InheritanceResolver.invalidateCache();
    }

    public static RuntimeScalar getGlobalVariable(String key) {
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            globalVariables.put(key, var);
        }
        return var;
    }

    public static void setGlobalVariable(String key, String value) {
        getGlobalVariable(key).set(value);
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


package org.perlonjava.runtime;

import org.perlonjava.ArgumentParser;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // Initialize %ENV
        Map<String, RuntimeScalar> env = getGlobalHash("main::ENV").elements;
        System.getenv().forEach((k, v) -> {
            env.put(k, new RuntimeScalar(v));
        });

        // Initialize @ARGV
        getGlobalArray("main::ARGV").elements = compilerOptions.argumentList.elements;

        // Initialize @INC
        List<RuntimeBaseEntity> inc = getGlobalArray("main::INC").elements;

        inc.addAll(compilerOptions.inc.elements);   // add from `-I`

        String[] directories = env.getOrDefault("PERL5LIB", new RuntimeScalar("")).toString().split(":");
        for (int i = 0; i < directories.length; i++) {
            if (!directories[i].isEmpty()) {
                inc.add(new RuntimeScalar(directories[i])); // add from env PERL5LIB
            }
        }

        // Initialize %INC
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


package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.util.Map;
import java.util.Properties;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * Java::System - Perl module for accessing Java system properties and environment
 *
 * This module provides a Perl interface to Java's System class, allowing
 * Perl code to access system properties, environment variables, and other
 * JVM information.
 */
public class JavaSystem extends PerlModuleBase {

    public JavaSystem() {
        super("Java::System", true);
    }

    public static void initialize() {
        JavaSystem javaSystem = new JavaSystem();
        javaSystem.initializeExporter();

        // Export commonly used functions by default
        javaSystem.defineExport("EXPORT",
                "getProperty",
                "getProperties",
                "getenv",
                "getEnvMap",
                "currentTimeMillis",
                "nanoTime",
                "gc",
                "exit"
        );

        // Additional functions available on request
        javaSystem.defineExport("EXPORT_OK",
                "getSecurityManager",
                "identityHashCode",
                "lineSeparator",
                "arraycopy"
        );

        try {
            // Register all methods
            javaSystem.registerMethod("getProperty", null);
            javaSystem.registerMethod("getProperties", null);
            javaSystem.registerMethod("getenv", null);
            javaSystem.registerMethod("getEnvMap", null);
            javaSystem.registerMethod("currentTimeMillis", null);
            javaSystem.registerMethod("nanoTime", null);
            javaSystem.registerMethod("gc", null);
            javaSystem.registerMethod("exit", null);
            javaSystem.registerMethod("identityHashCode", null);
            javaSystem.registerMethod("lineSeparator", null);
            javaSystem.registerMethod("arraycopy", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing JavaSystem method: " + e.getMessage());
        }
    }

    /**
     * Get a system property by name
     * Usage: my $value = getProperty('java.version');
     *        my $value = getProperty('os.name', 'default');
     */
    public static RuntimeList getProperty(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalArgumentException("getProperty requires at least 1 argument");
        }

        String key = args.get(0).toString();
        String defaultValue = args.size() > 1 ? args.get(1).toString() : null;

        String value = System.getProperty(key, defaultValue);

        if (value == null) {
            return scalarUndef.getList();
        }

        return new RuntimeScalar(value).getList();
    }

    /**
     * Get all system properties as a hash
     * Usage: my %props = getProperties();
     */
    public static RuntimeList getProperties(RuntimeArray args, int ctx) {
        Properties props = System.getProperties();
        RuntimeHash hash = new RuntimeHash();

        for (String key : props.stringPropertyNames()) {
            hash.put(key, new RuntimeScalar(props.getProperty(key)));
        }

        if (ctx == RuntimeContextType.LIST) {
            return hash.getList();
        } else {
            return hash.createReference().getList();
        }
    }

    /**
     * Get an environment variable by name
     * Usage: my $value = getenv('PATH');
     */
    public static RuntimeList getenv(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalArgumentException("getenv requires 1 argument");
        }

        String key = args.get(0).toString();
        String value = System.getenv(key);

        if (value == null) {
            return scalarUndef.getList();
        }

        return new RuntimeScalar(value).getList();
    }

    /**
     * Get all environment variables as a hash
     * Usage: my %env = getEnvMap();
     */
    public static RuntimeList getEnvMap(RuntimeArray args, int ctx) {
        Map<String, String> env = System.getenv();
        RuntimeHash hash = new RuntimeHash();

        for (Map.Entry<String, String> entry : env.entrySet()) {
            hash.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
        }

        if (ctx == RuntimeContextType.LIST) {
            return hash.getList();
        } else {
            return hash.createReference().getList();
        }
    }

    /**
     * Get current time in milliseconds
     * Usage: my $time = currentTimeMillis();
     */
    public static RuntimeList currentTimeMillis(RuntimeArray args, int ctx) {
        return new RuntimeScalar(System.currentTimeMillis()).getList();
    }

    /**
     * Get high-resolution time in nanoseconds
     * Usage: my $time = nanoTime();
     */
    public static RuntimeList nanoTime(RuntimeArray args, int ctx) {
        return new RuntimeScalar(System.nanoTime()).getList();
    }

    /**
     * Request garbage collection
     * Usage: gc();
     */
    public static RuntimeList gc(RuntimeArray args, int ctx) {
        System.gc();
        return scalarUndef.getList();
    }

    /**
     * Exit the JVM
     * Usage: exit(0);
     */
    public static RuntimeList exit(RuntimeArray args, int ctx) {
        int status = args.size() > 0 ? (int) args.get(0).getLong() : 0;
        System.exit(status);
        return scalarUndef.getList(); // Never reached
    }

    /**
     * Get identity hash code of an object
     * Usage: my $hash = identityHashCode($ref);
     */
    public static RuntimeList identityHashCode(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("identityHashCode requires 1 argument");
        }

        Object obj = args.get(0);
        int hashCode = System.identityHashCode(obj);

        return new RuntimeScalar(hashCode).getList();
    }

    /**
     * Get the system line separator
     * Usage: my $sep = lineSeparator();
     */
    public static RuntimeList lineSeparator(RuntimeArray args, int ctx) {
        return new RuntimeScalar(System.lineSeparator()).getList();
    }

    /**
     * Copy array elements (simplified version)
     * Usage: arraycopy(\@src, $srcPos, \@dest, $destPos, $length);
     */
    public static RuntimeList arraycopy(RuntimeArray args, int ctx) {
        if (args.size() < 5) {
            throw new IllegalArgumentException("arraycopy requires 5 arguments");
        }

        RuntimeArray src = args.get(0).arrayDeref();
        int srcPos = (int) args.get(1).getLong();
        RuntimeArray dest = args.get(2).arrayDeref();
        int destPos = (int) args.get(3).getLong();
        int length = (int) args.get(4).getLong();

        // Perform the copy
        for (int i = 0; i < length; i++) {
            dest.get(destPos + i).set(src.get(srcPos + i));
        }

        return scalarUndef.getList();
    }
}

package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Utility class for Cwd operations in Perl.
 * Extends PerlModuleBase to leverage module initialization and method registration.
 */
public class Cwd extends PerlModuleBase {

    /**
     * Constructor for Cwd.
     * Initializes the module with the name "Cwd".
     */
    public Cwd() {
        super("Cwd");
    }

    /**
     * Static initializer to set up the Cwd module.
     * This method initializes the exporter and defines the symbols that can be exported.
     */
    public static void initialize() {
        Cwd cwdUtil = new Cwd();
        cwdUtil.initializeExporter();
        cwdUtil.defineExport("EXPORT", "cwd", "getcwd", "fastcwd", "fastgetcwd");
        cwdUtil.defineExport("EXPORT_OK", "getcwd", "cwd", "fastcwd", "fastgetcwd", "abs_path", "realpath", "fast_abs_path");
        try {
            cwdUtil.registerMethod("getcwd", "");
            cwdUtil.registerMethod("cwd", "");
            cwdUtil.registerMethod("fastcwd", "");
            cwdUtil.registerMethod("fastgetcwd", "");
            cwdUtil.registerMethod("abs_path", "$");
            cwdUtil.registerMethod("realpath", "$");
            cwdUtil.registerMethod("fast_abs_path", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Cwd method: " + e.getMessage());
        }
    }

    /**
     * Returns the current working directory.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the current working directory.
     */
    public static RuntimeList getcwd(RuntimeArray args, int ctx) {
        try {
            // Normalize the path to handle Windows 8.3 short paths
            // This ensures getcwd() matches abs_path('.')
            String cwd = Paths.get(System.getProperty("user.dir")).toRealPath().toString();
            return new RuntimeScalar(cwd).getList();
        } catch (IOException e) {
            // Fallback to raw path if normalization fails
            String cwd = System.getProperty("user.dir");
            return new RuntimeScalar(cwd).getList();
        }
    }

    /**
     * Returns the current working directory, synonym for getcwd.
     */
    public static RuntimeList cwd(RuntimeArray args, int ctx) {
        return getcwd(args, ctx);
    }

    /**
     * A potentially faster version of getcwd.
     */
    public static RuntimeList fastcwd(RuntimeArray args, int ctx) {
        return getcwd(args, ctx); // Placeholder for potentially faster implementation
    }

    /**
     * Synonym for fastcwd.
     */
    public static RuntimeList fastgetcwd(RuntimeArray args, int ctx) {
        return fastcwd(args, ctx);
    }

    /**
     * Returns the absolute path of the given file or current directory if no argument is provided.
     */
    public static RuntimeList abs_path(RuntimeArray args, int ctx) {
        try {
            // Get the base directory from the user.dir system property
            String baseDir = System.getProperty("user.dir");
            // Determine the path to resolve
            String path = args.size() > 0 ? args.get(0).toString() : baseDir;
            
            // Resolve the path: if already absolute, use it directly; otherwise resolve relative to baseDir
            java.nio.file.Path pathObj = Paths.get(path);
            if (!pathObj.isAbsolute()) {
                pathObj = Paths.get(baseDir).resolve(path);
            }
            String absPath = pathObj.toRealPath().toString();
            return new RuntimeScalar(absPath).getList();
        } catch (IOException e) {
            System.err.println("Error resolving absolute path: " + e.getMessage());
            return new RuntimeScalar().getList(); // Return undef on error
        }
    }

    /**
     * Synonym for abs_path.
     */
    public static RuntimeList realpath(RuntimeArray args, int ctx) {
        return abs_path(args, ctx);
    }

    /**
     * A potentially faster version of abs_path.
     */
    public static RuntimeList fast_abs_path(RuntimeArray args, int ctx) {
        return abs_path(args, ctx); // Placeholder for potentially faster implementation
    }
}

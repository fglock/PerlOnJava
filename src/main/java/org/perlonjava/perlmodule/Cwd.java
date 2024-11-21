package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.io.File;
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
            String cwd = new File(".").getCanonicalPath();
            return new RuntimeScalar(cwd).getList();
        } catch (IOException e) {
            return new RuntimeScalar().getList(); // Return undef on error
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
            String path = args.size() > 0 ? args.get(0).toString() : ".";
            String absPath = Paths.get(path).toRealPath().toString();
            return new RuntimeScalar(absPath).getList();
        } catch (IOException e) {
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

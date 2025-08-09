package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.RuntimeContextType.SCALAR;

/**
 * The Symbol class provides functionalities for symbol manipulation in a Perl-like environment.
 * It extends PerlModuleBase to leverage module initialization and method registration.
 */
public class Symbol extends PerlModuleBase {

    /**
     * Constructor for Symbol.
     * Initializes the module with the name "Symbol".
     */
    public Symbol() {
        super("Symbol");
    }

    /**
     * Static initializer to set up the Symbol module.
     * This method initializes the exporter and defines the symbols that can be exported.
     */
    public static void initialize() {
        Symbol symbol = new Symbol();
        symbol.initializeExporter();
        symbol.defineExport("EXPORT", "gensym", "ungensym", "qualify", "qualify_to_ref");
        symbol.defineExport("EXPORT_OK", "delete_package", "geniosym");
        try {
            // Register methods with their respective signatures
            symbol.registerMethod("gensym", "");
            symbol.registerMethod("ungensym", "$");
            symbol.registerMethod("qualify_to_ref", "$;$");
            symbol.registerMethod("qualify", "$;$");
            symbol.registerMethod("delete_package", "$");
            symbol.registerMethod("geniosym", "");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Symbol method: " + e.getMessage());
        }
    }

    /**
     * Placeholder for the gensym functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     * @throws PerlCompilerException if the method is not implemented.
     */
    public static RuntimeList gensym(RuntimeArray args, int ctx) {
        // Placeholder for gensym functionality
        // return new RuntimeScalar(new RuntimeGlob("GEN" + System.nanoTime())).getList();
        throw new PerlCompilerException("not implemented");
    }

    /**
     * Placeholder for the ungensym functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList ungensym(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for ungensym()");
        }
        // Placeholder for ungensym functionality
        return new RuntimeScalar().getList();
    }

    /**
     * Placeholder for the delete_package functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList delete_package(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for delete_package()");
        }
        // Placeholder for delete_package functionality
        return new RuntimeScalar().getList();
    }

    /**
     * Placeholder for the geniosym functionality.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     * @throws PerlCompilerException if the method is not implemented.
     */
    public static RuntimeList geniosym(RuntimeArray args, int ctx) {
        // Placeholder for geniosym functionality
        // return new RuntimeScalar(new RuntimeGlob("IO" + System.nanoTime())).getList();
        throw new PerlCompilerException("not implemented");
    }

    /**
     * Qualifies a symbol name with a package name.
     * If no package name is provided, defaults to the caller's package.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the qualified symbol name.
     */
    public static RuntimeList qualify(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for qualify()");
        }
        RuntimeScalar object = args.get(0);
        RuntimeScalar packageName = null;
        if (args.size() > 1) {
            packageName = args.get(1);
        } else {
            RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), SCALAR);
            packageName = callerList.scalar();
        }
        RuntimeScalar result;
        // System.out.println("qualify " + object + " :: " + packageName + " type:" + object.type);
        if (!object.isString()) {
            result = object;
        } else {
            // System.out.println("qualify normalizeVariableName");
            result = new RuntimeScalar(NameNormalizer.normalizeVariableName(object.toString(), packageName.toString()));
        }
        RuntimeList list = new RuntimeList();
        list.elements.add(result);
        return list;
    }

    /**
     * Qualifies a symbol name with a package name and returns a glob reference.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the qualified glob reference.
     */
    public static RuntimeList qualify_to_ref(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for qualify_to_ref()");
        }
        RuntimeScalar object = qualify(args, ctx).scalar();
        RuntimeScalar result;
        if (!object.isString()) {
            result = object;
        } else {
            // System.out.println("qualify_to_ref");
            result = new RuntimeScalar().set(new RuntimeGlob(object.toString()));
        }
        // System.out.println("qualify_to_ref returns " + result.type);
        RuntimeList list = new RuntimeList();
        list.elements.add(result);
        return list;
    }
}

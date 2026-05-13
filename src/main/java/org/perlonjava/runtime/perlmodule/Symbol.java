package org.perlonjava.runtime.perlmodule;

import org.perlonjava.backend.jvm.EmitterMethodCreator;
import org.perlonjava.runtime.runtimetypes.*;

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
     * Creates a new anonymous glob and returns a reference to it.
     * This is equivalent to Perl's Symbol::gensym().
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a reference to a new anonymous glob.
     */
    public static RuntimeList gensym(RuntimeArray args, int ctx) {
        // Create a unique anonymous glob
        String globName = "Symbol::GEN" + EmitterMethodCreator.classCounter++;
        RuntimeGlob glob = new RuntimeGlob(globName);
        
        // Return a reference to the glob (not the glob itself)
        RuntimeScalar globRef = new RuntimeScalar();
        globRef.type = RuntimeScalarType.GLOBREFERENCE;
        globRef.value = glob;
        
        return globRef.getList();
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
     * Creates a new anonymous IO handle.
     * Equivalent to Perl's Symbol::geniosym() — creates an anonymous glob,
     * initializes its IO slot, and returns a reference to it.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a reference to a new anonymous glob with initialized IO.
     */
    public static RuntimeList geniosym(RuntimeArray args, int ctx) {
        // Create a unique anonymous glob (same as gensym)
        String globName = "Symbol::GEN" + EmitterMethodCreator.classCounter++;
        RuntimeGlob glob = new RuntimeGlob(globName);

        // Initialize the IO slot (equivalent to Perl's: select(select $sym))
        // The IO slot is already initialized by RuntimeGlob constructor (this.IO = new RuntimeScalar())

        // Return a reference to the glob
        RuntimeScalar globRef = new RuntimeScalar();
        globRef.type = RuntimeScalarType.GLOBREFERENCE;
        globRef.value = glob;

        return globRef.getList();
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
            RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
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
        RuntimeScalar object;
        if (args.size() == 1) {
            RuntimeArray qa = new RuntimeArray();
            qa.push(args.get(0));
            // Prefer perl-compatible caller(); InterpreterState can diverge from caller inside
            // closures invoked from another package (qualify_to_ref must match embedded qualify).
            qa.push(RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR).scalar());
            object = qualify(qa, ctx).scalar();
        } else {
            object = qualify(args, ctx).scalar();
        }
        RuntimeScalar result;
        if (!object.isString()) {
            // Already a glob reference or similar — return as-is
            result = object;
        } else {
            // Use the canonical stash glob (vivifying if needed), not a detached RuntimeGlob.
            // new RuntimeGlob(name).createReference() pointed at an orphan glob — slots like
            // ARRAY never saw @Pkg::name (Symbol::qualify_to_ref, FindBin::libs path).
            result = GlobalVariable.getGlobalIO(object.toString()).createReference();
        }
        // System.out.println("qualify_to_ref returns " + result.type);
        RuntimeList list = new RuntimeList();
        list.elements.add(result);
        return list;
    }
}

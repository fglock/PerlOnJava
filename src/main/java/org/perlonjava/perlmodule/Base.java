package org.perlonjava.perlmodule;

import org.perlonjava.operators.ModuleOperators;
import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalArray;

/**
 * The Base class is responsible for establishing ISA relationships with base classes at compile time.
 * It mimics the behavior of Perl's base module, allowing classes to inherit from other classes.
 */
public class Base extends PerlModuleBase {

    /**
     * Constructor for Base.
     * Initializes the module with the name "base".
     */
    public Base() {
        super("base");
    }

    /**
     * Initializes the Base class by setting up the necessary global variables and methods.
     */
    public static void initialize() {
        // Initialize `base` class
        Base base = new Base();
        try {
            base.registerMethod("import", "importBase", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Base method: " + e.getMessage());
        }
    }

    /**
     * Imports base classes into the caller's namespace, effectively setting up inheritance.
     *
     * @param args The arguments specifying the base classes to inherit from.
     * @param ctx  The context in which the import is being performed.
     * @return A RuntimeList representing the result of the import operation.
     * @throws PerlCompilerException if there are issues with the import process.
     */
    public static RuntimeList importBase(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Not enough arguments for base::import");
        }

        // Extract the package name from the arguments
        RuntimeScalar packageScalar = RuntimeArray.shift(args);
        String packageName = packageScalar.scalar().toString();

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String inheritor = callerList.scalar().toString();

        // Process each base class specified in the arguments
        for (RuntimeScalar baseClass : args.elements) {
            String baseClassName = baseClass.toString();

            if (baseClassName.equals(inheritor)) {
                System.err.println("Warning: Class '" + inheritor + "' tried to inherit from itself");
                continue;
            }

            if (!GlobalVariable.isPackageLoaded(baseClassName)) {
                // Require the base class file
                String filename = baseClassName.replace("::", "/").replace("'", "/") + ".pm";
                try {
                    RuntimeScalar ret = ModuleOperators.require(new RuntimeScalar(filename));
                } catch (Exception e) {
                    if (e.getMessage().contains("not found")) {
                        System.err.println("Base class package \"" + baseClassName + "\" is empty.");
                        throw new PerlCompilerException("Base class package \"" + baseClassName + "\" is empty.");
                    } else {
                        throw e;
                    }
                }
            }

            // Add the base class to the @ISA array of the inheritor
            RuntimeArray isa = getGlobalArray(inheritor + "::ISA");
            RuntimeArray.push(isa, new RuntimeScalar(baseClassName));
        }

        return new RuntimeList();
    }
}

package org.perlonjava.perlmodule;

import org.perlonjava.operators.ModuleOperators;
import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalArray;
import static org.perlonjava.runtime.GlobalVariable.getGlobalHash;

/**
 * The Parent class is responsible for managing inheritance in Perl-like modules.
 * It mimics the behavior of Perl's parent module, allowing classes to inherit from other classes.
 */
public class Parent extends PerlModuleBase {

    /**
     * Constructor for Parent.
     * Initializes the module with the name "parent".
     */
    public Parent() {
        super("parent");
    }

    /**
     * Initializes the Parent class by setting up the necessary global variables and methods.
     * This includes setting the %INC hash and loading the parent methods into the Perl namespace.
     */
    public static void initialize() {
        // Initialize `parent` class
        Parent parent = new Parent();
        try {
            parent.registerMethod("import", "importParent", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Parent method: " + e.getMessage());
        }

        // Set %INC to indicate that parent.pm has been loaded
        getGlobalHash("main::INC").put("parent.pm", new RuntimeScalar("parent.pm"));
    }

    /**
     * Imports parent classes into the caller's namespace, effectively setting up inheritance.
     *
     * @param args The arguments specifying the parent classes to inherit from.
     * @param ctx  The context in which the import is being performed.
     * @return A RuntimeList representing the result of the import operation.
     * @throws PerlCompilerException if there are issues with the import process.
     */
    public static RuntimeList importParent(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Not enough arguments for parent::import");
        }

        // Extract the package name from the arguments
        RuntimeScalar packageScalar = args.shift();
        String packageName = packageScalar.scalar().toString();

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String inheritor = callerList.scalar().toString();

        // Check for the -norequire option
        boolean noRequire = false;
        if (args.size() > 0 && args.get(0).toString().equals("-norequire")) {
            noRequire = true;
            args.shift();
        }

        // Process each parent class specified in the arguments
        for (RuntimeScalar parentClass : args.elements) {
            String parentClassName = parentClass.toString();

            if (!noRequire) {
                // Require the parent class file unless -norequire is specified
                String filename = parentClassName.replace("::", "/").replace("'", "/") + ".pm";
                RuntimeScalar ret = ModuleOperators.require(new RuntimeScalar(filename));
            }

            // Add the parent class to the @ISA array of the inheritor
            RuntimeArray isa = getGlobalArray(inheritor + "::ISA");
            isa.push(new RuntimeScalar(parentClassName));
        }

        return new RuntimeList();
    }
}

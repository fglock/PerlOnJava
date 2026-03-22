package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ModuleOperators;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalArray;

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

        // Keep track of bases we're adding in this import call
        java.util.List<String> basesToAdd = new java.util.ArrayList<>();

        // Process each base class specified in the arguments
        for (RuntimeScalar baseClass : args.elements) {
            String baseClassName = baseClass.toString();

            if (baseClassName.equals(inheritor)) {
                System.err.println("Warning: Class '" + inheritor + "' tried to inherit from itself");
                continue;
            }

            // Check if inheritor or any base we're adding already isa this base class
            // This matches Perl's base.pm line 92: next if grep $_->isa($base), ($inheritor, @bases);
            boolean shouldSkip = false;
            
            // Check if inheritor already isa baseClassName
            RuntimeArray isaArgs = new RuntimeArray();
            RuntimeArray.push(isaArgs, new RuntimeScalar(inheritor));
            RuntimeArray.push(isaArgs, new RuntimeScalar(baseClassName));
            if (Universal.isa(isaArgs, RuntimeContextType.SCALAR).getBoolean()) {
                shouldSkip = true;
            }
            
            // Check if any of the bases we're adding already isa baseClassName
            if (!shouldSkip) {
                for (String addedBase : basesToAdd) {
                    RuntimeArray isaArgs2 = new RuntimeArray();
                    RuntimeArray.push(isaArgs2, new RuntimeScalar(addedBase));
                    RuntimeArray.push(isaArgs2, new RuntimeScalar(baseClassName));
                    if (Universal.isa(isaArgs2, RuntimeContextType.SCALAR).getBoolean()) {
                        shouldSkip = true;
                        break;
                    }
                }
            }
            
            if (shouldSkip) {
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

            // Add to our list of bases to add
            basesToAdd.add(baseClassName);
        }

        // Add all the bases to @ISA at the end (like Perl's base.pm line 138)
        RuntimeArray isa = getGlobalArray(inheritor + "::ISA");
        for (String baseClassName : basesToAdd) {
            RuntimeArray.push(isa, new RuntimeScalar(baseClassName));
        }

        return new RuntimeList();
    }
}

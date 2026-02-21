package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/**
 * The Vars class is responsible for creating variables in the current package.
 * It mimics the behavior of Perl's vars module, allowing variables to be declared.
 */
public class Vars extends PerlModuleBase {

    public Vars() {
        super("vars");
    }

    /**
     * Static initializer to set up the Vars module.
     */
    public static void initialize() {
        Vars vars = new Vars();
        try {
            vars.registerMethod("import", "importVars", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing vars method: " + e.getMessage());
        }
    }

    /**
     * Creates the specified variables in the current package.
     *
     * @param args The arguments specifying the variables to create.
     * @param ctx  The context in which the variables are being created.
     * @return A RuntimeList representing the result of the variable creation.
     * @throws PerlCompilerException if there are issues with the variable creation process.
     */
    public static RuntimeList importVars(RuntimeArray args, int ctx) {
        RuntimeArray.shift(args);

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String caller = callerList.scalar().toString();

        // Create the specified variables in the caller's namespace
        for (RuntimeScalar variableObj : args.elements) {
            String variableString = variableObj.toString();
            String fullName = caller + "::" + variableString.substring(1);

            if (variableString.startsWith("$")) {
                // Create a scalar variable
                GlobalVariable.getGlobalVariable(fullName);
            } else if (variableString.startsWith("@")) {
                // Create an array variable
                GlobalVariable.getGlobalArray(fullName);
            } else if (variableString.startsWith("%")) {
                // Create a hash variable
                GlobalVariable.getGlobalHash(fullName);
            } else if (variableString.startsWith("&")) {
                // Create a code variable
                GlobalVariable.getGlobalCodeRef(fullName);
            } else if (variableString.startsWith("*")) {
                // autovivify the bareword handle
                GlobalVariable.getGlobalIO(fullName);
            } else {
                throw new PerlCompilerException("Invalid variable type: " + variableString);
            }
        }

        return new RuntimeList();
    }
}

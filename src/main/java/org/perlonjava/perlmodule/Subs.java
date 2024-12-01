package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

/**
 * The Subs class is responsible for creating variables in the current package.
 * It mimics the behavior of Perl's subs module, allowing variables to be declared.
 */
public class Subs extends PerlModuleBase {

    public Subs() {
        super("subs");
    }

    /**
     * Static initializer to set up the Subs module.
     */
    public static void initialize() {
        Subs subs = new Subs();
        try {
            subs.registerMethod("import", "importSubs", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing subs method: " + e.getMessage());
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
    public static RuntimeList importSubs(RuntimeArray args, int ctx) {
        args.shift();

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String caller = callerList.scalar().toString();

        // Create the specified subroutine entries in the caller's namespace
        for (RuntimeScalar variableObj : args.elements) {
            String variableString = variableObj.toString();
            GlobalVariable.getGlobalCodeRef(caller + "::" + variableString);
        }

        return new RuntimeList();
    }
}

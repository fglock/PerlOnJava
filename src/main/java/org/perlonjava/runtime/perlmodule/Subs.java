package org.perlonjava.runtime.perlmodule;

import org.perlonjava.frontend.parser.ParserTables;
import org.perlonjava.runtime.runtimetypes.*;

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
            subs.registerMethod("mark_overridable", "markOverridable", "$$");
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
        RuntimeArray.shift(args);

        // Determine the caller's namespace
        RuntimeList callerList = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.SCALAR);
        String caller = callerList.scalar().toString();

        // Create the specified subroutine entries in the caller's namespace
        for (RuntimeScalar variableObj : args.elements) {
            String variableString = variableObj.toString();
            String fullName = caller + "::" + variableString;
            GlobalVariable.getGlobalCodeRef(fullName);
            GlobalVariable.isSubs.put(fullName, true);
        }

        return new RuntimeList();
    }

    /**
     * Marks a fully-qualified subroutine name as an override for a core operator.
     * Called from Perl Exporter.pm when importing overridable operators like 'time'.
     *
     * @param args The arguments: package::subname, operator_name
     * @param ctx  The context.
     * @return An empty RuntimeList.
     */
    public static RuntimeList markOverridable(RuntimeArray args, int ctx) {
        if (args.size() >= 2) {
            String fullName = args.get(0).toString();
            String operatorName = args.get(1).toString();
            if (ParserTables.OVERRIDABLE_OP.contains(operatorName)) {
                GlobalVariable.isSubs.put(fullName, true);
            }
        }
        return new RuntimeList();
    }
}

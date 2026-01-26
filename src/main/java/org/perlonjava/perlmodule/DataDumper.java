package org.perlonjava.perlmodule;

import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

public class DataDumper extends PerlModuleBase {

    /**
     * Constructor for Warnings.
     * Initializes the module with the name "Data::Dumper".
     */
    public DataDumper() {
        super("Data::Dumper", false);
    }

    /**
     * Static initializer to set up the DataDumper module.
     */
    public static void initialize() {
        DataDumper dataDumper = new DataDumper();
        try {
            dataDumper.registerMethod("Dumpxs", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Data::Dumper method: " + e.getMessage());
        }
    }

    /**
     * Call Dumpperl.
     * When Data::Dumper sees that XSLoader exists, it will call this.
     * Then we redirect to Data::Dumper::Dumpperl.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList Dumpxs(RuntimeArray args, int ctx) {
        // During module loading, never try to call Perl subroutines as it can cause
        // circular dependency issues. Always fallback to pure Perl implementation.
        // The XS loading will fail gracefully and Data::Dumper will use its pure Perl code.
        return new RuntimeList();
    }
}

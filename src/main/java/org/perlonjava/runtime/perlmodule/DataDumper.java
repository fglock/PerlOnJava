package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

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
            dataDumper.registerMethod("Dumpxs", null);
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
        return RuntimeCode.apply(
                GlobalVariable.getGlobalCodeRef("Data::Dumper::Dumpperl"), args, ctx);
    }
}

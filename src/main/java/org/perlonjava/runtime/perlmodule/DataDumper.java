package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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
            dataDumper.registerMethod("_perlonjava_numified_safe_decimal", "$");
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

    public static RuntimeList _perlonjava_numified_safe_decimal(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _perlonjava_numified_safe_decimal() method");
        }
        return new RuntimeScalar(args.get(0).isDataDumperNumifiedSafeDecimal()).getList();
    }
}

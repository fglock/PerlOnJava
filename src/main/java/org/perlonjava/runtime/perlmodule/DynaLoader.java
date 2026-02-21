package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

public class DynaLoader extends PerlModuleBase {

    public DynaLoader() {
        super("DynaLoader");
    }

    public static void initialize() {
        DynaLoader dynaLoader = new DynaLoader();
        dynaLoader.initializeExporter();
        dynaLoader.defineExport("EXPORT", "bootstrap");
        try {
            dynaLoader.registerMethod("bootstrap", null);
            dynaLoader.registerMethod("boot_DynaLoader", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DynaLoader method: " + e.getMessage());
        }
    }

    public static RuntimeList bootstrap(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            return WarnDie.die(
                    new RuntimeScalar("Usage: DynaLoader::bootstrap(module)"),
                    new RuntimeScalar("\n")
            ).getList();
        }

        String module = args.getFirst().toString();
        return WarnDie.die(
                new RuntimeScalar("Can't load module " + module),
                new RuntimeScalar("\n")
        ).getList();
    }

    public static RuntimeList boot_DynaLoader(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }
}

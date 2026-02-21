package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.operators.WarnDie;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

public class XSLoader extends PerlModuleBase {

    /**
     * Constructor for XSLoader.
     * Initializes the module with the name "XSLoader".
     */
    public XSLoader() {
        super("XSLoader", true);
    }

    /**
     * Static initializer to set up the XSLoader module.
     */
    public static void initialize() {
        XSLoader xsLoader = new XSLoader();
        try {
            xsLoader.registerMethod("load", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing XSLoader method: " + e.getMessage());
        }
    }

    /**
     * Loads a PerlOnJava module.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList load(RuntimeArray args, int ctx) {
        String moduleName = args.getFirst().toString();

        // Convert Perl::Module::Name to org.perlonjava.runtime.perlmodule.PerlModuleName
        String[] parts = moduleName.split("::");
        StringBuilder className1 = new StringBuilder("org.perlonjava.runtime.perlmodule.");
        for (String part : parts) {
            className1.append(part);
        }
        String className = className1.toString();

        try {
            Class<?> clazz = Class.forName(className);
            Method initialize = clazz.getMethod("initialize");
            initialize.invoke(null);
            return scalarTrue.getList();
        } catch (Exception e) {
            return WarnDie.die(
                    new RuntimeScalar("Can't load Java XS module: " + moduleName),
                    new RuntimeScalar("\n")
            ).getList();
        }
    }
}

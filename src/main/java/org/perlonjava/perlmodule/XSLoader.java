package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.lang.reflect.Method;

public class XSLoader extends PerlModuleBase {

    /**
     * Constructor for Warnings.
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

        boolean loaded;
        // Convert Perl::Module::Name to org.perlonjava.perlmodule.PerlModuleName
        String[] parts = moduleName.split("::");
        StringBuilder className1 = new StringBuilder("org.perlonjava.perlmodule.");
        for (String part : parts) {
            className1.append(part);
        }
        String className = className1.toString();
        try {
            Class<?> clazz = Class.forName(className);
            Method initialize = clazz.getMethod("initialize");
            initialize.invoke(null);
            loaded = true;
        } catch (Exception e) {
            // System.err.println("Failed to load Java module: " + moduleName + " (class: " + className + ")");
            // e.printStackTrace();
            loaded = false;
        }
        return new RuntimeScalar(loaded).getList();
    }
}

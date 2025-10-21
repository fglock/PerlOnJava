package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

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
            return scalarTrue.getList();
        } catch (ClassNotFoundException e) {
            // XS module not found - throw exception so eval fails
            // This allows modules like Data::Dumper to fall back to pure Perl
            throw new RuntimeException("Can't load '" + className + "' for module " + moduleName + ": " + e.getMessage());
        } catch (Exception e) {
            // Other errors - throw as well
            throw new RuntimeException("Failed to initialize module " + moduleName + ": " + e.getMessage(), e);
        }
    }
}

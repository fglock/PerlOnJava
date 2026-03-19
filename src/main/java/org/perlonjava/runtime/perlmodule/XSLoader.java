package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.lang.reflect.Method;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;
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
     * <p>
     * If no module name is provided as an argument, uses caller() to determine
     * the calling package name, matching standard Perl XSLoader behavior.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing true on success, false on failure.
     */
    public static RuntimeList load(RuntimeArray args, int ctx) {
        String moduleName;

        if (args.isEmpty() || args.getFirst().toString().isEmpty()) {
            // No module name provided - use caller() to get the calling package
            RuntimeList callerInfo = RuntimeCode.caller(new RuntimeList(), SCALAR);
            if (callerInfo.isEmpty()) {
                return WarnDie.die(
                        new RuntimeScalar("Can't determine module name for XSLoader::load"),
                        new RuntimeScalar("\n")
                ).getList();
            }
            moduleName = callerInfo.scalar().toString();
            if (moduleName.isEmpty()) {
                return WarnDie.die(
                        new RuntimeScalar("Can't determine module name for XSLoader::load"),
                        new RuntimeScalar("\n")
                ).getList();
            }
        } else {
            moduleName = args.getFirst().toString();
        }

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
            // Error message matches pattern /object version|loadable object/ that many
            // CPAN modules (DateTime, JSON::XS, etc.) expect for pure Perl fallback
            return WarnDie.die(
                    new RuntimeScalar("Can't load loadable object for module " + moduleName + 
                                      ": no Java XS implementation available"),
                    new RuntimeScalar("\n")
            ).getList();
        }
    }
}

package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.lang.reflect.Field;
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
            
            // Check version if provided
            if (args.size() > 1) {
                String requestedVersion = args.get(1).toString();
                if (!requestedVersion.isEmpty()) {
                    checkVersion(clazz, moduleName, requestedVersion);
                }
            }
            
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

    /**
     * Checks if the Java XS implementation version is compatible with the requested version.
     * If versions differ significantly, emits a warning but continues loading.
     * 
     * @param clazz The loaded Java XS class
     * @param moduleName The Perl module name
     * @param requestedVersion The version requested by XSLoader::load()
     */
    private static void checkVersion(Class<?> clazz, String moduleName, String requestedVersion) {
        try {
            Field versionField = clazz.getField("XS_VERSION");
            String javaVersion = (String) versionField.get(null);
            
            if (javaVersion != null && !versionsCompatible(javaVersion, requestedVersion)) {
                System.err.println("Warning: " + moduleName + " Java XS version " + javaVersion + 
                                   " may not be compatible with requested version " + requestedVersion);
            }
        } catch (NoSuchFieldException e) {
            // No XS_VERSION field - that's OK, just skip version check
        } catch (Exception e) {
            // Any other error - skip version check silently
        }
    }

    /**
     * Checks if two version strings are compatible.
     * Considers versions compatible if they have the same major version.
     * 
     * @param javaVersion The version from the Java XS implementation
     * @param requestedVersion The version requested by the Perl module
     * @return true if versions are likely compatible
     */
    private static boolean versionsCompatible(String javaVersion, String requestedVersion) {
        // Remove underscores (dev versions like 1.65_01)
        javaVersion = javaVersion.replace("_", "");
        requestedVersion = requestedVersion.replace("_", "");
        
        // Extract major version (part before first dot)
        String javaMajor = javaVersion.contains(".") ? 
                           javaVersion.substring(0, javaVersion.indexOf('.')) : javaVersion;
        String requestedMajor = requestedVersion.contains(".") ? 
                                requestedVersion.substring(0, requestedVersion.indexOf('.')) : requestedVersion;
        
        // Same major version is considered compatible
        return javaMajor.equals(requestedMajor);
    }
}

package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.util.List;

import static org.perlonjava.runtime.GlobalContext.getGlobalCodeRef;

/**
 * The Universal class provides methods that are universally available to all objects in a Perl-like environment.
 * It extends PerlModuleBase to leverage module initialization and method registration.
 */
public class Universal extends PerlModuleBase {

    /**
     * Constructor for Universal.
     * Initializes the module with the name "UNIVERSAL".
     */
    public Universal() {
        super("UNIVERSAL");
    }

    /**
     * Static initializer to set up the UNIVERSAL module.
     * This method registers universally available methods.
     */
    public static void initialize() {
        Universal universal = new Universal();
        try {
            // Register methods with their respective signatures
            universal.registerMethod("can", "$");
            universal.registerMethod("isa", "$");
            universal.registerMethod("DOES", "$");
            universal.registerMethod("VERSION", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing UNIVERSAL method: " + e.getMessage());
        }
    }

    /**
     * Checks if the object can perform a given method.
     * Note: This is a Perl method, it expects `this` to be the first argument.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the method if it can be performed, otherwise false.
     */
    public static RuntimeList can(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for can() method");
        }
        RuntimeScalar object = args.get(0);
        String methodName = args.get(1).toString();

        // Retrieve Perl class name
        String perlClassName;
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) object.value).blessId;
                if (blessId == 0) {
                    return new RuntimeScalar(false).getList();
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                return new RuntimeScalar(false).getList();
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    return new RuntimeScalar(false).getList();
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Check the method cache
        String normalizedMethodName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        RuntimeScalar cachedMethod = InheritanceResolver.getCachedMethod(normalizedMethodName);
        if (cachedMethod != null) {
            return cachedMethod.getList();
        }

        // Get the linearized inheritance hierarchy using C3
        for (String className : InheritanceResolver.linearizeC3(perlClassName)) {
            String normalizedClassMethodName = NameNormalizer.normalizeVariableName(methodName, className);
            if (GlobalContext.existsGlobalCodeRef(normalizedClassMethodName)) {
                // If the method is found, return it
                return getGlobalCodeRef(normalizedClassMethodName).getList();
            }
        }
        return new RuntimeScalar(false).getList();
    }

    /**
     * Checks if the object is of a given class or a subclass.
     * Note: This is a Perl method, it expects `this` to be the first argument.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the object is of the given class or subclass.
     */
    public static RuntimeList isa(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for isa() method");
        }
        RuntimeScalar object = args.get(0);
        String argString = args.get(1).toString();

        // Retrieve Perl class name
        String perlClassName;
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) object.value).blessId;
                if (blessId == 0) {
                    return new RuntimeScalar(false).getList();
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                return new RuntimeScalar(false).getList();
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    return new RuntimeScalar(false).getList();
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = InheritanceResolver.linearizeC3(perlClassName);

        return new RuntimeScalar(linearizedClasses.contains(argString)).getList();
    }

    /**
     * Checks if the object does a given role.
     * This method is equivalent to isa() in this context.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the object does the given role.
     */
    public static RuntimeList DOES(RuntimeArray args, int ctx) {
        return isa(args, ctx);
    }

    /**
     * Retrieves the version of the package the object is blessed into.
     * If a REQUIRE argument is provided, it compares the package version with REQUIRE.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeScalar representing the version.
     * @throws PerlCompilerException if the version comparison fails.
     */
    public static RuntimeList VERSION(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for VERSION() method");
        }
        RuntimeScalar object = args.get(0);
        String requireVersion = args.size() == 2 ? args.get(1).toString() : null;

        // Retrieve Perl class name
        String perlClassName;
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) object.value).blessId;
                if (blessId == 0) {
                    throw new PerlCompilerException("Object is not blessed into a package");
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                throw new PerlCompilerException("Object is undefined");
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    throw new PerlCompilerException("Object is not blessed into a package");
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Retrieve the $VERSION variable from the package
        String versionVariableName = NameNormalizer.normalizeVariableName("VERSION", perlClassName);
        RuntimeScalar versionScalar = GlobalContext.getGlobalVariable(versionVariableName);

        if (versionScalar == null || versionScalar.type != RuntimeScalarType.STRING) {
            throw new PerlCompilerException("Package version is not defined or not a valid string");
        }

        String packageVersion = versionScalar.toString();

        // If REQUIRE is provided, compare versions
        if (requireVersion != null) {
            if (!isLaxVersion(packageVersion) || !isLaxVersion(requireVersion)) {
                throw new PerlCompilerException("Either package version or REQUIRE is not a lax version number");
            }
            if (compareVersions(packageVersion, requireVersion) < 0) {
                throw new PerlCompilerException(perlClassName + " version " + requireVersion + " required--this is only version " + packageVersion);
            }
        }

        return new RuntimeScalar(packageVersion).getList();
    }

    /**
     * Checks if a version string is a lax version number.
     *
     * @param version The version string to check.
     * @return True if the version is a lax version number, false otherwise.
     */
    private static boolean isLaxVersion(String version) {
        // Implement a simple check for lax version numbers
        return version.matches("\\d+(\\.\\d+)*");
    }

    /**
     * Compares two version strings.
     *
     * @param v1 The first version string.
     * @param v2 The second version string.
     * @return A negative integer, zero, or a positive integer as the first version is less than, equal to, or greater than the second.
     */
    private static int compareVersions(String v1, String v2) {
        String[] v1Parts = v1.split("\\.");
        String[] v2Parts = v2.split("\\.");
        int length = Math.max(v1Parts.length, v2Parts.length);
        for (int i = 0; i < length; i++) {
            int v1Part = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2Part = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;
            if (v1Part != v2Part) {
                return v1Part - v2Part;
            }
        }
        return 0;
    }
}

package org.perlonjava.perlmodule;

import org.perlonjava.mro.InheritanceResolver;
import org.perlonjava.mro.InheritanceResolver.MROAlgorithm;
import org.perlonjava.runtime.*;

import java.util.*;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * The Mro class provides Perl's mro (Method Resolution Order) module functionality.
 * It allows switching between different MRO algorithms (DFS and C3) and provides
 * utilities for introspecting the inheritance hierarchy.
 */
public class Mro extends PerlModuleBase {

    // Package generation counters
    private static final Map<String, Integer> packageGenerations = new HashMap<>();

    // Reverse ISA cache (which classes inherit from a given class)
    private static final Map<String, Set<String>> isaRevCache = new HashMap<>();

    /**
     * Constructor for Mro.
     * Initializes the module with the name "mro".
     */
    public Mro() {
        super("mro");
    }

    /**
     * Static initializer to set up the mro module.
     */
    public static void initialize() {
        Mro mro = new Mro();
        try {
            // Register mro methods
            mro.registerMethod("get_linear_isa", "$;$");
            mro.registerMethod("set_mro", "$$");
            mro.registerMethod("get_mro", "$");
            mro.registerMethod("get_isarev", "$");
            mro.registerMethod("is_universal", "$");
            mro.registerMethod("invalidate_all_method_caches", "");
            mro.registerMethod("method_changed_in", "$");
            mro.registerMethod("get_pkg_gen", "$");
            mro.registerMethod("import", "useMro", ";@");
            mro.registerMethod("unimport", "noMro", ";@");

            // Register next::method, next::can, and maybe::next::method
            mro.registerMethod("next::method", "nextMethod", "@");
            mro.registerMethod("next::can", "nextCan", "@");
            mro.registerMethod("maybe::next::method", "maybeNextMethod", "@");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing mro method: " + e.getMessage());
        }

    }

    /**
     * Implements next::method functionality - wrapper for NextMethod.nextMethod
     */
    public static RuntimeList nextMethod(RuntimeArray args, int ctx) {
        return org.perlonjava.runtime.NextMethod.nextMethod(args, ctx);
    }

    /**
     * Implements next::can functionality - wrapper for NextMethod.nextCan
     */
    public static RuntimeList nextCan(RuntimeArray args, int ctx) {
        return org.perlonjava.runtime.NextMethod.nextCan(args, ctx);
    }

    /**
     * Implements maybe::next::method functionality - wrapper for NextMethod.maybeNextMethod
     */
    public static RuntimeList maybeNextMethod(RuntimeArray args, int ctx) {
        return org.perlonjava.runtime.NextMethod.maybeNextMethod(args, ctx);
    }

    /**
     * Implements the import method for 'use mro'.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useMro(RuntimeArray args, int ctx) {
        if (args.size() == 1) {
            // use mro; - enables next::method globally
            Feature.featureManager.enableFeatureBundle("perlonjava::internal::next_method");
            return new RuntimeList();
        }

        // Get the calling package using caller()
        RuntimeList callerInfo = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.LIST);
        String callerPackage = callerInfo.elements.isEmpty() ? "main" : callerInfo.elements.get(0).toString();

        for (int i = 1; i < args.size(); i++) {
            String mroType = args.get(i).toString();

            if (mroType.equals("dfs")) {
                InheritanceResolver.setPackageMRO(callerPackage, MROAlgorithm.DFS);
            } else if (mroType.equals("c3")) {
                InheritanceResolver.setPackageMRO(callerPackage, MROAlgorithm.C3);
                // Enable the c3 feature
                Feature.featureManager.enableFeatureBundle("perlonjava::internal::mro_c3");
            } else {
                throw new PerlCompilerException("Invalid mro type '" + mroType + "'");
            }
        }

        return new RuntimeList();
    }

    /**
     * Implements the unimport method for 'no mro'.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList noMro(RuntimeArray args, int ctx) {
        // Disable next::method feature
        Feature.featureManager.disableFeatureBundle("perlonjava::internal::next_method");
        return new RuntimeList();
    }

    /**
     * Returns the linearized MRO of the given class.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing an array reference of the linearized MRO.
     */
    public static RuntimeList get_linear_isa(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for mro::get_linear_isa()");
        }

        String className = args.get(0).toString();
        String mroType = args.size() == 2 ? args.get(1).toString() : null;

        List<String> linearized;

        // Check if class exists
        if (!GlobalVariable.existsGlobalArray(className + "::ISA")) {
            // Class doesn't exist, return just the classname
            linearized = Arrays.asList(className);
        } else {
            // Invalidate cache to ensure we see any ISA changes
            InheritanceResolver.invalidateCache();

            // Get linearized MRO based on type
            if (mroType == null) {
                // Use current MRO for the class
                linearized = InheritanceResolver.linearizeHierarchy(className);
            } else if (mroType.equals("dfs")) {
                linearized = org.perlonjava.mro.DFS.linearizeDFS(className);
            } else if (mroType.equals("c3")) {
                linearized = org.perlonjava.mro.C3.linearizeC3(className);
            } else {
                throw new PerlCompilerException("Invalid mro type '" + mroType + "'");
            }

            // Remove UNIVERSAL from the list as per spec
            linearized = new ArrayList<>(linearized);
            linearized.remove("UNIVERSAL");
        }

        // Convert to RuntimeArray
        RuntimeArray result = new RuntimeArray();
        for (String cls : linearized) {
            result.push(new RuntimeScalar(cls));
        }

        return result.createReference().getList();
    }

    /**
     * Sets the MRO of the given class.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList set_mro(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for mro::set_mro()");
        }

        String className = args.get(0).toString();
        String mroType = args.get(1).toString();

        if (mroType.equals("dfs")) {
            InheritanceResolver.setPackageMRO(className, MROAlgorithm.DFS);
        } else if (mroType.equals("c3")) {
            InheritanceResolver.setPackageMRO(className, MROAlgorithm.C3);
        } else {
            throw new PerlCompilerException("Invalid mro type '" + mroType + "'");
        }

        // Increment package generation
        incrementPackageGeneration(className);

        return new RuntimeList();
    }

    /**
     * Returns the MRO type of the given class.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the MRO type.
     */
    public static RuntimeList get_mro(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for mro::get_mro()");
        }

        String className = args.get(0).toString();
        MROAlgorithm mro = InheritanceResolver.getPackageMRO(className);

        String mroType = mro == MROAlgorithm.C3 ? "c3" : "dfs";
        return new RuntimeScalar(mroType).getList();
    }

    /**
     * Builds the reverse ISA cache by scanning for packages that have ISA arrays.
     * This is a more targeted approach that only looks at packages with inheritance.
     */
    private static void buildIsaRevCache() {
        isaRevCache.clear();

        // Instead of getting all packages, we'll build the cache on-demand
        // This method will be called when get_isarev is used
    }

    /**
     * Gets the mro_isarev for this class - all classes that inherit from it.
     * Builds the reverse ISA relationships on demand.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing an array reference of inheriting classes.
     */
    public static RuntimeList get_isarev(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for mro::get_isarev()");
        }

        String className = args.get(0).toString();

        // For UNIVERSAL, we need special handling
        if (className.equals("UNIVERSAL")) {
            // Don't return all classes for UNIVERSAL as per spec
            return new RuntimeScalar(new RuntimeArray()).getList();
        }

        // Build reverse ISA on demand by checking which classes inherit from the target
        Set<String> isarev = new HashSet<>();

        // We need to check loaded packages by looking for those that might inherit from className
        // Since we can't iterate all packages, we'll need to maintain this cache differently

        // For now, return empty array - this would need to be populated when @ISA is modified
        RuntimeArray result = new RuntimeArray();
        for (String cls : isarev) {
            result.push(new RuntimeScalar(cls));
        }

        return new RuntimeScalar(result).getList();
    }

    /**
     * Checks if the given class is UNIVERSAL or a parent of UNIVERSAL.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean.
     */
    public static RuntimeList is_universal(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for mro::is_universal()");
        }

        String className = args.get(0).toString();

        // Only UNIVERSAL itself is universal in the base system
        boolean isUniversal = className.equals("UNIVERSAL");

        return new RuntimeScalar(isUniversal).getList();
    }

    /**
     * Invalidates all method caches.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList invalidate_all_method_caches(RuntimeArray args, int ctx) {
        InheritanceResolver.invalidateCache();
        isaRevCache.clear();

        // Increment all package generations
        for (String pkg : new HashSet<>(packageGenerations.keySet())) {
            incrementPackageGeneration(pkg);
        }

        return new RuntimeList();
    }

    /**
     * Invalidates the method cache of any classes dependent on the given class.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList method_changed_in(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for mro::method_changed_in()");
        }

        String className = args.get(0).toString();

        // Invalidate the method cache
        InheritanceResolver.invalidateCache();

        // Build isarev if needed and invalidate dependent classes
        if (isaRevCache.isEmpty()) {
            buildIsaRevCache();
        }

        Set<String> dependents = isaRevCache.getOrDefault(className, new HashSet<>());
        dependents.add(className); // Include the class itself

        // Increment package generation for all dependent classes
        for (String dependent : dependents) {
            incrementPackageGeneration(dependent);
        }

        return new RuntimeList();
    }

    /**
     * Returns the package generation number.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the generation number.
     */
    public static RuntimeList get_pkg_gen(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for mro::get_pkg_gen()");
        }

        String className = args.get(0).toString();

        // Check if stash exists
        if (!GlobalVariable.existsGlobalArray(className + "::ISA")) {
            return new RuntimeScalar(0).getList();
        }

        Integer gen = packageGenerations.getOrDefault(className, 1);
        return new RuntimeScalar(gen).getList();
    }

    /**
     * Increments the package generation counter.
     *
     * @param packageName The name of the package.
     */
    private static void incrementPackageGeneration(String packageName) {
        Integer current = packageGenerations.getOrDefault(packageName, 1);
        packageGenerations.put(packageName, current + 1);
    }
}

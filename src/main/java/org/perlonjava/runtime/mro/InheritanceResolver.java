package org.perlonjava.runtime.mro;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.*;

/**
 * The InheritanceResolver class provides methods for resolving method inheritance
 * and linearizing class hierarchies using the C3 or dfs algorithm. It maintains caches
 * for method resolution and linearized class hierarchies to improve performance.
 */
public class InheritanceResolver {
    private static final boolean TRACE_METHOD_RESOLUTION = false;  // Set to true for debugging

    // ---- Accessors delegating to PerlRuntime.current() ----

    /** Returns the linearized classes cache from the current PerlRuntime. */
    static Map<String, List<String>> getLinearizedClassesCache() {
        return PerlRuntime.current().linearizedClassesCache;
    }

    /** Returns the method cache from the current PerlRuntime. */
    private static Map<String, RuntimeScalar> getMethodCache() {
        return PerlRuntime.current().methodCache;
    }

    /** Returns the overload context cache from the current PerlRuntime. */
    private static Map<Integer, OverloadContext> getOverloadContextCache() {
        return PerlRuntime.current().overloadContextCache;
    }

    /** Returns the ISA state cache from the current PerlRuntime. */
    private static Map<String, List<String>> getIsaStateCache() {
        return PerlRuntime.current().isaStateCache;
    }

    /** Returns the per-package MRO map from the current PerlRuntime. */
    private static Map<String, MROAlgorithm> getPackageMROMap() {
        return PerlRuntime.current().packageMRO;
    }

    // ---- autoloadEnabled getter/setter ----

    public static boolean isAutoloadEnabled() {
        return PerlRuntime.current().autoloadEnabled;
    }

    public static void setAutoloadEnabled(boolean enabled) {
        PerlRuntime.current().autoloadEnabled = enabled;
    }

    // ---- currentMRO getter (used internally) ----

    private static MROAlgorithm getCurrentMRO() {
        return PerlRuntime.current().currentMRO;
    }

    /**
     * Sets the default MRO algorithm.
     *
     * @param algorithm The MRO algorithm to use as default.
     */
    public static void setDefaultMRO(MROAlgorithm algorithm) {
        PerlRuntime.current().currentMRO = algorithm;
        invalidateCache();
    }

    /**
     * Sets the MRO algorithm for a specific package.
     *
     * @param packageName The name of the package.
     * @param algorithm   The MRO algorithm to use for this package.
     */
    public static void setPackageMRO(String packageName, MROAlgorithm algorithm) {
        getPackageMROMap().put(packageName, algorithm);
        invalidateCache();
    }

    /**
     * Gets the MRO algorithm for a specific package.
     *
     * @param packageName The name of the package.
     * @return The MRO algorithm for the package, or the default if not set.
     */
    public static MROAlgorithm getPackageMRO(String packageName) {
        return getPackageMROMap().getOrDefault(packageName, getCurrentMRO());
    }

    /**
     * Linearizes the inheritance hierarchy for a class using the appropriate MRO algorithm.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in the order of method resolution.
     */
    public static List<String> linearizeHierarchy(String className) {
        // Check if ISA has changed and invalidate cache if needed
        if (hasIsaChanged(className)) {
            invalidateCacheForClass(className);
        }

        Map<String, List<String>> cache = getLinearizedClassesCache();
        // Check cache first
        List<String> cached = cache.get(className);
        if (cached != null) {
            // Return a copy of the cached list to prevent modification of the cached version
            return new ArrayList<>(cached);
        }

        MROAlgorithm mro = getPackageMRO(className);

        List<String> result;
        switch (mro) {
            case C3:
                result = C3.linearizeC3(className);
                break;
            case DFS:
                result = DFS.linearizeDFS(className);
                break;
            default:
                throw new IllegalStateException("Unknown MRO algorithm: " + mro);
        }

        // Cache the result (store a copy to prevent external modifications)
        cache.put(className, new ArrayList<>(result));
        return result;
    }

    /**
     * Checks if the @ISA array for a class has changed since last cached.
     */
    private static boolean hasIsaChanged(String className) {
        RuntimeArray isaArray = GlobalVariable.getGlobalArray(className + "::ISA");
        
        // Build current ISA list
        List<String> currentIsa = new ArrayList<>();
        for (RuntimeBase entity : isaArray.elements) {
            String parentName = entity.toString();
            if (parentName != null && !parentName.isEmpty()) {
                currentIsa.add(parentName);
            }
        }

        Map<String, List<String>> isCache = getIsaStateCache();
        List<String> cachedIsa = isCache.get(className);

        // If ISA changed, update cache and return true
        if (!currentIsa.equals(cachedIsa)) {
            isCache.put(className, currentIsa);
            return true;
        }
        
        return false;
    }

    /**
     * Invalidate cache for a specific class and its dependents.
     */
    private static void invalidateCacheForClass(String className) {
        Map<String, List<String>> linCache = getLinearizedClassesCache();
        Map<String, RuntimeScalar> mCache = getMethodCache();

        // Remove exact class and subclasses from linearization cache
        linCache.remove(className);
        linCache.entrySet().removeIf(entry -> entry.getKey().startsWith(className + "::"));

        // Remove from method cache (entries for this class and subclasses)
        mCache.entrySet().removeIf(entry ->
                entry.getKey().startsWith(className + "::") || entry.getKey().contains("::" + className + "::"));

        // Could also notify dependents here if we had that information
    }

    /**
     * Invalidates the caches for method resolution and linearized class hierarchies.
     * This should be called whenever the class hierarchy or method definitions change.
     */
    public static void invalidateCache() {
        getMethodCache().clear();
        getLinearizedClassesCache().clear();
        getOverloadContextCache().clear();
        getIsaStateCache().clear();
        // Also clear the inline method cache in RuntimeCode
        RuntimeCode.clearInlineMethodCache();
    }

    /**
     * Retrieves a cached OverloadContext for the given blessing ID.
     *
     * @param blessId The blessing ID of the class.
     * @return The cached OverloadContext, or null if not found.
     */
    public static OverloadContext getCachedOverloadContext(int blessId) {
        return getOverloadContextCache().get(blessId);
    }

    /**
     * Caches an OverloadContext for the given blessing ID.
     *
     * @param blessId The blessing ID of the class.
     * @param context The OverloadContext to cache (can be null to indicate no overloading).
     */
    public static void cacheOverloadContext(int blessId, OverloadContext context) {
        getOverloadContextCache().put(blessId, context);
    }

    /**
     * Retrieves a cached method for the given normalized method name.
     *
     * @param normalizedMethodName The normalized name of the method.
     * @return The cached RuntimeScalar representing the method, or null if not found.
     */
    public static RuntimeScalar getCachedMethod(String normalizedMethodName) {
        return getMethodCache().get(normalizedMethodName);
    }

    /**
     * Caches a method for the given normalized method name.
     *
     * @param normalizedMethodName The normalized name of the method.
     * @param method               The RuntimeScalar representing the method to cache.
     */
    public static void cacheMethod(String normalizedMethodName, RuntimeScalar method) {
        getMethodCache().put(normalizedMethodName, method);
    }

    /**
     * Populates the isaMap with @ISA arrays for each class.
     *
     * @param className The name of the class to populate.
     * @param isaMap    The map to populate with @ISA arrays.
     */
    static void populateIsaMap(String className, Map<String, List<String>> isaMap) {
        populateIsaMapHelper(className, isaMap, new HashSet<>());
    }

    private static void populateIsaMapHelper(String className,
                                             Map<String, List<String>> isaMap,
                                             Set<String> currentPath) {
        if (isaMap.containsKey(className)) {
            return; // Already populated
        }

        // Check for circular inheritance
        if (currentPath.contains(className)) {
            throw new PerlCompilerException("Recursive inheritance detected involving class '" + className + "'");
        }

        currentPath.add(className);

        // Retrieve @ISA array for the given class
        RuntimeArray isaArray = GlobalVariable.getGlobalArray(className + "::ISA");
        List<String> parents = new ArrayList<>();
        for (RuntimeBase entity : isaArray.elements) {
            String parentName = entity.toString();
            // Handle undef elements as "main" for Perl compatibility
            if (parentName == null || parentName.equals("")) {
                if (!entity.getDefinedBoolean()) {
                    parentName = "main";
                } else {
                    continue; // Skip empty but defined strings
                }
            }
            if (!parentName.isEmpty()) {
                // Normalize old-style ' separator to :: (e.g., Foo'Bar -> Foo::Bar)
                parentName = NameNormalizer.normalizePackageName(parentName);
                parents.add(parentName);
            }
        }

        isaMap.put(className, parents);

        // Recursively populate for parent classes
        for (String parent : parents) {
            populateIsaMapHelper(parent, isaMap, currentPath);
        }

        currentPath.remove(className);
    }

    /**
     * Searches for a method in the class hierarchy starting from a specific index.
     * Uses method caching to improve performance for both found and not-found methods.
     *
     * <p><b>Method Resolution Process:</b>
     * <ol>
     *   <li>Check method cache for previously resolved lookups</li>
     *   <li>Linearize the class hierarchy using C3 or DFS algorithm</li>
     *   <li>Search each class in order for the method</li>
     *   <li>For each class, normalize method name: {@code ClassName::methodName}</li>
     *   <li>Check if method exists in global symbol table</li>
     *   <li>Fall back to AUTOLOAD if method not found (except for overload markers)</li>
     * </ol>
     *
     * <p><b>Overload Methods:</b>
     * Overload marker methods like {@code ((} and {@code ()} are exempt from AUTOLOAD
     * because they should be explicitly defined by the overload pragma.
     *
     * @param methodName     The name of the method to find (e.g., "((", "(0+", "normal_method")
     * @param perlClassName  The Perl class name to start the search from (e.g., "Math::BigInt::")
     * @param cacheKey       The cache key to use for the method cache (null to use default cache key)
     * @param startFromIndex The index in the linearized hierarchy to start searching from (used for SUPER:: calls)
     * @return RuntimeScalar representing the found method, or null if not found
     */
    public static RuntimeScalar findMethodInHierarchy(String methodName, String perlClassName, String cacheKey, int startFromIndex) {
        if (TRACE_METHOD_RESOLUTION) {
            System.err.println("TRACE InheritanceResolver.findMethodInHierarchy:");
            System.err.println("  methodName: '" + methodName + "'");
            System.err.println("  perlClassName: '" + perlClassName + "'");
            System.err.println("  startFromIndex: " + startFromIndex);
            System.err.flush();
        }

        if (cacheKey == null) {
            // Normalize the method name for consistent caching
            cacheKey = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        }

        if (TRACE_METHOD_RESOLUTION) {
            System.err.println("  cacheKey: '" + cacheKey + "'");
            System.err.flush();
        }

        // Check if ISA changed for this class - if so, invalidate relevant caches
        if (hasIsaChanged(perlClassName)) {
            invalidateCacheForClass(perlClassName);
        }

        // Check the method cache - handles both found and not-found cases
        Map<String, RuntimeScalar> mCache = getMethodCache();
        if (mCache.containsKey(cacheKey)) {
            if (TRACE_METHOD_RESOLUTION) {
                System.err.println("  Found in cache: " + (mCache.get(cacheKey) != null ? "YES" : "NULL"));
                System.err.flush();
            }
            return mCache.get(cacheKey);
        }

        // Get the linearized inheritance hierarchy using the appropriate MRO
        List<String> linearizedClasses = linearizeHierarchy(perlClassName);

        if (TRACE_METHOD_RESOLUTION) {
            System.err.println("  Linearized classes: " + linearizedClasses);
            System.err.flush();
        }

        // Perl MRO: first pass — search all classes (including UNIVERSAL) for the method.
        // AUTOLOAD is only checked after the entire hierarchy has been searched.
        for (int i = startFromIndex; i < linearizedClasses.size(); i++) {
            String className = linearizedClasses.get(i);
            String effectiveClassName = GlobalVariable.resolveStashAlias(className);
            String normalizedClassMethodName = NameNormalizer.normalizeVariableName(methodName, effectiveClassName);

            if (TRACE_METHOD_RESOLUTION) {
                System.err.println("  Checking class: '" + className + "'");
                System.err.println("  Normalized name: '" + normalizedClassMethodName + "'");
                System.err.println("  Exists: " + GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName));
                System.err.flush();
            }

            if (GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName)) {
                RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(normalizedClassMethodName);
                if (!codeRef.getDefinedBoolean()) {
                    continue;
                }
                cacheMethod(cacheKey, codeRef);
                if (TRACE_METHOD_RESOLUTION) {
                    System.err.println("  FOUND method!");
                    System.err.flush();
                }
                return codeRef;
            }
        }

        // Second pass — method not found anywhere, check AUTOLOAD in class hierarchy.
        // This matches Perl semantics: AUTOLOAD is only tried after the full MRO
        // search (including UNIVERSAL) fails to find the method.
        if (isAutoloadEnabled() && !methodName.startsWith("(")) {
            for (int i = startFromIndex; i < linearizedClasses.size(); i++) {
                String className = linearizedClasses.get(i);
                String effectiveClassName = GlobalVariable.resolveStashAlias(className);
                String autoloadName = (effectiveClassName.endsWith("::") ? effectiveClassName : effectiveClassName + "::") + "AUTOLOAD";
                if (GlobalVariable.existsGlobalCodeRef(autoloadName)) {
                    RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadName);
                    if (autoload.getDefinedBoolean()) {
                        // Use the AUTOLOAD sub's CvSTASH (packageName) for $AUTOLOAD,
                        // not the glob's package. Perl sets $AUTOLOAD in the package
                        // where the AUTOLOAD sub was compiled, which matters for closures
                        // installed in proxy namespaces (e.g., Template::Plugin::Procedural).
                        RuntimeCode autoloadCode = (RuntimeCode) autoload.value;
                        String cvStash = autoloadCode.packageName;
                        if (cvStash != null && !cvStash.isEmpty()) {
                            autoloadCode.autoloadVariableName = cvStash + "::AUTOLOAD";
                        } else {
                            autoloadCode.autoloadVariableName = autoloadName;
                        }
                        cacheMethod(cacheKey, autoload);
                        return autoload;
                    }
                }
            }
        }

        // Cache the fact that method was not found (using null)
        mCache.put(cacheKey, null);
        return null;
    }

    // MRO algorithm selection
    public enum MROAlgorithm {
        C3,
        DFS
    }
}
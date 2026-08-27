package org.perlonjava.runtime.mro;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The InheritanceResolver class provides methods for resolving method inheritance
 * and linearizing class hierarchies using the C3 or dfs algorithm. It maintains caches
 * for method resolution and linearized class hierarchies to improve performance.
 */
public class InheritanceResolver {
    private static final boolean TRACE_METHOD_RESOLUTION = false;  // Set to true for debugging
    // Temporary bridge while package symbol tables remain shared until Phase 8.
    private static final AtomicLong SHARED_SYMBOL_MUTATION_EPOCH = new AtomicLong();
    private static final AtomicLong SHARED_ISA_MUTATION_EPOCH = new AtomicLong();

    static MroRuntimeState currentState() {
        MroRuntimeState state = PerlRuntime.current().mroState();
        if (state.observeSharedMutationEpochs(
                SHARED_SYMBOL_MUTATION_EPOCH.get(), SHARED_ISA_MUTATION_EPOCH.get())) {
            invalidateDependentRuntimeCaches();
        }
        return state;
    }

    /** Observe mutations to the temporarily shared Phase 8 symbol tables. */
    public static void synchronizeCurrentRuntime() {
        currentState();
    }

    private static void invalidateDependentRuntimeCaches() {
        NameNormalizer.invalidateBlessIdCache();
        RuntimeCode.clearInlineMethodCache();
        DestroyDispatch.invalidateCache();
    }

    private static void clearCurrentRuntimeCaches() {
        currentState().clearDerivedCaches();
        invalidateDependentRuntimeCaches();
    }

    /**
     * Sets the default MRO algorithm.
     *
     * @param algorithm The MRO algorithm to use as default.
     */
    public static void setDefaultMRO(MROAlgorithm algorithm) {
        currentState().setDefaultMro(algorithm);
        clearCurrentRuntimeCaches();
    }

    /**
     * Sets the MRO algorithm for a specific package.
     *
     * @param packageName The name of the package.
     * @param algorithm   The MRO algorithm to use for this package.
     */
    public static void setPackageMRO(String packageName, MROAlgorithm algorithm) {
        currentState().packageMro().put(packageName, algorithm);
        clearCurrentRuntimeCaches();
    }

    /**
     * Gets the MRO algorithm for a specific package.
     *
     * @param packageName The name of the package.
     * @return The MRO algorithm for the package, or the default if not set.
     */
    public static MROAlgorithm getPackageMRO(String packageName) {
        MroRuntimeState state = currentState();
        return state.packageMro().getOrDefault(packageName, state.defaultMro());
    }

    public static boolean isAutoloadEnabled() {
        return currentState().autoloadEnabled();
    }

    public static void setAutoloadEnabled(boolean enabled) {
        currentState().setAutoloadEnabled(enabled);
    }

    /**
     * Linearizes the inheritance hierarchy for a class always using C3.
     * This is used by next::method which always uses C3 regardless of the class's MRO setting,
     * matching Perl 5 behavior where next::method always uses C3 linearization.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in C3 order.
     */
    public static List<String> linearizeC3Always(String className) {
        MroRuntimeState state = currentState();
        // Check if ISA has changed and invalidate cache if needed
        if (hasIsaChanged(className, state)) {
            invalidateCacheForClass(className, state);
        }

        // Use a separate cache key for C3-always linearization
        String cacheKey = className + "::__C3__";
        List<String> cached = state.linearizedClassesCache().get(cacheKey);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        List<String> result = C3.linearizeC3(className);

        // Cache the result
        state.linearizedClassesCache().put(cacheKey, new ArrayList<>(result));
        return result;
    }

    /**
     * Linearizes the inheritance hierarchy for a class using the appropriate MRO algorithm.
     *
     * @param className The name of the class to linearize.
     * @return A list of class names in the order of method resolution.
     */
    public static List<String> linearizeHierarchy(String className) {
        MroRuntimeState state = currentState();
        // Check if ISA has changed and invalidate cache if needed
        if (hasIsaChanged(className, state)) {
            invalidateCacheForClass(className, state);
        }

        // Check cache first
        List<String> cached = state.linearizedClassesCache().get(className);
        if (cached != null) {
            // Return a copy of the cached list to prevent modification of the cached version
            return new ArrayList<>(cached);
        }

        MROAlgorithm mro = state.packageMro().getOrDefault(className, state.defaultMro());

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
        state.linearizedClassesCache().put(className, new ArrayList<>(result));
        return result;
    }

    /**
     * Checks if the @ISA array for a class has changed since last cached.
     */
    private static boolean hasIsaChanged(String className, MroRuntimeState state) {
        RuntimeArray isaArray = getIsaArrayForClass(className);
        
        // Build current ISA list
        List<String> currentIsa = new ArrayList<>();
        for (RuntimeBase entity : visibleArrayElements(isaArray)) {
            String parentName = entity.toString();
            if (parentName != null && !parentName.isEmpty()) {
                currentIsa.add(parentName);
            }
        }

        List<String> cachedIsa = state.isaStateCache().get(className);

        // If ISA changed, update cache and return true
        if (!currentIsa.equals(cachedIsa)) {
            state.isaStateCache().put(className, currentIsa);
            return true;
        }
        
        return false;
    }

    /**
     * Invalidate cache for a specific class and its dependents.
     */
    private static void invalidateCacheForClass(String className, MroRuntimeState state) {
        Map<String, List<String>> linearizedClassesCache = state.linearizedClassesCache();
        Map<String, RuntimeScalar> methodCache = state.methodCache();
        // Remove exact class and subclasses from linearization cache
        linearizedClassesCache.remove(className);
        linearizedClassesCache.entrySet().removeIf(entry -> entry.getKey().startsWith(className + "::"));

        // Remove from method cache (entries for this class and subclasses)
        methodCache.entrySet().removeIf(entry ->
                entry.getKey().startsWith(className + "::") || entry.getKey().contains("::" + className + "::"));

        // @ISA changes can make an existing package inherit overloads after it
        // was first blessed; force the next bless/overload check to reclassify.
        state.overloadContextCache().clear();
        invalidateDependentRuntimeCaches();
    }

    /**
     * Invalidates the caches for method resolution and linearized class hierarchies.
     * This should be called whenever the class hierarchy or method definitions change.
     */
    public static void invalidateCache() {
        SHARED_SYMBOL_MUTATION_EPOCH.incrementAndGet();
        clearCurrentRuntimeCaches();
    }

    /**
     * Drop derived entries only for the bound runtime.
     * Used for runtime-local policy changes and uncached introspection reads.
     */
    public static void invalidateCurrentRuntimeCaches() {
        clearCurrentRuntimeCaches();
    }

    /**
     * Records a mutation to any package's {@code @ISA}.
     *
     * <p>Unlike ordinary arrays, {@code @ISA} changes Perl's method lookup
     * graph.  RuntimeArray marks package arrays ending in {@code ::ISA} and
     * calls this hook for every structural mutation.
     */
    public static void noteIsaMutation() {
        SHARED_ISA_MUTATION_EPOCH.incrementAndGet();
        SHARED_SYMBOL_MUTATION_EPOCH.incrementAndGet();
        clearCurrentRuntimeCaches();
    }

    public static long getIsaGeneration() {
        return currentState().isaGeneration();
    }

    /**
     * Drop method-resolution cache entries that depend on a given package sub's
     * <em>leaf</em> name (stash key {@code My::Pkg::foo} &rarr; {@code foo}), including
     * {@code \0noautoload} variants. Also clears {@link DestroyDispatch} bookkeeping
     * when the leaf is {@code DESTROY}.
     */
    public static void invalidateMethodLookupCachesForStashSubKey(String stashFqn) {
        if (stashFqn == null || stashFqn.isEmpty()) {
            return;
        }
        int chop = stashFqn.lastIndexOf("::");
        String leaf = chop < 0 ? stashFqn : stashFqn.substring(chop + 2);
        if (leaf.isEmpty()) {
            return;
        }
        String suffix = "::" + leaf;
        String suffixNoAutoload = suffix + "\0noautoload";
        SHARED_SYMBOL_MUTATION_EPOCH.incrementAndGet();
        MroRuntimeState state = currentState();
        state.methodCache().entrySet().removeIf(e -> {
            String k = e.getKey();
            return k.endsWith(suffixNoAutoload)
                    || k.endsWith(suffix)
                    || k.equals(leaf)
                    || k.equals(leaf + "\0noautoload");
        });
        if ("DESTROY".equals(leaf)) {
            DestroyDispatch.invalidateCache();
        }
    }

    /**
     * Retrieves a cached OverloadContext for the given blessing ID.
     *
     * @param blessId The blessing ID of the class.
     * @return The cached OverloadContext, or null if not found.
     */
    public static OverloadContext getCachedOverloadContext(int blessId) {
        return currentState().overloadContextCache().get(blessId);
    }

    /**
     * Caches an OverloadContext for the given blessing ID.
     *
     * @param blessId The blessing ID of the class.
     * @param context The OverloadContext to cache (can be null to indicate no overloading).
     */
    public static void cacheOverloadContext(int blessId, OverloadContext context) {
        currentState().overloadContextCache().put(blessId, context);
    }

    /**
     * Retrieves a cached method for the given normalized method name.
     *
     * @param normalizedMethodName The normalized name of the method.
     * @return The cached RuntimeScalar representing the method, or null if not found.
     */
    public static RuntimeScalar getCachedMethod(String normalizedMethodName) {
        return currentState().methodCache().get(normalizedMethodName);
    }

    /**
     * Caches a method for the given normalized method name.
     *
     * @param normalizedMethodName The normalized name of the method.
     * @param method               The RuntimeScalar representing the method to cache.
     */
    public static void cacheMethod(String normalizedMethodName, RuntimeScalar method) {
        currentState().methodCache().put(normalizedMethodName, method);
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

    /**
     * Resolve @ISA through Perl's optional main:: package prefix.  Code may
     * declare `package main::Foo::Bar` while objects and method calls report
     * the same class as `Foo::Bar`; both spellings address one Perl stash.
     */
    static RuntimeArray getIsaArrayForClass(String className) {
        for (String alias : packageLookupAliases(className)) {
            String key = alias + "::ISA";
            if (GlobalVariable.existsGlobalArray(key)) {
                return GlobalVariable.getGlobalArray(key);
            }
        }
        String canonicalClassName = GlobalVariable.resolveStashAlias(className);
        if (!canonicalClassName.equals(className)) {
            for (String alias : packageLookupAliases(canonicalClassName)) {
                String key = alias + "::ISA";
                if (GlobalVariable.existsGlobalArray(key)) {
                    return GlobalVariable.getGlobalArray(key);
                }
            }
        }
        // Method probes such as MissingClass->can(...) must not create an
        // @MissingClass::ISA slot (and therefore recreate the package stash).
        return new RuntimeArray();
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
        RuntimeArray isaArray = getIsaArrayForClass(className);
        List<String> parents = new ArrayList<>();
        for (RuntimeBase entity : visibleArrayElements(isaArray)) {
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
                // A package stash alias also aliases all child packages. Keep
                // the MRO graph canonical so a parent such as Clone::Inner
                // installed by `*Clone:: = *Outer::` resolves to
                // Outer::Inner for both isa() and method dispatch.
                parentName = GlobalVariable.resolveStashAlias(parentName);
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
     * Return the Perl-visible contents of an array.
     *
     * <p>Most package {@code @ISA} arrays are ordinary arrays, but Perl permits
     * them to be tied. Method lookup must honor the tie's {@code FETCHSIZE} and
     * {@code FETCH} methods; reading {@link RuntimeArray#elements} directly sees
     * only the empty backing list used by a tied array.</p>
     */
    static List<RuntimeScalar> visibleArrayElements(RuntimeArray array) {
        if (array.type != RuntimeArray.TIED_ARRAY) {
            return array.elements;
        }
        List<RuntimeScalar> visible = new ArrayList<>();
        int size = TieArray.tiedFetchSize(array).getInt();
        for (int i = 0; i < size; i++) {
            visible.add(array.get(i));
        }
        return visible;
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
        return findMethodInHierarchy(methodName, perlClassName, cacheKey, startFromIndex, true);
    }

    /**
     * Like {@link #findMethodInHierarchy(String, String, String, int)} but without the
     * AUTOLOAD fallback. Pass {@code checkAutoload=false} for callers that need
     * Perl's {@code gv_fetchmethod_autoload(..., FALSE)} semantics — for example,
     * Storable's STORABLE_freeze / STORABLE_thaw / STORABLE_attach hook lookup,
     * which must NOT promote an inherited AUTOLOAD into the hook (the AUTOLOAD
     * would be invoked with {@code $AUTOLOAD = "Pkg::STORABLE_freeze"}, which the
     * AUTOLOAD typically isn't expecting and just dies on).
     *
     * @param methodName     method name to find
     * @param perlClassName  starting class
     * @param cacheKey       cache key (null = default)
     * @param startFromIndex starting index in linearized hierarchy
     * @param checkAutoload  whether to fall back to AUTOLOAD when method is not directly defined
     * @return RuntimeScalar representing the found method, or null if not found
     */
    public static RuntimeScalar findMethodInHierarchy(String methodName, String perlClassName, String cacheKey, int startFromIndex, boolean checkAutoload) {
        MroRuntimeState state = currentState();
        Map<String, RuntimeScalar> methodCache = state.methodCache();
        if (TRACE_METHOD_RESOLUTION) {
            System.err.println("TRACE InheritanceResolver.findMethodInHierarchy:");
            System.err.println("  methodName: '" + methodName + "'");
            System.err.println("  perlClassName: '" + perlClassName + "'");
            System.err.println("  startFromIndex: " + startFromIndex);
            System.err.flush();
        }

        if (cacheKey == null) {
            // Normalize the method name for consistent caching
            cacheKey = normalizeMethodName(methodName, perlClassName);
        }
        // Use a separate cache slot for no-AUTOLOAD lookups so they don't
        // pollute (or get polluted by) normal lookups which DO promote AUTOLOAD.
        if (!checkAutoload) {
            cacheKey = cacheKey + "\0noautoload";
        }

        if (TRACE_METHOD_RESOLUTION) {
            System.err.println("  cacheKey: '" + cacheKey + "'");
            System.err.flush();
        }

        // Check if ISA changed for this class - if so, invalidate relevant caches
        if (hasIsaChanged(perlClassName, state)) {
            invalidateCacheForClass(perlClassName, state);
        }

        // Check the method cache - handles both found and not-found cases
        if (methodCache.containsKey(cacheKey)) {
            if (TRACE_METHOD_RESOLUTION) {
                System.err.println("  Found in cache: " + (methodCache.get(cacheKey) != null ? "YES" : "NULL"));
                System.err.flush();
            }
            return methodCache.get(cacheKey);
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
            for (String lookupClassName : packageLookupAliases(className)) {
                String effectiveClassName = GlobalVariable.resolveStashAlias(lookupClassName);
                String normalizedClassMethodName = normalizeMethodName(methodName, effectiveClassName);

                if (TRACE_METHOD_RESOLUTION) {
                    System.err.println("  Checking class: '" + lookupClassName + "'");
                    System.err.println("  Normalized name: '" + normalizedClassMethodName + "'");
                    System.err.println("  Exists: " + GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName));
                    System.err.flush();
                }

                if (GlobalVariable.existsGlobalCodeRef(normalizedClassMethodName)) {
                    RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(normalizedClassMethodName);
                    // A forward declaration is a real method-table entry even
                    // before it has a body. AutoSplit relies on this: the stub
                    // in a parent class must win method lookup so invoking it
                    // reaches that package's AUTOLOAD and corresponding .al
                    // file. Ignore only incidental undefined code slots that
                    // were never declared.
                    boolean isDeclaredForward = codeRef != null
                            && codeRef.value instanceof RuntimeCode code
                            && code.isDeclared;
                    if (!RuntimeCode.isCodeDefined(codeRef) && !isDeclaredForward) {
                        continue;
                    }
                    methodCache.put(cacheKey, codeRef);
                    if (TRACE_METHOD_RESOLUTION) {
                        System.err.println("  FOUND method!");
                        System.err.flush();
                    }
                    return codeRef;
                }
            }
        }

        // Second pass — method not found anywhere, check AUTOLOAD in class hierarchy.
        // This matches Perl semantics: AUTOLOAD is only tried after the full MRO
        // search (including UNIVERSAL) fails to find the method.
        if (state.autoloadEnabled() && checkAutoload && !methodName.startsWith("(")) {
            for (int i = startFromIndex; i < linearizedClasses.size(); i++) {
                String className = linearizedClasses.get(i);
                for (String lookupClassName : packageLookupAliases(className)) {
                    String effectiveClassName = GlobalVariable.resolveStashAlias(lookupClassName);
                    String autoloadName = (effectiveClassName.endsWith("::") ? effectiveClassName : effectiveClassName + "::") + "AUTOLOAD";
                    if (GlobalVariable.existsGlobalCodeRef(autoloadName)) {
                        RuntimeScalar autoload = GlobalVariable.getGlobalCodeRef(autoloadName);
                        if (RuntimeCode.isCodeDefined(autoload)) {
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
                            methodCache.put(cacheKey, autoload);
                            return autoload;
                        }
                    }
                }
            }
        }

        // Cache "method not found" as null. Late-installed or redefined package subs
        // invalidate matching entries via GlobalVariable.globalCodeRefs and in-place CV
        // updates on stash-backed RuntimeScalars (see globalCodeRefFqn).
        methodCache.put(cacheKey, null);
        return null;
    }

    private static List<String> packageLookupAliases(String className) {
        String normalized = NameNormalizer.normalizePackageName(className);
        List<String> aliases = new ArrayList<>(2);
        aliases.add(normalized);

        if (normalized.startsWith("main::") && normalized.length() > 6) {
            aliases.add(normalized.substring(6));
        } else if (normalized.startsWith("::") && normalized.length() > 2) {
            aliases.add("main::" + normalized.substring(2));
        } else {
            // Perl's root package prefix is optional at every nesting depth:
            // Foo::Bar and main::Foo::Bar name the same stash.  Dynamic code
            // generators commonly emit an explicit `package main::Foo::Bar`
            // and later invoke it as Foo::Bar->method.
            aliases.add("main::" + normalized);
        }

        return aliases;
    }

    private static String normalizeMethodName(String methodName, String className) {
        if (methodName.startsWith("::")) {
            return "main" + methodName;
        }
        if (methodName.contains("::")) {
            return methodName;
        }

        String normalizedClass = NameNormalizer.normalizePackageName(className);
        if (normalizedClass.endsWith("::")) {
            return normalizedClass + methodName;
        }
        return normalizedClass + "::" + methodName;
    }

    // MRO algorithm selection
    public enum MROAlgorithm {
        C3,
        DFS
    }
}

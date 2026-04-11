package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.jvm.ByteCodeSourceMapper;
import org.perlonjava.backend.jvm.CustomClassLoader;
import org.perlonjava.frontend.parser.ParserTables;
import org.perlonjava.runtime.mro.InheritanceResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * The GlobalVariable class manages global variables, arrays, hashes, and references
 * within the runtime environment. It provides methods to retrieve, set, and check
 * the existence of these global entities, initializing them as necessary.
 */
public class GlobalVariable {
    // ---- Static accessor methods delegating to PerlRuntime.current() ----
    // These replace the former static fields. External code should use these methods.

    public static Map<String, RuntimeScalar> getGlobalVariablesMap() {
        return PerlRuntime.current().globalVariables;
    }

    public static Map<String, RuntimeArray> getGlobalArraysMap() {
        return PerlRuntime.current().globalArrays;
    }

    public static Map<String, RuntimeHash> getGlobalHashesMap() {
        return PerlRuntime.current().globalHashes;
    }

    public static Map<String, Boolean> getPackageExistsCacheMap() {
        return PerlRuntime.current().packageExistsCache;
    }

    public static Map<String, Boolean> getIsSubsMap() {
        return PerlRuntime.current().isSubs;
    }

    public static Map<String, RuntimeScalar> getGlobalCodeRefsMap() {
        return PerlRuntime.current().globalCodeRefs;
    }

    public static Map<String, RuntimeGlob> getGlobalIORefsMap() {
        return PerlRuntime.current().globalIORefs;
    }

    public static Map<String, RuntimeFormat> getGlobalFormatRefsMap() {
        return PerlRuntime.current().globalFormatRefs;
    }

    public static Map<String, RuntimeScalar> getPinnedCodeRefsMap() {
        return PerlRuntime.current().pinnedCodeRefs;
    }

    public static Map<String, String> getStashAliasesMap() {
        return PerlRuntime.current().stashAliases;
    }

    public static Map<String, String> getGlobAliasesMap() {
        return PerlRuntime.current().globAliases;
    }

    public static Map<String, Boolean> getGlobalGlobsMap() {
        return PerlRuntime.current().globalGlobs;
    }

    public static CustomClassLoader getGlobalClassLoader() {
        return PerlRuntime.current().globalClassLoader;
    }

    public static void setGlobalClassLoader(CustomClassLoader loader) {
        PerlRuntime.current().globalClassLoader = loader;
    }

    // Regular expression for regex variables like $main::1 (compile-time constant)
    static Pattern regexVariablePattern = Pattern.compile("^main::(\\d+)$");

    /**
     * Marks a global variable as explicitly declared (e.g., via use vars, Exporter import).
     */
    public static void declareGlobalVariable(String key) {
        PerlRuntime.current().declaredGlobalVariables.add(key);
    }

    /**
     * Marks a global array as explicitly declared.
     */
    public static void declareGlobalArray(String key) {
        PerlRuntime.current().declaredGlobalArrays.add(key);
    }

    /**
     * Marks a global hash as explicitly declared.
     */
    public static void declareGlobalHash(String key) {
        PerlRuntime.current().declaredGlobalHashes.add(key);
    }

    /**
     * Checks if a global variable was explicitly declared (not just auto-vivified).
     */
    public static boolean isDeclaredGlobalVariable(String key) {
        return PerlRuntime.current().declaredGlobalVariables.contains(key)
                || key.endsWith("::a") || key.endsWith("::b");
    }

    /**
     * Checks if a global array was explicitly declared.
     */
    public static boolean isDeclaredGlobalArray(String key) {
        return PerlRuntime.current().declaredGlobalArrays.contains(key);
    }

    /**
     * Checks if a global hash was explicitly declared.
     */
    public static boolean isDeclaredGlobalHash(String key) {
        return PerlRuntime.current().declaredGlobalHashes.contains(key);
    }

    /**
     * Resets all global variables, arrays, hashes, code references, and IO references.
     * Also destroys and recreates the global class loader to allow GC of old classes.
     */
    public static void resetAllGlobals() {
        PerlRuntime rt = PerlRuntime.current();
        // Clear all global state
        rt.globalVariables.clear();
        rt.globalArrays.clear();
        rt.globalHashes.clear();
        rt.globalCodeRefs.clear();
        rt.pinnedCodeRefs.clear();
        rt.globalIORefs.clear();
        rt.globalFormatRefs.clear();
        rt.globalGlobs.clear();
        rt.isSubs.clear();
        rt.stashAliases.clear();
        rt.globAliases.clear();
        rt.declaredGlobalVariables.clear();
        rt.declaredGlobalArrays.clear();
        rt.declaredGlobalHashes.clear();
        clearPackageCache();

        RuntimeCode.clearCaches();

        // Clear special blocks (INIT, END, CHECK, UNITCHECK) to prevent stale code references.
        // When the classloader is replaced, old INIT blocks may reference evalTags that no longer
        // exist in the cleared evalContext, causing "ctx is null" errors.
        SpecialBlock.getInitBlocks().elements.clear();
        SpecialBlock.getEndBlocks().elements.clear();
        SpecialBlock.getCheckBlocks().elements.clear();

        // Method resolution caches can grow across test scripts.
        InheritanceResolver.invalidateCache();

        // Debug/source mapping cache grows with every compilation; clear it between test scripts.
        ByteCodeSourceMapper.resetAll();

        // Reset Net::SSLeay static state (handles, providers, etc.)
        try {
            org.perlonjava.runtime.perlmodule.NetSSLeay.resetState();
        } catch (NoClassDefFoundError e) {
            // NetSSLeay not loaded; ignore
        }

        // Reset lib module static state (ORIG_INC)
        org.perlonjava.runtime.perlmodule.Lib.resetState();

        // Destroy the old classloader and create a new one
        // This allows the old generated classes to be garbage collected
        rt.globalClassLoader = new CustomClassLoader(GlobalVariable.class.getClassLoader());
    }

    public static void setStashAlias(String dstNamespace, String srcNamespace) {
        String dst = dstNamespace.endsWith("::") ? dstNamespace : dstNamespace + "::";
        String src = srcNamespace.endsWith("::") ? srcNamespace : srcNamespace + "::";
        PerlRuntime.current().stashAliases.put(dst, src);
    }

    public static void clearStashAlias(String namespace) {
        String key = namespace.endsWith("::") ? namespace : namespace + "::";
        PerlRuntime.current().stashAliases.remove(key);
    }

    public static String resolveStashAlias(String namespace) {
        PerlRuntime rt = PerlRuntime.current();
        String key = namespace.endsWith("::") ? namespace : namespace + "::";
        String aliased = rt.stashAliases.get(key);
        if (aliased == null) {
            return namespace;
        }
        // Preserve trailing :: if caller passed it.
        if (!namespace.endsWith("::") && aliased.endsWith("::")) {
            return aliased.substring(0, aliased.length() - 2);
        }
        return aliased;
    }

    /**
     * Sets a glob alias. After `*a = *b`, calling setGlobAlias("a", "b") makes
     * all slot assignments to "a" also affect "b" and vice versa.
     */
    public static void setGlobAlias(String fromGlob, String toGlob) {
        // Find the canonical name for toGlob (in case it's already an alias)
        String canonical = resolveGlobAlias(toGlob);
        PerlRuntime rt = PerlRuntime.current();
        // Don't create self-loops
        if (!fromGlob.equals(canonical)) {
            rt.globAliases.put(fromGlob, canonical);
        }
        // Also ensure toGlob points to the canonical name (unless it would create a self-loop)
        if (!toGlob.equals(canonical) && !toGlob.equals(fromGlob)) {
            rt.globAliases.put(toGlob, canonical);
        }
    }

    /**
     * Resolves a glob name to its canonical name.
     * If the glob is aliased, returns the target name; otherwise returns the input.
     */
    public static String resolveGlobAlias(String globName) {
        String aliased = PerlRuntime.current().globAliases.get(globName);
        if (aliased != null && !aliased.equals(globName)) {
            // Follow the chain in case of multiple aliases
            return resolveGlobAlias(aliased);
        }
        return globName;
    }

    /**
     * Gets all glob names that are aliased to the same canonical name.
     * This is used when assigning to a glob slot - we need to update all aliases.
     */
    public static java.util.List<String> getGlobAliasGroup(String globName) {
        String canonical = resolveGlobAlias(globName);
        java.util.List<String> group = new java.util.ArrayList<>();
        group.add(canonical);
        for (Map.Entry<String, String> entry : PerlRuntime.current().globAliases.entrySet()) {
            if (resolveGlobAlias(entry.getKey()).equals(canonical) && !group.contains(entry.getKey())) {
                group.add(entry.getKey());
            }
        }
        return group;
    }

    /**
     * Retrieves a global variable by its key, initializing it if necessary.
     * If the key matches a regex capture variable pattern, it initializes a special variable.
     *
     * @param key The key of the global variable.
     * @return The RuntimeScalar representing the global variable.
     */
    public static RuntimeScalar getGlobalVariable(String key) {
        PerlRuntime rt = PerlRuntime.current();
        RuntimeScalar var = rt.globalVariables.get(key);
        if (var == null) {
            // Need to initialize global variable
            Matcher matcher = regexVariablePattern.matcher(key);
            if (matcher.matches() && !key.equals("main::0")) {
                // Regex capture variable like $1
                // Extract the numeric capture group as a string
                String capturedNumber = matcher.group(1);
                // Convert the capture group to an integer
                int position = Integer.parseInt(capturedNumber);
                // Initialize the regex capture variable
                var = new ScalarSpecialVariable(ScalarSpecialVariable.Id.CAPTURE, position);
            } else {
                // Normal "non-magic" global variable
                var = new RuntimeScalar();
            }
            rt.globalVariables.put(key, var);
        }
        return var;
    }

    public static RuntimeScalar aliasGlobalVariable(String key, String to) {
        PerlRuntime rt = PerlRuntime.current();
        RuntimeScalar var = rt.globalVariables.get(to);
        rt.globalVariables.put(key, var);
        return var;
    }

    public static void aliasGlobalVariable(String key, RuntimeScalar var) {
        PerlRuntime.current().globalVariables.put(key, var);
    }

    /**
     * Sets the value of a global variable.
     *
     * @param key   The key of the global variable.
     * @param value The value to set.
     */
    public static void setGlobalVariable(String key, String value) {
        getGlobalVariable(key).set(value);
    }

    /**
     * Checks if a global variable exists.
     *
     * @param key The key of the global variable.
     * @return True if the global variable exists, false otherwise.
     */
    public static boolean existsGlobalVariable(String key) {
        return PerlRuntime.current().globalVariables.containsKey(key)
                || key.endsWith("::a")  // $a, $b always exist
                || key.endsWith("::b");
    }

    /**
     * Checks if a global variable exists AND has a defined value, without auto-creating.
     *
     * @param key The key of the global variable.
     * @return True if the variable exists and is defined, false otherwise.
     */
    public static boolean isGlobalVariableDefined(String key) {
        RuntimeScalar var = PerlRuntime.current().globalVariables.get(key);
        return var != null && var.getDefinedBoolean();
    }

    /**
     * Removes a global variable by its key.
     *
     * @param key The key of the global variable.
     * @return The removed RuntimeScalar, or null if it did not exist.
     */
    public static RuntimeScalar removeGlobalVariable(String key) {
        return PerlRuntime.current().globalVariables.remove(key);
    }

    /**
     * Retrieves a global array by its key, initializing it if necessary.
     *
     * @param key The key of the global array.
     * @return The RuntimeArray representing the global array.
     */
    public static RuntimeArray getGlobalArray(String key) {
        PerlRuntime rt = PerlRuntime.current();
        RuntimeArray var = rt.globalArrays.get(key);
        if (var == null) {
            var = new RuntimeArray();
            rt.globalArrays.put(key, var);
        }
        return var;
    }

    /**
     * Checks if a global array exists.
     *
     * @param key The key of the global array.
     * @return True if the global array exists, false otherwise.
     */
    public static boolean existsGlobalArray(String key) {
        return PerlRuntime.current().globalArrays.containsKey(key);
    }

    /**
     * Removes a global array by its key.
     *
     * @param key The key of the global array.
     * @return The removed RuntimeArray, or null if it did not exist.
     */
    public static RuntimeArray removeGlobalArray(String key) {
        return PerlRuntime.current().globalArrays.remove(key);
    }

    /**
     * Retrieves a global hash by its key, initializing it if necessary.
     *
     * @param key The key of the global hash.
     * @return The RuntimeHash representing the global hash.
     */
    public static RuntimeHash getGlobalHash(String key) {
        PerlRuntime rt = PerlRuntime.current();
        RuntimeHash var = rt.globalHashes.get(key);
        if (var == null) {
            // Check if this is a package stash (ends with ::)
            if (key.endsWith("::")) {
                var = new RuntimeStash(key);
            } else {
                var = new RuntimeHash();
            }
            rt.globalHashes.put(key, var);
        }
        return var;
    }

    /**
     * Checks if a global hash exists.
     *
     * @param key The key of the global hash.
     * @return True if the global hash exists, false otherwise.
     */
    public static boolean existsGlobalHash(String key) {
        return PerlRuntime.current().globalHashes.containsKey(key);
    }

    /**
     * Removes a global hash by its key.
     *
     * @param key The key of the global hash.
     * @return The removed RuntimeHash, or null if it did not exist.
     */
    public static RuntimeHash removeGlobalHash(String key) {
        return PerlRuntime.current().globalHashes.remove(key);
    }

    /**
     * Retrieves a global code reference by its key, initializing it if necessary.
     * The returned RuntimeScalar is also pinned, meaning it will survive stash deletion.
     * This matches Perl's behavior where compiled bytecode holds direct references to CVs.
     *
     * @param key The key of the global code reference.
     * @return The RuntimeScalar representing the global code reference.
     */
    public static RuntimeScalar getGlobalCodeRef(String key) {
        if (key == null) {
            return new RuntimeScalar();
        }
        PerlRuntime rt = PerlRuntime.current();
        // First check if we have a pinned reference that survives stash deletion
        RuntimeScalar pinned = rt.pinnedCodeRefs.get(key);
        if (pinned != null) {
            // Return the pinned ref so compiled code keeps working, but do NOT
            // re-add to rt.globalCodeRefs. If it was deleted from the stash (e.g., by
            // namespace::clean), that deletion should be respected for method
            // resolution via can() and the inheritance hierarchy.
            return pinned;
        }

        RuntimeScalar var = rt.globalCodeRefs.get(key);
        if (var == null) {
            var = new RuntimeScalar();
            var.type = RuntimeScalarType.CODE;  // value is null
            RuntimeCode runtimeCode = new RuntimeCode((String) null, null);

            // Parse the key to extract package and subroutine names
            // key format is typically "Package::SubroutineName"
            int lastColonIndex = key.lastIndexOf("::");
            if (lastColonIndex > 0) {
                runtimeCode.packageName = key.substring(0, lastColonIndex);
                runtimeCode.subName = key.substring(lastColonIndex + 2);
            } else {
                runtimeCode.packageName = "main";
                runtimeCode.subName = key;
            }

            // Note: We don't set isSymbolicReference here by default
            // It will be set specifically for \&{string} patterns in createCodeReference

            var.value = runtimeCode;
            rt.globalCodeRefs.put(key, var);
        }

        // Pin the RuntimeScalar so it survives stash deletion
        rt.pinnedCodeRefs.put(key, var);

        return var;
    }

    /**
     * Retrieves a global code reference for the purpose of DEFINING code.
     * Unlike getGlobalCodeRef(), this also ensures the entry is visible in
     * PerlRuntime.current().globalCodeRefs for method resolution via can() and the inheritance hierarchy.
     * Use this when assigning code to a glob (e.g., *Foo::bar = sub { ... }).
     *
     * @param key The key of the global code reference.
     * @return The RuntimeScalar representing the global code reference.
     */
    public static RuntimeScalar defineGlobalCodeRef(String key) {
        RuntimeScalar ref = getGlobalCodeRef(key);
        PerlRuntime rt = PerlRuntime.current();
        // Ensure it's in rt.globalCodeRefs so method resolution finds it
        if (!rt.globalCodeRefs.containsKey(key)) {
            rt.globalCodeRefs.put(key, ref);
        }
        return ref;
    }

    /**
     * Checks if a global code reference exists.
     *
     * @param key The key of the global code reference.
     * @return True if the global code reference exists, false otherwise.
     */
    public static boolean existsGlobalCodeRef(String key) {
        return PerlRuntime.current().globalCodeRefs.containsKey(key);
    }

    /**
     * Replaces the pinned code ref for a glob during local scope.
     * Called by RuntimeGlob.dynamicSaveState() so that assignments during the
     * local scope go to the new empty code object instead of the saved one.
     *
     * @param key     The glob name key.
     * @param codeRef The new RuntimeScalar to pin (typically a new empty one).
     */
    static void replacePinnedCodeRef(String key, RuntimeScalar codeRef) {
        Map<String, RuntimeScalar> pinned = PerlRuntime.current().pinnedCodeRefs;
        if (pinned.containsKey(key)) {
            pinned.put(key, codeRef);
        }
    }

    /**
     * Checks if a global code reference exists AND is defined (has a real subroutine),
     * without auto-creating an entry.
     *
     * @param key The key of the global code reference.
     * @return True if the code reference exists and is defined, false otherwise.
     */
    public static boolean isGlobalCodeRefDefined(String key) {
        RuntimeScalar var = PerlRuntime.current().globalCodeRefs.get(key);
        if (var != null && var.type == RuntimeScalarType.CODE && var.value instanceof RuntimeCode runtimeCode) {
            return runtimeCode.defined();
        }
        return false;
    }

    public static RuntimeScalar existsGlobalCodeRefAsScalar(String key) {
        RuntimeScalar var = PerlRuntime.current().globalCodeRefs.get(key);
        if (var != null && var.type == RuntimeScalarType.CODE && var.value instanceof RuntimeCode runtimeCode) {
            // Use the RuntimeCode.defined() method to check if the subroutine actually exists
            // This checks methodHandle, constantValue, and compilerSupplier
            return runtimeCode.defined() ? scalarTrue : scalarFalse;
        }
        return scalarFalse;
    }

    public static RuntimeScalar existsGlobalCodeRefAsScalar(RuntimeScalar key) {
        // Handle GLOB type: extract CODE slot from the glob
        if (key.type == RuntimeScalarType.GLOB && key.value instanceof RuntimeGlob glob) {
            return existsGlobalCodeRefAsScalar(glob.globName);
        }
        // Handle RuntimeCode objects by extracting the subroutine name
        if (key.type == RuntimeScalarType.CODE && key.value instanceof RuntimeCode runtimeCode) {
            // Use the RuntimeCode.defined() method to check if the subroutine actually exists
            return runtimeCode.defined() ? scalarTrue : scalarFalse;
        }
        return existsGlobalCodeRefAsScalar(key.toString());
    }

    public static RuntimeScalar existsGlobalCodeRefAsScalar(RuntimeScalar key, String packageName) {
        // Use proper package name resolution like createCodeReference
        String name = NameNormalizer.normalizeVariableName(key.toString(), packageName);
        return existsGlobalCodeRefAsScalar(name);
    }

    public static RuntimeScalar definedGlobalCodeRefAsScalar(String key) {
        // For defined(&{string}) patterns, check actual subroutine existence to match standard Perl
        // Standard Perl: defined(&{existing}) = true, defined(&{nonexistent}) = false

        // Check if it's a built-in operator
        // Built-ins are ONLY accessible via CORE:: prefix
        int lastColonIndex = key.lastIndexOf("::");

        if (lastColonIndex > 0) {
            String packageName = key.substring(0, lastColonIndex);
            String operatorName = key.substring(lastColonIndex + 2);
            // CORE:: prefix means it's definitely referring to a built-in
            if (packageName.equals("CORE") && ParserTables.CORE_PROTOTYPES.containsKey(operatorName)) {
                return scalarTrue;
            }
        }

        RuntimeScalar var = PerlRuntime.current().globalCodeRefs.get(key);
        if (var != null && var.type == RuntimeScalarType.CODE && var.value instanceof RuntimeCode runtimeCode) {
            return runtimeCode.defined() ? scalarTrue : scalarFalse;
        }
        return scalarFalse;
    }

    public static RuntimeScalar definedGlobalCodeRefAsScalar(RuntimeScalar key) {
        // Handle GLOB type: extract CODE slot from the glob
        if (key.type == RuntimeScalarType.GLOB && key.value instanceof RuntimeGlob glob) {
            return definedGlobalCodeRefAsScalar(glob.globName);
        }
        // Handle CODE type: check the RuntimeCode object directly.
        // This works for both named subs and anonymous/lexical coderefs.
        // After `undef &x`, the RuntimeCode is replaced with an empty one where defined() returns false.
        if (key.type == RuntimeScalarType.CODE && key.value instanceof RuntimeCode runtimeCode) {
            return runtimeCode.defined() ? scalarTrue : scalarFalse;
        }
        return definedGlobalCodeRefAsScalar(key.toString());
    }

    public static RuntimeScalar definedGlobalCodeRefAsScalar(RuntimeScalar key, String packageName) {
        // Use proper package name resolution like createCodeReference
        String name = NameNormalizer.normalizeVariableName(key.toString(), packageName);

        // Built-ins are ONLY accessible via CORE:: prefix, not from main:: or other packages
        // So just delegate to the main method which checks for CORE:: prefix
        return definedGlobalCodeRefAsScalar(name);
    }


    public static RuntimeScalar deleteGlobalCodeRefAsScalar(String key) {
        RuntimeScalar deleted = PerlRuntime.current().globalCodeRefs.remove(key);
        return deleted != null ? deleted : scalarFalse;
    }

    public static RuntimeScalar deleteGlobalCodeRefAsScalar(RuntimeScalar key) {
        // Handle GLOB type: extract CODE slot from the glob
        if (key.type == RuntimeScalarType.GLOB && key.value instanceof RuntimeGlob glob) {
            return deleteGlobalCodeRefAsScalar(glob.globName);
        }
        // Handle RuntimeCode objects by extracting the subroutine name
        if (key.type == RuntimeScalarType.CODE && key.value instanceof RuntimeCode runtimeCode) {
            String fullName = runtimeCode.packageName + "::" + runtimeCode.subName;
            return deleteGlobalCodeRefAsScalar(fullName);
        }
        return deleteGlobalCodeRefAsScalar(key.toString());
    }

    public static RuntimeScalar deleteGlobalCodeRefAsScalar(RuntimeScalar key, String packageName) {
        // Use proper package name resolution like createCodeReference
        String name = NameNormalizer.normalizeVariableName(key.toString(), packageName);
        return deleteGlobalCodeRefAsScalar(name);
    }

    /**
     * Clears pinned code references for all subroutines in a given namespace.
     * This prevents deleted subs from being resurrected by getGlobalCodeRef()
     * after stash namespace deletion (e.g., delete $::{"Foo::"}).
     *
     * @param prefix The namespace prefix (e.g., "Foo::") to clear.
     */
    public static void clearPinnedCodeRefsForNamespace(String prefix) {
        PerlRuntime.current().pinnedCodeRefs.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Clears the package existence cache.
     * Should be called when new packages are loaded or code refs are modified.
     */
    public static void clearPackageCache() {
        PerlRuntime.current().packageExistsCache.clear();
    }

    /**
     * Checks if a Perl package is loaded by scanning for any methods in its namespace
     *
     * @param className The name of the package/class to check
     * @return true if any methods exist in the class namespace
     */
    public static boolean isPackageLoaded(String className) {
        PerlRuntime rt = PerlRuntime.current();
        // Check cache first
        Boolean cached = rt.packageExistsCache.get(className);
        if (cached != null) {
            return cached;
        }

        // Ensure we have the :: suffix for the prefix check
        final String prefix = className.endsWith("::") ? className : className + "::";

        // Check if any code references exist directly in this class (not in sub-packages).
        // A key like "Foo::Bar::baz" belongs to package "Foo::Bar", not "Foo".
        // After stripping the prefix, the remaining part must NOT contain "::"
        // to be a direct member of this package.
        boolean exists = rt.globalCodeRefs.keySet().stream()
                .anyMatch(key -> key.startsWith(prefix) && !key.substring(prefix.length()).contains("::"));

        // Cache the result
        rt.packageExistsCache.put(className, exists);
        return exists;
    }

    /**
     * Resolves a fully-qualified variable name through stash hash redirections.
     * <p>
     * When {@code *PKG:: = \%OtherPkg::} is executed, accesses to {@code PKG::name}
     * should resolve to {@code OtherPkg::name}. This method checks if the package
     * portion of the name has been redirected to another package's RuntimeStash, and
     * if so, rewrites the name accordingly.
     * <p>
     * This is critical for the {@code local *__ANON__:: = $namespace} pattern used
     * by Package::Stash::PP, where glob vivification through the aliased stash must
     * create entries visible in the target package's symbol table.
     *
     * @param fullName The fully-qualified variable name (e.g., "__ANON__::foo").
     * @return The resolved name (e.g., "Foo::foo" if __ANON__:: was redirected to Foo::),
     *         or the original name if no redirection is active.
     */
    public static String resolveStashHashRedirect(String fullName) {
        int lastDoubleColon = fullName.lastIndexOf("::");
        if (lastDoubleColon >= 0) {
            String pkgPart = fullName.substring(0, lastDoubleColon + 2);
            RuntimeHash stashHash = PerlRuntime.current().globalHashes.get(pkgPart);
            if (stashHash instanceof RuntimeStash stash && !stash.namespace.equals(pkgPart)) {
                String shortName = fullName.substring(lastDoubleColon + 2);
                return stash.namespace + shortName;
            }
        }
        return fullName;
    }

    /**
     * Retrieves a global IO reference by its key, initializing it if necessary.
     * <p>
     * Resolves stash hash redirections so that glob vivification through an aliased
     * stash (e.g., after {@code *__ANON__:: = \%Foo::}) creates entries in the correct
     * package's symbol table.
     *
     * @param key The key of the global IO reference.
     * @return The RuntimeScalar representing the global IO reference.
     */
    public static RuntimeGlob getGlobalIO(String key) {
        String resolvedKey = resolveStashHashRedirect(key);
        PerlRuntime rt = PerlRuntime.current();
        RuntimeGlob glob = rt.globalIORefs.get(resolvedKey);
        if (glob == null) {
            glob = new RuntimeGlob(resolvedKey);
            rt.globalIORefs.put(resolvedKey, glob);
        }
        return glob;
    }

    /**
     * Retrieves a detached copy of a global IO reference, wrapped in a RuntimeScalar.
     *
     * <p>This method is crucial for the {@code do { local *FH; *FH }} pattern used to create
     * anonymous filehandles. By creating the detached copy immediately when the glob is
     * evaluated, we capture the current IO slot BEFORE the local scope ends and restores
     * the original IO.
     *
     * <p>The detached copy has the same globName (for stringification) but its own IO
     * reference that is independent of the global glob after the copy is made.
     *
     * @param key The key of the global IO reference.
     * @return A RuntimeScalar containing a detached copy of the glob.
     */
    public static RuntimeScalar getGlobalIOCopy(String key) {
        return new RuntimeScalar(getGlobalIO(key));
    }

    /**
     * Checks if a global IO reference exists.
     *
     * @param key The key of the global IO reference.
     * @return True if the global IO reference exists, false otherwise.
     */
    public static boolean existsGlobalIO(String key) {
        return PerlRuntime.current().globalIORefs.containsKey(key);
    }

    /**
     * Checks if a global IO reference exists AND has an actual IO handle (not just an empty glob),
     * without auto-creating an entry.
     *
     * @param key The key of the global IO reference.
     * @return True if the IO reference exists and has a real IO handle, false otherwise.
     */
    public static boolean isGlobalIODefined(String key) {
        RuntimeGlob glob = PerlRuntime.current().globalIORefs.get(key);
        if (glob != null && glob.type == RuntimeScalarType.GLOB) {
            // Check the IO slot, not glob.value - IO is stored in glob.IO
            return glob.IO != null && glob.IO.getDefinedBoolean();
        }
        return false;
    }

    /**
     * Returns the existing global IO glob for the given key, or null if not present.
     * Unlike {@link #getGlobalIO(String)}, this method does NOT auto-create entries.
     * Used by closeIOOnDrop() to check if a glob is still in the stash.
     *
     * @param key The key of the global IO reference.
     * @return The RuntimeGlob if it exists in the stash, null otherwise.
     */
    public static RuntimeGlob getExistingGlobalIO(String key) {
        return PerlRuntime.current().globalIORefs.get(key);
    }

    /**
     * Checks if a glob is defined (has any slot initialized).
     * Used for `defined *$var` which should not throw strict refs and not auto-vivify.
     *
     * @param scalar      The scalar containing the glob name or glob reference.
     * @param packageName The current package name for resolving unqualified names.
     * @return RuntimeScalar true if the glob is defined, false otherwise.
     */
    public static RuntimeScalar definedGlob(RuntimeScalar scalar, String packageName) {
        // Handle glob references directly
        if (scalar.type == RuntimeScalarType.GLOB || scalar.type == RuntimeScalarType.GLOBREFERENCE) {
            if (scalar.value instanceof RuntimeGlob glob) {
                return glob.defined();
            }
            return RuntimeScalarCache.scalarFalse;
        }

        // For strings, check if any slot exists without auto-vivifying
        String varName = NameNormalizer.normalizeVariableName(scalar.toString(), packageName);
        
        // Numeric capture variables (like $1, $42, $12345) are always defined in Perl
        // Use the same pattern as getGlobalVariable for consistency
        if (regexVariablePattern.matcher(varName).matches() && !varName.equals("main::0")) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        PerlRuntime rt = PerlRuntime.current();

        // Check if glob was explicitly assigned
        if (rt.globalGlobs.getOrDefault(varName, false)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check scalar slot - slot existence makes glob defined (not value definedness)
        // In Perl, `defined *FOO` is true if $FOO exists, even if $FOO is undef
        if (rt.globalVariables.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check array slot - exists = defined (even if empty)
        if (rt.globalArrays.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check hash slot - exists = defined (even if empty)
        if (rt.globalHashes.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check code slot - slot existence makes glob defined
        if (rt.globalCodeRefs.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check IO slot (via rt.globalIORefs)
        if (rt.globalIORefs.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check format slot
        if (rt.globalFormatRefs.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        return RuntimeScalarCache.scalarFalse;
    }

    /**
     * Retrieves a global format reference by its key, initializing it if necessary.
     *
     * @param key The key of the global format reference.
     * @return The RuntimeFormat representing the global format reference.
     */
    public static RuntimeFormat getGlobalFormatRef(String key) {
        PerlRuntime rt = PerlRuntime.current();
        RuntimeFormat format = rt.globalFormatRefs.get(key);
        if (format == null) {
            format = new RuntimeFormat(key);
            rt.globalFormatRefs.put(key, format);
        }
        return format;
    }

    /**
     * Sets a global format reference to share the same format object.
     * Used for typeglob format assignments like *COPIED = *ORIGINAL.
     *
     * @param key    The key of the global format reference.
     * @param format The RuntimeFormat object to set.
     */
    public static void setGlobalFormatRef(String key, RuntimeFormat format) {
        PerlRuntime.current().globalFormatRefs.put(key, format);
    }

    /**
     * Checks if a global format reference exists.
     *
     * @param key The key of the global format reference.
     * @return True if the global format reference exists, false otherwise.
     */
    public static boolean existsGlobalFormat(String key) {
        return PerlRuntime.current().globalFormatRefs.containsKey(key);
    }

    public static RuntimeScalar existsGlobalFormatAsScalar(String key) {
        return PerlRuntime.current().globalFormatRefs.containsKey(key) ? scalarTrue : scalarFalse;
    }

    public static RuntimeScalar existsGlobalFormatAsScalar(RuntimeScalar key) {
        return existsGlobalFormatAsScalar(key.toString());
    }

    /**
     * Checks if a global format reference exists AND is defined, without auto-creating an entry.
     *
     * @param key The key of the global format reference.
     * @return True if the format reference exists and is defined, false otherwise.
     */
    public static boolean isGlobalFormatDefined(String key) {
        RuntimeFormat format = PerlRuntime.current().globalFormatRefs.get(key);
        return format != null && format.isFormatDefined();
    }

    public static RuntimeScalar definedGlobalFormatAsScalar(String key) {
        PerlRuntime rt = PerlRuntime.current();
        return rt.globalFormatRefs.containsKey(key) ?
                (rt.globalFormatRefs.get(key).isFormatDefined() ? scalarTrue : scalarFalse) : scalarFalse;
    }

    public static RuntimeScalar definedGlobalFormatAsScalar(RuntimeScalar key) {
        return definedGlobalFormatAsScalar(key.toString());
    }

    /**
     * Resets all global variables whose names start with any of the specified characters
     *
     * @param resetChars     Set of characters to match variable names against
     * @param currentPackage The current package name with "::" suffix
     */
    public static void resetGlobalVariables(Set<Character> resetChars, String currentPackage) {
        PerlRuntime rt = PerlRuntime.current();
        // Reset scalar variables
        for (Map.Entry<String, RuntimeScalar> entry : rt.globalVariables.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Reset to undef instead of removing to maintain reference integrity
                entry.getValue().set(RuntimeScalar.undef());
            }
        }

        // Reset array variables
        for (Map.Entry<String, RuntimeArray> entry : rt.globalArrays.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Clear the array
                entry.getValue().elements.clear();
            }
        }

        // Reset hash variables
        for (Map.Entry<String, RuntimeHash> entry : rt.globalHashes.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Clear the hash
                entry.getValue().elements.clear();
            }
        }

        // Note: We don't reset code references or IO references as per Perl behavior
    }

    /**
     * Determines if a variable should be reset based on its name and the reset characters
     *
     * @param fullKey       The full variable key (e.g. "main::myvar")
     * @param packagePrefix The current package prefix (e.g. "main::")
     * @param resetChars    The set of characters to match against
     * @return true if the variable should be reset
     */
    private static boolean shouldResetVariable(String fullKey, String packagePrefix, Set<Character> resetChars) {
        if (!fullKey.startsWith(packagePrefix)) {
            return false;
        }

        // Extract the variable name without the package prefix
        String varName = fullKey.substring(packagePrefix.length());

        // Skip special variables like $_, @ARGV, %ENV, etc.
        if (varName.length() == 1 && "_!@$".indexOf(varName.charAt(0)) >= 0) {
            return false;
        }

        // Don't reset important arrays and hashes
        if (varName.equals("ARGV") || varName.equals("INC") || varName.equals("ENV")) {
            return false;
        }

        // Check if the first character of the variable name matches any reset character
        if (varName.length() > 0) {
            return resetChars.contains(varName.charAt(0));
        }

        return false;
    }

    /**
     * Gets all ISA arrays for reverse ISA cache building.
     * Returns all global arrays whose key ends with "::ISA".
     */
    public static Map<String, RuntimeArray> getAllIsaArrays() {
        Map<String, RuntimeArray> result = new HashMap<>();
        for (Map.Entry<String, RuntimeArray> entry : PerlRuntime.current().globalArrays.entrySet()) {
            if (entry.getKey().endsWith("::ISA")) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}

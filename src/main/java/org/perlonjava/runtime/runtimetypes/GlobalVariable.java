package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.jvm.ByteCodeSourceMapper;
import org.perlonjava.backend.jvm.CustomClassLoader;
import org.perlonjava.frontend.parser.ParserTables;
import org.perlonjava.runtime.mro.InheritanceResolver;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.function.Function;
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
    // Global variables and subroutines
    public static final Map<String, RuntimeScalar> globalVariables =
            new CurrentRuntimeMap<>(state -> state.scalarValues());
    public static final Map<String, RuntimeArray> globalArrays =
            new CurrentRuntimeMap<>(state -> state.arrayValues());
    public static final Map<String, RuntimeHash> globalHashes =
            new CurrentRuntimeMap<>(state -> state.hashValues());
    // Cache for package existence checks
    public static final Map<String, Boolean> packageExistsCache =
            new CurrentRuntimePlainMap<>(state -> state.packageExistsCache());
    // isSubs: Tracks subroutines declared via 'use subs' pragma (e.g., use subs 'hex')
    // Maps fully-qualified names (package::subname) to indicate they should be called
    // as user-defined subroutines instead of built-in operators
    public static final Map<String, Boolean> isSubs =
            new CurrentRuntimePlainMap<>(state -> state.importedSubs());
    public static final Map<String, RuntimeScalar> globalCodeRefs = new GlobalCodeRefMap();
    static final Map<String, RuntimeGlob> globalIORefs =
            new StashSlotMap<>(state -> state.ioSlots());
    static final Map<String, RuntimeFormat> globalFormatRefs =
            new StashSlotMap<>(state -> state.formatSlots());

    // Pinned code references: RuntimeScalars that were accessed at compile time
    // and should survive stash deletion. This matches Perl's behavior where
    // compiled bytecode holds direct references to CVs that survive stash deletion.
    // Stash aliasing: `*{Dst::} = *{Src::}` effectively makes Dst:: symbol table
    // behave like Src:: for method lookup and stash operations.
    // We keep this separate from globalCodeRefs/globalVariables so existing references
    // to Dst:: symbols can still point to their original objects.
    static final Map<String, String> stashAliases =
            new CurrentRuntimePlainMap<>(state -> state.stashAliases());

    // Glob aliasing: `*a = *b` makes a and b share the same glob.
    // Maps glob names to their canonical (target) name.
    // When looking up or assigning to glob slots, we resolve through this map.
    static final Map<String, String> globAliases =
            new CurrentRuntimePlainMap<>(state -> state.globAliases());

    // Flags used by operator override
    // globalGlobs: Tracks typeglob assignments (e.g., *CORE::GLOBAL::hex = sub {...})
    // Used to detect when built-in operators have been globally overridden
    static final Map<String, Boolean> globalGlobs =
            new CurrentRuntimePlainMap<>(state -> state.operatorOverrideGlobs());
    // Regular expression for regex variables like $main::1
    static Pattern regexVariablePattern = Pattern.compile("^main::(\\d+)$");
    static long stashEnumerationVersion() {
        return globalState().stashEnumerationVersion();
    }

    static void invalidateStashEnumerationCache() {
        globalState().invalidateStashEnumeration();
    }

    static long codeRefVersion() {
        return globalState().codeRefVersion();
    }

    private static void invalidateCodeRefNames() {
        globalState().invalidateCodeRefs();
    }

    private static GlobalRuntimeState globalState() {
        return PerlRuntime.current().globalState;
    }

    /** Return the generated-class loader owned by the bound runtime. */
    public static CustomClassLoader getGlobalClassLoader() {
        return globalState().generatedClassLoader();
    }

    /** Replace the generated-class loader owned by the bound runtime. */
    public static void setGlobalClassLoader(CustomClassLoader loader) {
        globalState().generatedClassLoader(loader);
    }

    private static Map<String, RuntimeScalar> foreachGlobalAliases() {
        return globalState().foreachScalarAliases();
    }

    private static Map<String, RuntimeScalar> temporaryGlobalAliases() {
        return globalState().temporaryScalarAliases();
    }

    private static Map<String, RuntimeScalar> globalPseudoConstants() {
        return globalState().pseudoConstants();
    }

    private static Map<String, RuntimeScalar> pinnedCodeRefs() {
        return globalState().pinnedCodeRefs();
    }

    private static Set<String> deletedCodeRefPins() {
        return globalState().deletedCodeRefPins();
    }

    private static Map<String, Integer> localizedCodeRefDepth() {
        return globalState().localizedCodeRefDepth();
    }

    private static IdentityHashMap<RuntimeScalar, String> displacedLocalizedCodeRefs() {
        return globalState().displacedLocalizedCodeRefs();
    }

    private static Set<String> hiddenIORefsAfterStashDelete() {
        return globalState().hiddenIoSlotsAfterStashDelete();
    }

    /** Stable facade for runtime-owned maps that do not need package-root hooks. */
    private static final class CurrentRuntimePlainMap<T> extends AbstractMap<String, T> {
        private final Function<GlobalRuntimeState, Map<String, T>> table;

        private CurrentRuntimePlainMap(Function<GlobalRuntimeState, Map<String, T>> table) {
            this.table = table;
        }

        private Map<String, T> delegate() {
            return table.apply(globalState());
        }

        @Override public T get(Object key) { return delegate().get(key); }
        @Override public boolean containsKey(Object key) { return delegate().containsKey(key); }
        @Override public int size() { return delegate().size(); }
        @Override public boolean isEmpty() { return delegate().isEmpty(); }
        @Override public T put(String key, T value) { return delegate().put(key, value); }
        @Override public T putIfAbsent(String key, T value) { return delegate().putIfAbsent(key, value); }
        @Override public T remove(Object key) { return delegate().remove(key); }
        @Override public void clear() { delegate().clear(); }
        @Override public Set<Entry<String, T>> entrySet() { return delegate().entrySet(); }
        @Override public Set<String> keySet() { return delegate().keySet(); }
        @Override public Collection<T> values() { return delegate().values(); }
    }

    /**
     * Stable facade retained for source and generated-bytecode compatibility.
     * Each operation resolves the table from the currently bound runtime.
     */
    private static final class CurrentRuntimeMap<T extends RuntimeBase> extends AbstractMap<String, T> {
        private final Function<GlobalRuntimeState, Map<String, T>> table;

        private CurrentRuntimeMap(Function<GlobalRuntimeState, Map<String, T>> table) {
            this.table = table;
        }

        private Map<String, T> delegate() {
            return table.apply(globalState());
        }

        @Override
        public T get(Object key) {
            return delegate().get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate().containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return delegate().containsValue(value);
        }

        @Override
        public int size() {
            return delegate().size();
        }

        @Override
        public boolean isEmpty() {
            return delegate().isEmpty();
        }

        @Override
        public T put(String key, T value) {
            boolean newKey = !delegate().containsKey(key);
            markStashEntryVisible(key);
            markPackageGlobalRoot(value);
            T previous = delegate().put(key, value);
            if (newKey) {
                invalidateStashEnumerationCache();
            }
            invalidatePackageRootSnapshot();
            return previous;
        }

        @Override
        public T putIfAbsent(String key, T value) {
            T previous = delegate().get(key);
            if (previous != null) {
                markPackageGlobalRoot(previous);
                return previous;
            }
            return put(key, value);
        }

        @Override
        public T remove(Object key) {
            T previous = delegate().remove(key);
            if (previous != null) {
                invalidateStashEnumerationCache();
                invalidatePackageRootSnapshot();
            }
            return previous;
        }

        @Override
        public void clear() {
            if (!delegate().isEmpty()) {
                invalidateStashEnumerationCache();
                invalidatePackageRootSnapshot();
            }
            delegate().clear();
        }

        @Override
        public Set<Entry<String, T>> entrySet() {
            Map<String, T> entries = delegate();
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, T>> iterator() {
                    Iterator<Entry<String, T>> iterator = entries.entrySet().iterator();
                    return new Iterator<>() {
                        private boolean canRemove;

                        @Override public boolean hasNext() { return iterator.hasNext(); }

                        @Override
                        public Entry<String, T> next() {
                            Entry<String, T> current = iterator.next();
                            canRemove = true;
                            String key = current.getKey();
                            return new SimpleEntry<>(key, current.getValue()) {
                                @Override
                                public T setValue(T value) {
                                    T previous = CurrentRuntimeMap.this.put(key, value);
                                    super.setValue(value);
                                    return previous;
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            if (!canRemove) throw new IllegalStateException("next() has not been called");
                            iterator.remove();
                            canRemove = false;
                            invalidateStashEnumerationCache();
                            invalidatePackageRootSnapshot();
                        }
                    };
                }

                @Override public int size() { return entries.size(); }
                @Override public void clear() { CurrentRuntimeMap.this.clear(); }
            };
        }
    }

    /**
     * Tracks FQN on stash-backed code scalars and invalidates method-resolution cache
     * lines that depend on the sub's leaf name when the map entry is meaningfully
     * created or replaced (see {@link InheritanceResolver#invalidateMethodLookupCachesForStashSubKey}).
     */
    private static final class GlobalCodeRefMap extends AbstractMap<String, RuntimeScalar> {
        private Map<String, RuntimeScalar> delegate() {
            return globalState().codeRefs();
        }

        private static void maybeInvalidateMethodCacheForCodeRefPut(String key, RuntimeScalar previous, RuntimeScalar value) {
            if (key == null || value == null) {
                return;
            }
            boolean reseat = previous != null && previous != value;
            boolean firstDefinedInstall = previous == null && RuntimeCode.isCodeDefined(value);
            if (reseat || firstDefinedInstall) {
                InheritanceResolver.invalidateMethodLookupCachesForStashSubKey(key);
            }
        }

        @Override
        public RuntimeScalar put(String key, RuntimeScalar value) {
            boolean newKey = !delegate().containsKey(key);
            markStashEntryVisible(key);
            markPackageGlobalRoot(value);
            RuntimeScalar old = delegate().put(key, value);
            if (old != value) {
                invalidateCodeRefNames();
            }
            if (newKey) {
                invalidateStashEnumerationCache();
            }
            invalidatePackageRootSnapshot();
            if (old != null && old != value) {
                old.globalCodeRefFqn = null;
            }
            if (value != null) {
                value.globalCodeRefFqn = key;
            }
            maybeInvalidateMethodCacheForCodeRefPut(key, old, value);
            return old;
        }

        @Override
        public RuntimeScalar putIfAbsent(String key, RuntimeScalar value) {
            RuntimeScalar existing = get(key);
            if (existing != null) {
                markPackageGlobalRoot(existing);
                return existing;
            }
            return put(key, value);
        }

        @Override
        public RuntimeScalar remove(Object key) {
            RuntimeScalar prev = delegate().remove(key);
            if (prev != null) {
                invalidateCodeRefNames();
                invalidateStashEnumerationCache();
                invalidatePackageRootSnapshot();
                prev.globalCodeRefFqn = null;
            }
            if (key instanceof String s) {
                InheritanceResolver.invalidateMethodLookupCachesForStashSubKey(s);
            }
            return prev;
        }

        @Override
        public void clear() {
            if (!isEmpty()) {
                invalidateCodeRefNames();
                invalidateStashEnumerationCache();
                for (Map.Entry<String, RuntimeScalar> entry : delegate().entrySet()) {
                    RuntimeScalar s = entry.getValue();
                    if (s != null) {
                        s.globalCodeRefFqn = null;
                    }
                    InheritanceResolver.invalidateMethodLookupCachesForStashSubKey(entry.getKey());
                }
                invalidatePackageRootSnapshot();
            }
            delegate().clear();
        }

        @Override public RuntimeScalar get(Object key) { return delegate().get(key); }
        @Override public boolean containsKey(Object key) { return delegate().containsKey(key); }
        @Override public int size() { return delegate().size(); }
        @Override public boolean isEmpty() { return delegate().isEmpty(); }
        @Override
        public Set<Entry<String, RuntimeScalar>> entrySet() {
            Map<String, RuntimeScalar> entries = delegate();
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, RuntimeScalar>> iterator() {
                    Iterator<Entry<String, RuntimeScalar>> iterator = entries.entrySet().iterator();
                    return new Iterator<>() {
                        private Entry<String, RuntimeScalar> current;
                        private boolean canRemove;

                        @Override public boolean hasNext() { return iterator.hasNext(); }

                        @Override
                        public Entry<String, RuntimeScalar> next() {
                            current = iterator.next();
                            canRemove = true;
                            String key = current.getKey();
                            return new SimpleEntry<>(key, current.getValue()) {
                                @Override
                                public RuntimeScalar setValue(RuntimeScalar value) {
                                    RuntimeScalar previous = GlobalCodeRefMap.this.put(key, value);
                                    super.setValue(value);
                                    return previous;
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            if (!canRemove) throw new IllegalStateException("next() has not been called");
                            String key = current.getKey();
                            RuntimeScalar value = current.getValue();
                            iterator.remove();
                            invalidateCodeRefNames();
                            canRemove = false;
                            if (value != null) value.globalCodeRefFqn = null;
                            invalidateStashEnumerationCache();
                            invalidatePackageRootSnapshot();
                            InheritanceResolver.invalidateMethodLookupCachesForStashSubKey(key);
                        }
                    };
                }

                @Override public int size() { return entries.size(); }
                @Override public void clear() { GlobalCodeRefMap.this.clear(); }
            };
        }
    }

    /** Runtime-selecting facade for named IO and FORMAT stash slots. */
    private static final class StashSlotMap<T> extends AbstractMap<String, T> {
        private final Function<GlobalRuntimeState, Map<String, T>> table;

        private StashSlotMap(Function<GlobalRuntimeState, Map<String, T>> table) {
            this.table = table;
        }

        private Map<String, T> delegate() {
            return table.apply(globalState());
        }

        @Override public T get(Object key) { return delegate().get(key); }
        @Override public boolean containsKey(Object key) { return delegate().containsKey(key); }
        @Override public int size() { return delegate().size(); }
        @Override public boolean isEmpty() { return delegate().isEmpty(); }

        @Override
        public T put(String key, T value) {
            boolean newKey = !delegate().containsKey(key);
            T previous = delegate().put(key, value);
            if (newKey) {
                invalidateStashEnumerationCache();
            }
            return previous;
        }

        @Override
        public T putIfAbsent(String key, T value) {
            T previous = delegate().putIfAbsent(key, value);
            if (previous == null) {
                invalidateStashEnumerationCache();
            }
            return previous;
        }

        @Override
        public T remove(Object key) {
            T previous = delegate().remove(key);
            if (previous != null) {
                invalidateStashEnumerationCache();
            }
            return previous;
        }

        @Override
        public void clear() {
            if (!isEmpty()) {
                invalidateStashEnumerationCache();
            }
            delegate().clear();
        }

        @Override
        public Set<Entry<String, T>> entrySet() {
            Map<String, T> entries = delegate();
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, T>> iterator() {
                    Iterator<Entry<String, T>> iterator = entries.entrySet().iterator();
                    return new Iterator<>() {
                        private Entry<String, T> current;
                        private boolean canRemove;

                        @Override public boolean hasNext() { return iterator.hasNext(); }

                        @Override
                        public Entry<String, T> next() {
                            current = iterator.next();
                            canRemove = true;
                            String key = current.getKey();
                            return new SimpleEntry<>(key, current.getValue()) {
                                @Override
                                public T setValue(T value) {
                                    T previous = StashSlotMap.this.put(key, value);
                                    super.setValue(value);
                                    return previous;
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            if (!canRemove) throw new IllegalStateException("next() has not been called");
                            iterator.remove();
                            canRemove = false;
                            invalidateStashEnumerationCache();
                        }
                    };
                }

                @Override public int size() { return entries.size(); }
                @Override public void clear() { StashSlotMap.this.clear(); }
            };
        }
    }

    static <T extends RuntimeBase> T markPackageGlobalRoot(T root) {
        if (root == null) return null;
        root.isPackageGlobalRoot = true;
        if (root instanceof RuntimeHash hash) {
            hash.isGlobalPackageHash = true;
            // RuntimeStash.elements is a synthetic symbol-table view. Iterating
            // it materializes every visible glob by scanning all global slot
            // maps. Package declarations call this method repeatedly while
            // compiling dependency-heavy applications, turning module loading
            // into quadratic work. The real scalar/array/hash/code slots are
            // independently registered and rooted, so a stash view has no
            // owned element values that need marking here.
            if (hash instanceof RuntimeStash) {
                return root;
            }
            for (RuntimeScalar value : hash.elements.values()) {
                hash.markPackageRootedValue(value);
            }
        } else if (root instanceof RuntimeArray array) {
            for (RuntimeScalar value : array.elements) {
                array.markPackageRootedValue(value);
            }
        }
        return root;
    }

    static void invalidatePackageRootSnapshot() {
        MortalList.invalidateExternalRootSnapshot();
    }

    /**
     * Materializes the stash hashes implied by a package declaration.
     *
     * <p>In Perl, {@code package Foo::Bar;} creates visible symbol-table
     * entries for both {@code Foo::} in {@code %main::} and {@code Bar::} in
     * {@code %Foo::}, even before the package defines subs or variables.
     */
    public static void ensurePackageStash(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        String normalized = packageName;
        if (normalized.endsWith("::")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.length() > 6 && normalized.startsWith("main::")) {
            normalized = normalized.substring(6);
        }

        if (normalized.equals("main")) {
            getGlobalHash("main::");
            packageExistsCache.put("main", true);
            return;
        }

        StringBuilder stashName = new StringBuilder();
        for (String part : normalized.split("::")) {
            if (part.isEmpty()) {
                continue;
            }
            stashName.append(part).append("::");
            getGlobalHash(stashName.toString());
            packageExistsCache.put(stashName.substring(0, stashName.length() - 2), true);
        }
        packageExistsCache.put(packageName, true);
    }

    /**
     * Marks a global variable as explicitly declared (e.g., via use vars, Exporter import).
     */
    public static void declareGlobalVariable(String key) {
        globalState().declaredGlobalVariables().add(key);
    }

    /**
     * Marks a global array as explicitly declared.
     */
    public static void declareGlobalArray(String key) {
        globalState().declaredGlobalArrays().add(key);
    }

    /**
     * Marks a global hash as explicitly declared.
     */
    public static void declareGlobalHash(String key) {
        globalState().declaredGlobalHashes().add(key);
    }

    /**
     * Checks if a global variable was explicitly declared (not just auto-vivified).
     */
    public static boolean isDeclaredGlobalVariable(String key) {
        return globalState().declaredGlobalVariables().contains(key)
                || key.endsWith("::a") || key.endsWith("::b");
    }

    /**
     * Checks if a global array was explicitly declared.
     */
    public static boolean isDeclaredGlobalArray(String key) {
        return globalState().declaredGlobalArrays().contains(key);
    }

    /**
     * Checks if a global hash was explicitly declared.
     */
    public static boolean isDeclaredGlobalHash(String key) {
        return globalState().declaredGlobalHashes().contains(key);
    }

    /**
     * Resets all global variables, arrays, hashes, code references, and IO references.
     * Also destroys and recreates the global class loader to allow GC of old classes.
     */
    public static void resetAllGlobals() {
        // Clear all global state
        globalVariables.clear();
        globalArrays.clear();
        globalHashes.clear();
        globalState().clearCoreValues();
        globalCodeRefs.clear();
        globalState().clearCodeValues();
        globalIORefs.clear();
        hiddenIORefsAfterStashDelete().clear();
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null) {
            runtime.resetStandardIOGlobVisibility();
        }
        globalFormatRefs.clear();
        globalState().clearIoAndFormatValues();
        globalGlobs.clear();
        isSubs.clear();
        globalState().clearGlobAndStashValues();
        globalState().clearDeclarationsAndPackageServices();

        org.perlonjava.runtime.WarningBitsRegistry.clear();
        WarningFlags.resetRuntimeState();

        RuntimeCode.clearCaches();

        // Clear special blocks (INIT, END, CHECK, UNITCHECK) to prevent stale code references.
        // When the classloader is replaced, old INIT blocks may reference evalTags that no longer
        // exist in the cleared evalContext, causing "ctx is null" errors.
        SpecialBlock.getInitBlocks().elements.clear();
        SpecialBlock.getEndBlocks().elements.clear();
        SpecialBlock.getCheckBlocks().elements.clear();

        // Method resolution caches can grow across test scripts.
        InheritanceResolver.noteIsaMutation();

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

        MortalList.invalidateAllRootSnapshots();
    }

    public static void setStashAlias(String dstNamespace, String srcNamespace) {
        String dst = normalizeStashNamespace(dstNamespace);
        String src = normalizeStashNamespace(srcNamespace);
        preserveAliasesBeforeStashRebind(dst);
        stashAliases.put(dst, src);
        resolvedStashAliasCache.clear();
        invalidatePackageRootSnapshot();
    }

    /**
     * A stash alias retains the old stash when its source name is later rebound.
     * For example, after {@code *Alias:: = *Source::; *Source:: = *Other::},
     * descendants reached through {@code Alias::} still name the former Source
     * subtree. Materialise that subtree below each direct alias before replacing
     * the source binding.
     */
    private static void preserveAliasesBeforeStashRebind(String sourcePrefix) {
        Map<String, String> aliases = new HashMap<>(stashAliases);
        ArrayList<String> destinations = new ArrayList<>();
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (sourcePrefix.equals(alias.getValue())) {
                destinations.add(alias.getKey());
            }
        }
        if (destinations.isEmpty()) {
            return;
        }

        Map<String, RuntimeScalar> scalars = snapshotNamespace(globalVariables, sourcePrefix);
        Map<String, RuntimeArray> arrays = snapshotNamespace(globalArrays, sourcePrefix);
        Map<String, RuntimeHash> hashes = snapshotNamespace(globalHashes, sourcePrefix);
        Map<String, RuntimeScalar> codes = snapshotNamespace(globalCodeRefs, sourcePrefix);
        RuntimeGlob.NamespaceMove move = new RuntimeGlob.NamespaceMove(
                sourcePrefix, scalars, arrays, hashes, codes);

        globalCodeRefs.keySet().removeIf(key -> key.startsWith(sourcePrefix));
        clearGlobalPseudoConstantsForNamespace(sourcePrefix);
        globalVariables.keySet().removeIf(key -> key.startsWith(sourcePrefix));
        globalArrays.keySet().removeIf(key -> key.startsWith(sourcePrefix));
        globalHashes.keySet().removeIf(key -> key.startsWith(sourcePrefix));
        removeGlobalIORefsForNamespace(sourcePrefix);
        globalFormatRefs.keySet().removeIf(key -> key.startsWith(sourcePrefix));
        clearHiddenIORefsForNamespace(sourcePrefix);
        clearPinnedCodeRefsForNamespace(sourcePrefix);
        invalidateStashEnumerationCache();

        for (String destination : destinations) {
            installNamespaceMove(move, destination);
            clearStashAlias(destination);
        }

        // Aliases below a destination may have pointed into the old source
        // subtree (e.g. Alias::Nested:: -> Source::Inner::). Keep them attached
        // to the materialised subtree instead of following Source's new binding.
        for (Map.Entry<String, String> alias : new HashMap<>(stashAliases).entrySet()) {
            if (!alias.getValue().startsWith(sourcePrefix)) {
                continue;
            }
            String destination = null;
            for (String candidate : destinations) {
                if (alias.getKey().startsWith(candidate)
                        && (destination == null || candidate.length() > destination.length())) {
                    destination = candidate;
                }
            }
            if (destination != null) {
                stashAliases.put(alias.getKey(),
                        destination + alias.getValue().substring(sourcePrefix.length()));
            }
        }
        resolvedStashAliasCache.clear();
    }

    private static <T> Map<String, T> snapshotNamespace(Map<String, T> values, String prefix) {
        Map<String, T> snapshot = new HashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        return snapshot;
    }

    static void installNamespaceMove(RuntimeGlob.NamespaceMove move, String destinationPrefix) {
        for (Map.Entry<String, RuntimeScalar> entry : move.scalars.entrySet()) {
            globalVariables.put(destinationPrefix + entry.getKey().substring(move.sourcePrefix.length()), entry.getValue());
        }
        for (Map.Entry<String, RuntimeArray> entry : move.arrays.entrySet()) {
            globalArrays.put(destinationPrefix + entry.getKey().substring(move.sourcePrefix.length()), entry.getValue());
        }
        for (Map.Entry<String, RuntimeHash> entry : move.hashes.entrySet()) {
            String destinationKey = destinationPrefix + entry.getKey().substring(move.sourcePrefix.length());
            // Each stash view carries its namespace for subsequent
            // `$stash->{"Child::"}` deletion. Reusing a nested source view
            // would make a moved `two::Inner::` still delete children from
            // `one::Inner::`.
            RuntimeHash value = entry.getValue();
            if (value instanceof RuntimeStash) {
                value = new RuntimeStash(destinationKey);
            }
            globalHashes.put(destinationKey, value);
        }
        for (Map.Entry<String, RuntimeScalar> entry : move.codes.entrySet()) {
            globalCodeRefs.put(destinationPrefix + entry.getKey().substring(move.sourcePrefix.length()), entry.getValue());
        }
        invalidatePackageRootSnapshot();
        InheritanceResolver.invalidateCache();
        clearPackageCache();
    }

    private static String normalizeStashNamespace(String namespace) {
        String normalized = namespace.endsWith("::") ? namespace : namespace + "::";
        // `main:::` is the fully-qualified spelling of the root package named
        // `:`. Keep the canonical stash spelling as `:::` so it agrees with
        // class names written simply as `:`.
        if ("main:::".equals(normalized)) {
            return ":::";
        }
        // Packages are children of main::, so main::Foo:: and Foo:: name the
        // same stash. Keep main:: itself intact.
        if (normalized.length() > 6 && normalized.startsWith("main::")) {
            return normalized.substring(6);
        }
        return normalized;
    }

    public static void clearStashAlias(String namespace) {
        String key = namespace.endsWith("::") ? namespace : namespace + "::";
        if (stashAliases.remove(key) != null) {
            invalidatePackageRootSnapshot();
        }
        resolvedStashAliasCache.clear();
    }

    public static String resolveStashAlias(String namespace) {
        if (namespace == null || stashAliases.isEmpty()) {
            return namespace;
        }
        boolean hasTrailingSeparator = namespace.endsWith("::");
        String resolved = resolvePackageAliasCached(normalizeStashNamespace(namespace));
        // Callers use both class names and package names.  Retain the form
        // they supplied while letting the cached resolver apply aliases to a
        // package subtree (for example Clone::Inner through *Clone:: =
        // *Outer::).  MRO and UNIVERSAL::isa both need that descendant form.
        if (!hasTrailingSeparator && resolved.endsWith("::")) {
            return resolved.substring(0, resolved.length() - 2);
        }
        return resolved;
    }

    /**
     * Cache of fully-resolved stash aliases with transitive chains collapsed to
     * their terminal package. Keys and values both include the trailing "::".
     * Invariant: for any `"Pkg::"` with no alias, the cache stores the SAME
     * string instance back, so callers can use reference equality to detect a
     * non-alias hit without allocating. Cleared whenever {@link #stashAliases}
     * is mutated.
     */
    private static final Map<String, String> resolvedStashAliasCache =
            new CurrentRuntimePlainMap<>(state -> state.resolvedStashAliases());

    /** Hop cap for cycle detection in {@link #resolvePackageAliasCached}. */
    private static final int STASH_ALIAS_HOP_CAP = 16;

    /**
     * Resolves a package name (with trailing "::") to its terminal target,
     * following any {@link #setStashAlias} chain to a fixed point. Result is
     * cached. Returns the input string (identity-equal) when no alias applies.
     */
    private static String resolvePackageAliasCached(String pkgWithColons) {
        String cached = resolvedStashAliasCache.get(pkgWithColons);
        if (cached != null) {
            return cached;
        }
        String current = pkgWithColons;
        for (int hop = 0; hop < STASH_ALIAS_HOP_CAP; hop++) {
            String next = stashAliases.get(current);
            if (next == null) {
                // A stash entry aliases the entire package subtree. Find the
                // longest matching package prefix so recursive constructs such
                // as Acme::Meta::Meta::Meta:: resolve one level at a time.
                String bestPrefix = null;
                for (String prefix : stashAliases.keySet()) {
                    if (current.startsWith(prefix)
                            && (bestPrefix == null || prefix.length() > bestPrefix.length())) {
                        bestPrefix = prefix;
                    }
                }
                if (bestPrefix != null) {
                    next = stashAliases.get(bestPrefix) + current.substring(bestPrefix.length());
                }
            }
            if (next == null || next.equals(current)) {
                break;
            }
            current = next;
        }
        // Use identity when no alias applies so callers can fast-path with ==.
        String result = current.equals(pkgWithColons) ? pkgWithColons : current;
        resolvedStashAliasCache.put(pkgWithColons, result);
        return result;
    }

    /**
     * Resolves a fully-qualified variable/sub name through any stash aliases
     * declared via `*Dst:: = *Src::`. FQNs without "::" are returned unchanged;
     * FQNs ending in "::" (the stash-view hash itself, e.g. `%Foo::`) are
     * returned unchanged — callers working with those should use
     * {@link #resolveStashAlias(String)} directly and the unified hash storage.
     *
     * <p>Fast path: if no aliases have been declared, returns the input
     * reference unchanged (no hashing, no substring). Hot-path accessors may
     * therefore call this unconditionally.
     *
     * @param fqn a name like "Foo::bar" — may or may not contain "::"
     * @return the alias-resolved FQN, or the original reference if unchanged
     */
    public static String resolveAliasedFqn(String fqn) {
        if (stashAliases.isEmpty() || fqn == null) {
            return fqn;
        }
        int idx = fqn.lastIndexOf("::");
        if (idx < 0) {
            return fqn;
        }
        // "Pkg::" — the stash-view hash itself. Leave it alone.
        if (idx == fqn.length() - 2) {
            return fqn;
        }
        String pkg = fqn.substring(0, idx + 2);
        String resolved = resolvePackageAliasCached(pkg);
        if (resolved == pkg) {  // identity: no alias applied
            return fqn;
        }
        return resolved + fqn.substring(idx + 2);
    }

    /**
     * Migrates all IO entries keyed under {@code dstNs} (a package name ending
     * in "::") into the corresponding slots under {@code srcNs}. Called when
     * `*Dst:: = *Src::` fires so a DATA filehandle placeholder set up by the
     * parser at Dst::DATA remains reachable after lookups start resolving to
     * Src::DATA. Entries already present under {@code srcNs} are preserved.
     * Scoped to IO only — CVs, scalars, arrays, hashes keep their original
     * package binding as in real Perl.
     */
    public static void migrateStashIOEntries(String dstNs, String srcNs) {
        if (dstNs.equals(srcNs)) return;
        int dstLen = dstNs.length();
        for (String key : new java.util.ArrayList<>(globalIORefs.keySet())) {
            if (key.startsWith(dstNs) && key.length() > dstLen) {
                String newKey = srcNs + key.substring(dstLen);
                RuntimeGlob value = globalIORefs.remove(key);
                if (value != null) globalIORefs.putIfAbsent(newKey, value);
                if (hiddenIORefsAfterStashDelete().remove(key)) {
                    hiddenIORefsAfterStashDelete().add(newKey);
                }
            }
        }
    }

    static void hideIORefAfterStashDelete(String key) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null && runtime.standardIOGlob(key) != null) {
            runtime.hideStandardIOGlob(key);
            invalidateStashEnumerationCache();
            return;
        }
        if (key != null && globalIORefs.containsKey(key)) {
            if (hiddenIORefsAfterStashDelete().add(key)) {
                invalidateStashEnumerationCache();
            }
        }
    }

    static void markStashEntryVisible(String key) {
        if (key != null) {
            PerlRuntime runtime = PerlRuntime.currentOrNull();
            if (runtime != null && runtime.standardIOGlob(key) != null) {
                runtime.showStandardIOGlob(key);
                invalidateStashEnumerationCache();
            }
            if (hiddenIORefsAfterStashDelete().remove(key)) {
                invalidateStashEnumerationCache();
            }
        }
    }

    static boolean isIORefHiddenAfterStashDelete(String key) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null && runtime.standardIOGlob(key) != null) {
            return !runtime.isStandardIOGlobVisible(key);
        }
        return key != null && hiddenIORefsAfterStashDelete().contains(key);
    }

    static boolean isVisibleGlobalIORef(String key) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null && runtime.standardIOGlob(key) != null) {
            return runtime.isStandardIOGlobVisible(key);
        }
        return key != null
                && globalIORefs.containsKey(key)
                && !hiddenIORefsAfterStashDelete().contains(key);
    }

    static boolean containsVisibleGlobalIORefWithPrefix(String prefix) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null) {
            for (String key : runtime.standardIOGlobNames()) {
                boolean matches = prefix.endsWith("::")
                        ? key.startsWith(prefix)
                        : key.equals(prefix);
                if (matches && runtime.isStandardIOGlobVisible(key)) {
                    return true;
                }
            }
        }
        for (String key : globalIORefs.keySet()) {
            boolean matches = prefix.endsWith("::")
                    ? key.startsWith(prefix)
                    : key.equals(prefix);
            if (matches && !hiddenIORefsAfterStashDelete().contains(key)) {
                return true;
            }
        }
        return false;
    }

    static Iterable<String> visibleGlobalIOKeys() {
        ArrayList<String> keys = new ArrayList<>();
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null) {
            for (String key : runtime.standardIOGlobNames()) {
                if (runtime.isStandardIOGlobVisible(key)) {
                    keys.add(key);
                }
            }
        }
        for (String key : globalIORefs.keySet()) {
            if (!hiddenIORefsAfterStashDelete().contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    static void clearHiddenIORefsForNamespace(String prefix) {
        if (hiddenIORefsAfterStashDelete().removeIf(k -> k.startsWith(prefix))) {
            invalidateStashEnumerationCache();
        }
    }

    /**
     * Sets a glob alias. After `*a = *b`, calling setGlobAlias("a", "b") makes
     * all slot assignments to "a" also affect "b" and vice versa.
     */
    public static void setGlobAlias(String fromGlob, String toGlob) {
        // Find the canonical name for toGlob (in case it's already an alias)
        String canonical = resolveGlobAlias(toGlob);
        // Don't create self-loops
        if (!fromGlob.equals(canonical)) {
            globAliases.put(fromGlob, canonical);
            invalidatePackageRootSnapshot();
        }
        // Also ensure toGlob points to the canonical name (unless it would create a self-loop)
        if (!toGlob.equals(canonical) && !toGlob.equals(fromGlob)) {
            globAliases.put(toGlob, canonical);
            invalidatePackageRootSnapshot();
        }
    }

    /**
     * Resolves a glob name to its canonical name.
     * If the glob is aliased, returns the target name; otherwise returns the input.
     */
    public static String resolveGlobAlias(String globName) {
        String aliased = globAliases.get(globName);
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
        for (Map.Entry<String, String> entry : globAliases.entrySet()) {
            if (resolveGlobAlias(entry.getKey()).equals(canonical) && !group.contains(entry.getKey())) {
                group.add(entry.getKey());
            }
        }
        return group;
    }

    /**
     * Returns true if {@code globName} participates in a `*A = *B` style glob
     * alias relationship. Includes both sides — only one direction is stored
     * in {@link #globAliases}, so the canonical destination (e.g. {@code B}
     * after {@code *A = *B}) is detected by walking the map values.
     */
    public static boolean isInGlobAliasGroup(String globName) {
        if (globAliases.isEmpty()) return false;
        if (globAliases.containsKey(globName)) return true;
        return globAliases.containsValue(globName);
    }

    /**
     * Retrieves a global variable by its key, initializing it if necessary.
     * If the key matches a regex capture variable pattern, it initializes a special variable.
     *
     * @param key The key of the global variable.
     * @return The RuntimeScalar representing the global variable.
     */
    public static RuntimeScalar getGlobalVariable(String key) {
        // Stash alias resolution with fallback: if the aliased destination has
        // a value, use it; otherwise fall through to the raw key. See
        // getGlobalCodeRef for the rationale (preserve compile-time-qualified
        // refs while letting runtime symbolic refs follow the alias).
        String resolvedKey = key;
        if (!stashAliases.isEmpty()) {
            resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key) {
                RuntimeScalar resolved = globalVariables.get(resolvedKey);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            // No scalar was pinned to the original package before the stash
            // alias. New symbols belong to the aliased stash; retain the raw
            // fallback only for an already-existing compile-time-qualified SV.
            String storageKey = resolvedKey != key ? resolvedKey : key;
            // Need to initialize global variable
            Matcher matcher = regexVariablePattern.matcher(storageKey);
            if (matcher.matches() && !storageKey.equals("main::0")) {
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
            markPackageGlobalRoot(var);
            globalVariables.put(storageKey, var);
            invalidatePackageRootSnapshot();
        } else if (temporaryGlobalAliases().get(key) != var) {
            markPackageGlobalRoot(var);
        }
        return var;
    }

    public static RuntimeScalar aliasGlobalVariable(String key, String to) {
        RuntimeScalar var = globalVariables.get(to);
        clearForeachGlobalAlias(key);
        markPackageGlobalRoot(var);
        globalVariables.put(key, var);
        invalidatePackageRootSnapshot();
        return var;
    }

    public static void aliasGlobalVariable(String key, RuntimeScalar var) {
        clearForeachGlobalAlias(key);
        markPackageGlobalRoot(var);
        globalVariables.put(key, var);
        invalidatePackageRootSnapshot();
    }

    public static void aliasGlobalArray(String key, RuntimeArray array) {
        markPackageGlobalRoot(array);
        globalArrays.put(key, array);
        invalidatePackageRootSnapshot();
    }

    public static void aliasGlobalHash(String key, RuntimeHash hash) {
        markPackageGlobalRoot(hash);
        globalHashes.put(key, hash);
        invalidatePackageRootSnapshot();
    }

    /**
     * Temporarily aliases a package scalar without permanently labelling the
     * aliased value as package-global. Operators such as map and grep localize
     * {@code $_} to each input element; the global slot is a reachability root
     * while the alias is installed, but the input SV must become ephemeral
     * again when the operator restores the old slot.
     */
    public static void aliasTemporaryGlobalVariable(String key, RuntimeScalar var) {
        clearForeachGlobalAlias(key);
        temporaryGlobalAliases().put(key, var);
        globalVariables.put(key, var);
        invalidatePackageRootSnapshot();
    }

    public static boolean isTemporaryGlobalAlias(String key) {
        return temporaryGlobalAliases().containsKey(key);
    }

    public static boolean isTemporaryGlobalAliasValue(RuntimeScalar value) {
        for (RuntimeScalar alias : temporaryGlobalAliases().values()) {
            if (alias == value) return true;
        }
        return false;
    }

    public static void restoreTemporaryGlobalVariable(
            String key, RuntimeScalar var, boolean wasTemporary) {
        clearForeachGlobalAlias(key);
        if (wasTemporary) {
            temporaryGlobalAliases().put(key, var);
        } else {
            temporaryGlobalAliases().remove(key);
        }
        globalVariables.put(key, var);
        invalidatePackageRootSnapshot();
    }

    public static void aliasForeachGlobalVariable(String key, RuntimeScalar var) {
        clearForeachGlobalAlias(key);
        retainForeachAlias(var);
        foreachGlobalAliases().put(key, var);
        markPackageGlobalRoot(var);
        globalVariables.put(key, var);
        invalidatePackageRootSnapshot();
    }

    public static void clearForeachGlobalAlias(String key) {
        RuntimeScalar previous = foreachGlobalAliases().remove(key);
        if (previous != null) {
            releaseForeachAlias(previous);
        }
    }

    private static void retainForeachAlias(RuntimeScalar scalar) {
        if (scalar != null
                && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base) {
            base.foreachAliasCount++;
        }
    }

    private static void releaseForeachAlias(RuntimeScalar scalar) {
        if (scalar != null
                && (scalar.type & RuntimeScalarType.REFERENCE_BIT) != 0
                && scalar.value instanceof RuntimeBase base
                && base.foreachAliasCount > 0) {
            base.foreachAliasCount--;
        }
    }

    /**
     * Sets the value of a global variable.
     *
     * @param key   The key of the global variable.
     * @param value The value to set.
     */
    public static void setGlobalVariable(String key, String value) {
        RuntimeScalar scalar = getGlobalVariable(key);
        if ("main::@".equals(key)
                && (scalar instanceof RuntimeScalarReadOnly
                    || scalar.type == RuntimeScalarType.READONLY_SCALAR)) {
            // Perl's eval machinery can replace $@ even when stash assignment
            // has aliased it to a read-only literal (`$::{'@'} = \3`).  An
            // ordinary Perl `$@ = ...` still uses RuntimeScalar.set() and must
            // continue to throw for that alias.
            globalVariables.put(key, new RuntimeScalar(value));
            return;
        }
        scalar.set(value);
    }

    /**
     * Checks if a global variable exists.
     *
     * @param key The key of the global variable.
     * @return True if the global variable exists, false otherwise.
     */
    public static boolean existsGlobalVariable(String key) {
        if (!stashAliases.isEmpty()) {
            String resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key && globalVariables.containsKey(resolvedKey)) {
                return true;
            }
        }
        return globalVariables.containsKey(key)
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
        RuntimeScalar var = globalVariables.get(key);
        return var != null && var.getDefinedBoolean();
    }

    /**
     * Removes a global variable by its key.
     *
     * @param key The key of the global variable.
     * @return The removed RuntimeScalar, or null if it did not exist.
     */
    public static RuntimeScalar removeGlobalVariable(String key) {
        clearGlobalPseudoConstant(key);
        RuntimeScalar removed = globalVariables.remove(key);
        if (removed != null) invalidatePackageRootSnapshot();
        return removed;
    }

    public static void setGlobalPseudoConstant(String key, RuntimeScalar scalar) {
        if (key == null || scalar == null) {
            return;
        }
        String resolvedKey = resolveAliasedFqn(key);
        markPackageGlobalRoot(scalar);
        globalPseudoConstants().put(resolvedKey, scalar);
        if (!resolvedKey.equals(key)) {
            // Compilation may still be parsing through the spelling used on
            // the left-hand side of a stash assignment.  Keep that spelling
            // visible as well as its canonical alias target: otherwise a
            // preceding glob restore can make a following BEGIN-installed
            // pseudo-constant disappear to strict bareword lookup.
            globalPseudoConstants().put(key, scalar);
        }
    }

    public static void clearGlobalPseudoConstant(String key) {
        if (key == null) {
            return;
        }
        globalPseudoConstants().remove(key);
        String resolvedKey = resolveAliasedFqn(key);
        if (resolvedKey != key) {
            globalPseudoConstants().remove(resolvedKey);
        }
    }

    public static void clearGlobalPseudoConstantsForNamespace(String childPrefix) {
        if (childPrefix == null || childPrefix.isEmpty()) {
            return;
        }
        globalPseudoConstants().keySet().removeIf(key -> key.startsWith(childPrefix));
    }

    public static boolean hasGlobalPseudoConstant(String key) {
        if (key == null) {
            return false;
        }
        if (globalPseudoConstants().containsKey(key)) {
            return true;
        }
        String resolvedKey = resolveAliasedFqn(key);
        return resolvedKey != key && globalPseudoConstants().containsKey(resolvedKey);
    }

    public static RuntimeScalar getGlobalPseudoConstant(String key) {
        if (key == null) {
            return null;
        }
        RuntimeScalar scalar = globalPseudoConstants().get(key);
        if (scalar != null) {
            return scalar;
        }
        String resolvedKey = resolveAliasedFqn(key);
        return resolvedKey != key ? globalPseudoConstants().get(resolvedKey) : null;
    }

    /**
     * Retrieves a global array by its key, initializing it if necessary.
     *
     * @param key The key of the global array.
     * @return The RuntimeArray representing the global array.
     */
    public static RuntimeArray getGlobalArray(String key) {
        boolean isaArray = key.endsWith("::ISA");
        if (!stashAliases.isEmpty()) {
            String resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key) {
                RuntimeArray resolved = globalArrays.get(resolvedKey);
                if (resolved == null) {
                    resolved = new RuntimeArray();
                    markPackageGlobalRoot(resolved);
                    globalArrays.put(resolvedKey, resolved);
                    invalidatePackageRootSnapshot();
                }
                if (isaArray || resolvedKey.endsWith("::ISA")) {
                    resolved.markIsaArray();
                }
                return resolved;
            }
        }
        RuntimeArray var = globalArrays.get(key);
        if (var == null) {
            // Glob-aliased names (`*A = *B`) need to share the same RuntimeArray
            // so that auto-vivification under one name shows up under the other.
            // Fan-out to every alias-group sibling on first creation. We detect
            // membership by asking for the alias group itself instead of just
            // probing globAliases.containsKey(key) — for `*A = *B`, only one
            // direction is recorded in the map, so the canonical name (B) is
            // not a key but is still part of the group.
            java.util.List<String> aliasGroup = isInGlobAliasGroup(key) ? getGlobAliasGroup(key) : null;
            if (aliasGroup != null && aliasGroup.size() > 1) {
                for (String alias : aliasGroup) {
                    RuntimeArray existing = globalArrays.get(alias);
                    if (existing != null) {
                        var = existing;
                        break;
                    }
                }
                if (var == null) {
                    var = new RuntimeArray();
                }
                markPackageGlobalRoot(var);
                for (String alias : aliasGroup) {
                    globalArrays.putIfAbsent(alias, var);
                }
                if (!globalArrays.containsKey(key)) {
                    globalArrays.put(key, var);
                }
            } else {
                var = new RuntimeArray();
                markPackageGlobalRoot(var);
                globalArrays.put(key, var);
            }
            invalidatePackageRootSnapshot();
        } else {
            markPackageGlobalRoot(var);
        }
        if (isaArray) {
            var.markIsaArray();
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
        if (!stashAliases.isEmpty()) {
            String resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key && globalArrays.containsKey(resolvedKey)) {
                return true;
            }
        }
        return globalArrays.containsKey(key);
    }

    /**
     * Removes a global array by its key.
     *
     * @param key The key of the global array.
     * @return The removed RuntimeArray, or null if it did not exist.
     */
    public static RuntimeArray removeGlobalArray(String key) {
        RuntimeArray removed = globalArrays.remove(key);
        if (removed != null) {
            invalidatePackageRootSnapshot();
            if (key.endsWith("::ISA")) {
                InheritanceResolver.noteIsaMutation();
            }
        }
        return removed;
    }

    /**
     * Retrieves a global hash by its key, initializing it if necessary.
     *
     * @param key The key of the global hash.
     * @return The RuntimeHash representing the global hash.
     */
    public static RuntimeHash getGlobalHash(String key) {
        // Normalize stash lookups: in Perl, all packages are children of main::,
        // so %{main::F::} and %F:: refer to the same stash.
        // Strip a leading "main::" from stash keys (but keep "main::" itself).
        if (key.length() > 6 && key.endsWith("::") && key.startsWith("main::")) {
            key = key.substring(6);
        }
        // Stash alias resolution with fallback for non-stash-view hashes
        // (e.g. %Pkg::h). Stash-view keys (ending in "::") are already
        // unified at assignment time in RuntimeGlob.set(RuntimeGlob).
        if (!stashAliases.isEmpty() && !key.endsWith("::")) {
            String resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key) {
                RuntimeHash resolved = globalHashes.get(resolvedKey);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        RuntimeHash var = globalHashes.get(key);
        if (var == null) {
            boolean isStash = key.endsWith("::");
            // Glob-aliased names (`*A = *B`) need to share the same RuntimeHash
            // so that auto-vivification under one name shows up under the other.
            // Stash-view hashes are excluded — they have their own unification
            // path in RuntimeGlob.set(). See getGlobalArray() for the mirror
            // logic and the rationale for using isInGlobAliasGroup() (the map
            // only records one side of `*A = *B`, so the canonical name has
            // to be detected via the values).
            java.util.List<String> aliasGroup =
                    (!isStash && isInGlobAliasGroup(key)) ? getGlobAliasGroup(key) : null;
            if (aliasGroup != null && aliasGroup.size() > 1) {
                for (String alias : aliasGroup) {
                    RuntimeHash existing = globalHashes.get(alias);
                    if (existing != null) {
                        var = existing;
                        break;
                    }
                }
                if (var == null) {
                    var = createNamedGlobalHash(aliasGroup);
                }
                markPackageGlobalRoot(var);
                for (String alias : aliasGroup) {
                    globalHashes.putIfAbsent(alias, var);
                }
                if (!globalHashes.containsKey(key)) {
                    globalHashes.put(key, var);
                }
            } else {
                if (isStash) {
                    var = new RuntimeStash(key);
                } else {
                    var = createNamedGlobalHash(java.util.List.of(key));
                }
                // D-W6.18: mark as package-global so values stored here
                // get the storedInPackageGlobal flag (replaces class-name
                // heuristic in walker gate).
                markPackageGlobalRoot(var);
                globalHashes.put(key, var);
            }
            invalidatePackageRootSnapshot();
        } else if (!var.isPackageGlobalRoot || !var.isGlobalPackageHash) {
            // The first installation marks both the hash and its element-map
            // mutation hooks. Rewalking an already-rooted hash on every package
            // variable read is redundant and makes hot operations such as
            // list-context keys() pay registry bookkeeping before their empty
            // hash fast path.
            markPackageGlobalRoot(var);
        }
        // Merely mentioning *! can create an ordinary HASH slot before the
        // magic %! value is requested (notably in `*Y = *!`).  Upgrade that
        // pre-existing slot in place so aliases see Errno's populated hash.
        if (key.equals("main::!") && !(var.elements instanceof ErrnoHash)) {
            var.elements = new ErrnoHash();
        }
        return var;
    }

    private static RuntimeHash createNamedGlobalHash(java.util.List<String> names) {
        RuntimeHash hash = new RuntimeHash();
        if (names.contains("main::!")) {
            // %! is magic but remains absent from the stash until first
            // accessed. A glob alias of %! must materialize the same magic.
            hash.elements = new ErrnoHash();
        } else if (names.contains("main::+")) {
            hash.type = RuntimeHash.READONLY_HASH;
            hash.elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE);
        } else if (names.contains("main::-")) {
            hash.type = RuntimeHash.READONLY_HASH;
            hash.elements = new HashSpecialVariable(HashSpecialVariable.Id.CAPTURE_ALL);
        }
        return hash;
    }

    /**
     * Checks if a global hash exists.
     *
     * @param key The key of the global hash.
     * @return True if the global hash exists, false otherwise.
     */
    public static boolean existsGlobalHash(String key) {
        if (globalHashes.containsKey(key)) return true;
        // Normalize stash lookups: %{main::F::} and %F:: refer to the same stash.
        if (key.length() > 6 && key.endsWith("::") && key.startsWith("main::")) {
            return globalHashes.containsKey(key.substring(6));
        }
        return false;
    }

    /**
     * Removes a global hash by its key.
     *
     * @param key The key of the global hash.
     * @return The removed RuntimeHash, or null if it did not exist.
     */
    public static RuntimeHash removeGlobalHash(String key) {
        RuntimeHash removed = globalHashes.remove(key);
        if (removed != null) invalidatePackageRootSnapshot();
        return removed;
    }

    /**
     * Perl records a {@code BEGIN} typeglob entry in the compiling package's stash when
     * the legacy single-quote package separator is used with a <em>non-ASCII</em> package
     * segment (e.g. {@code $압Ƈ'var} under {@code use utf8}; see perl5_t/t/uni/package.t).
     * Pure-ASCII legacy names (e.g. {@code $main'a}, {@code $ABC'dyick}) do not get this
     * entry (perl5_t/t/comp/package.t).
     */
    public static void ensureStashBeginStubForLegacyPackageSeparator(String currentPackage) {
        if (currentPackage == null || currentPackage.isEmpty()) {
            return;
        }
        String key = currentPackage + "::BEGIN";
        if (globalCodeRefs.containsKey(key)) {
            return;
        }
        getGlobalCodeRef(key);
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
        // Stash aliasing: after `*Dst:: = *Src::`, look up under the resolved
        // target first. If no sub exists there, fall back to the raw key so
        // that compile-time-qualified references like `\&Pkg::foo` keep
        // working when the sub still lives at its original FQN.
        // This matches real Perl, which keeps each CV's CvGV pinned to the
        // package where it was compiled — the stash alias only redirects
        // new writes and runtime hash-view lookups.
        if (!stashAliases.isEmpty()) {
            String resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key) {
                RuntimeScalar resolvedPinned = pinnedCodeRefs().get(resolvedKey);
                if (resolvedPinned != null) {
                    return resolvedPinned;
                }
                RuntimeScalar resolvedVar = globalCodeRefs.get(resolvedKey);
                if (resolvedVar != null) {
                    pinnedCodeRefs().put(resolvedKey, resolvedVar);
                    return resolvedVar;
                }
            }
        }
        // First check if we have a pinned reference that survives stash deletion.
        // Runtime lookups emitted into already-compiled code use this path so
        // those call sites keep their original CV even after delete $Pkg::{sub}.
        RuntimeScalar pinned = pinnedCodeRefs().get(key);
        if (pinned != null) {
            return pinned;
        }

        RuntimeScalar var = globalCodeRefs.get(key);
        if (var == null) {
            var = createEmptyCodeRef(key);
            markPackageGlobalRoot(var);
            globalCodeRefs.put(key, var);
            invalidatePackageRootSnapshot();
        } else {
            markPackageGlobalRoot(var);
        }

        // Pin the RuntimeScalar so it survives stash deletion
        pinnedCodeRefs().put(key, var);

        return var;
    }

    private static RuntimeScalar createEmptyCodeRef(String key) {
        RuntimeScalar var = new RuntimeScalar();
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
        return var;
    }

    public static RuntimeScalar createPseudoConstantCodeRef(String key) {
        String resolvedKey = resolveAliasedFqn(key);
        RuntimeScalar installed = globalCodeRefs.get(resolvedKey);
        if (installed == null && !resolvedKey.equals(key)) {
            installed = globalCodeRefs.get(key);
        }
        if (installed != null
                && installed.type == RuntimeScalarType.CODE
                && installed.value instanceof RuntimeCode code
                && code.constantValue != null) {
            // A constant installed through the stash has a real, stable CV.
            // Reusing that CV makes separately compiled \&name references
            // compare by identity just as they do on Perl 5.  Synthesizing a
            // fresh RuntimeCode for every lookup made Moo mistake constants
            // that predated `use Moo` for newly installed methods.
            return new RuntimeScalar(code);
        }

        RuntimeScalar scalar = globalPseudoConstants().get(key);
        if (scalar == null) {
            if (resolvedKey != key) {
                scalar = globalPseudoConstants().get(resolvedKey);
                key = resolvedKey;
            }
        }
        if (scalar == null) {
            return null;
        }

        RuntimeCode runtimeCode = new RuntimeCode("", null);
        runtimeCode.packageName = "constant";
        runtimeCode.subName = "__ANON__";
        runtimeCode.constantValue = scalar.getList();
        return new RuntimeScalar(runtimeCode);
    }

    /**
     * Looks up a CODE slot for newly compiled/eval'd code. Unlike
     * getGlobalCodeRef(), this must not resurrect an old pinned CV after
     * delete $Pkg::{sub}; it should see the currently visible stash.
     */
    public static RuntimeScalar getGlobalCodeRefForFreshLookup(String key) {
        if (key == null) {
            return new RuntimeScalar();
        }
        if (!stashAliases.isEmpty()) {
            String resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key) {
                RuntimeScalar resolvedVar = globalCodeRefs.get(resolvedKey);
                if (resolvedVar != null) {
                    if (!deletedCodeRefPins().contains(resolvedKey)) {
                        pinnedCodeRefs().put(resolvedKey, resolvedVar);
                    }
                    return resolvedVar;
                }
            }
        }

        RuntimeScalar var = globalCodeRefs.get(key);
        if (var == null) {
            var = createEmptyCodeRef(key);
            markPackageGlobalRoot(var);
            globalCodeRefs.put(key, var);
            invalidatePackageRootSnapshot();
        } else {
            markPackageGlobalRoot(var);
        }
        if (!deletedCodeRefPins().contains(key)) {
            pinnedCodeRefs().put(key, var);
        }
        return var;
    }

    /**
     * Resolves a compiled direct named call against the current CODE slot.
     * A typeglob replacement must be visible to the next call, but deleting
     * the stash entry must not invalidate a call site compiled while the old
     * CV existed. Newly compiled code after deletion installs a fresh
     * undefined slot and therefore does not reach the old pin.
     */
    public static RuntimeScalar getGlobalCodeRefForDirectCall(String key) {
        if (key == null) {
            return new RuntimeScalar();
        }

        String resolvedKey = resolveAliasedFqn(key);
        RuntimeScalar current = globalCodeRefs.get(resolvedKey);
        if (current == null && !resolvedKey.equals(key)) {
            current = globalCodeRefs.get(key);
        }
        if (current != null) {
            return current;
        }

        RuntimeScalar pseudoConstant = createPseudoConstantCodeRef(resolvedKey);
        if (pseudoConstant == null && !resolvedKey.equals(key)) {
            pseudoConstant = createPseudoConstantCodeRef(key);
        }
        if (pseudoConstant != null) {
            return pseudoConstant;
        }

        RuntimeScalar pinned = pinnedCodeRefs().get(resolvedKey);
        if (pinned == null && !resolvedKey.equals(key)) {
            pinned = pinnedCodeRefs().get(key);
        }
        return pinned != null ? pinned : getGlobalCodeRefForFreshLookup(key);
    }

    /**
     * Resolve an interpreter direct-call site.  A visible replacement always
     * wins; after the stash entry is deleted, retain the most recently visible
     * CV for this already-compiled call site.
     */
    public static RuntimeScalar getGlobalCodeRefForDirectCall(String key, RuntimeScalar cached) {
        String resolvedKey = resolveAliasedFqn(key);
        RuntimeScalar current = globalCodeRefs.get(resolvedKey);
        if (current == null && !resolvedKey.equals(key)) {
            current = globalCodeRefs.get(key);
        }
        if (current != null) {
            cached.type = current.type;
            cached.value = current.value;
            return cached;
        }
        if (cached.type == RuntimeScalarType.CODE
                && cached.value instanceof RuntimeCode code
                && code.defined()) {
            return cached;
        }
        return getGlobalCodeRefForDirectCall(key);
    }

    /**
     * Undefines the CODE slot currently visible through a package stash.
     * Unlike compiled direct-call lookup, this operation must not fall back to
     * an old pinned CV or create a slot after the stash entry was deleted.
     */
    public static void undefineVisibleGlobalCodeRef(String key) {
        String resolvedKey = resolveAliasedFqn(key);
        RuntimeScalar current = globalCodeRefs.get(resolvedKey);
        if (current == null && !resolvedKey.equals(key)) {
            current = globalCodeRefs.get(key);
        }
        if (current != null) {
            current.undefine();
        }
    }

    /**
     * Retrieves a global code reference for the purpose of DEFINING code.
     * Unlike getGlobalCodeRef(), this also ensures the entry is visible in
     * globalCodeRefs for method resolution via can() and the inheritance hierarchy.
     * Use this when assigning code to a glob (e.g., *Foo::bar = sub { ... }).
     *
     * @param key The key of the global code reference.
     * @return The RuntimeScalar representing the global code reference.
     */
    public static RuntimeScalar defineGlobalCodeRef(String key) {
        // For defines, always resolve through stash aliases: `*Dst:: = *Src::`
        // followed by `sub Dst::foo {}` should install the sub in Src::foo.
        String resolvedKey = resolveAliasedFqn(key);
        RuntimeScalar ref = globalCodeRefs.get(resolvedKey);
        if (ref == null) {
            RuntimeScalar pinned = pinnedCodeRefs().get(resolvedKey);
            if (pinned != null
                    && pinned.type == RuntimeScalarType.CODE
                    && pinned.value instanceof RuntimeCode pinnedCode
                    && !pinnedCode.defined()) {
                // A parser/compiler lookup may have created an undefined CV
                // placeholder before a later compile-time import installs the
                // real sub. Fill that placeholder so already-compiled call
                // sites keep the CV even if a following `no Module` deletes
                // the visible stash entry before runtime.
                ref = pinned;
                markPackageGlobalRoot(ref);
                globalCodeRefs.put(resolvedKey, ref);
                invalidatePackageRootSnapshot();
            } else {
                ref = getGlobalCodeRefForFreshLookup(resolvedKey);
            }
        }
        // Ensure it's in globalCodeRefs so method resolution finds it
        if (!globalCodeRefs.containsKey(resolvedKey)) {
            markPackageGlobalRoot(ref);
            globalCodeRefs.put(resolvedKey, ref);
            invalidatePackageRootSnapshot();
        } else {
            markPackageGlobalRoot(ref);
        }
        deletedCodeRefPins().remove(resolvedKey);
        pinnedCodeRefs().put(resolvedKey, ref);
        return ref;
    }

    public static int registerCompiledCodeRef(RuntimeScalar ref) {
        return globalState().registerCompiledCodeRef(ref);
    }

    public static RuntimeScalar getCompiledCodeRef(int id) {
        RuntimeScalar ref = globalState().getCompiledCodeRef(id);
        return ref != null ? ref : new RuntimeScalar();
    }

    /**
     * Checks if a global code reference exists.
     *
     * @param key The key of the global code reference.
     * @return True if the global code reference exists, false otherwise.
     */
    public static boolean existsGlobalCodeRef(String key) {
        if (!stashAliases.isEmpty()) {
            String resolvedKey = resolveAliasedFqn(key);
            if (resolvedKey != key && globalCodeRefs.containsKey(resolvedKey)) {
                return true;
            }
        }
        return globalCodeRefs.containsKey(key);
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
        if (pinnedCodeRefs().containsKey(key)) {
            pinnedCodeRefs().put(key, codeRef);
        }
    }

    static void enterLocalizedCodeRef(String key, RuntimeScalar displacedCodeRef) {
        localizedCodeRefDepth().merge(key, 1, Integer::sum);
        if (displacedCodeRef != null) {
            displacedLocalizedCodeRefs().put(displacedCodeRef, key);
        }
    }

    static void exitLocalizedCodeRef(String key, RuntimeScalar displacedCodeRef) {
        if (displacedCodeRef != null) {
            displacedLocalizedCodeRefs().remove(displacedCodeRef);
        }
        Integer depth = localizedCodeRefDepth().get(key);
        if (depth == null) {
            return;
        }
        if (depth <= 1) {
            localizedCodeRefDepth().remove(key);
        } else {
            localizedCodeRefDepth().put(key, depth - 1);
        }
    }

    public static RuntimeScalar getLocalizedCodeRefForDirectCall(String key, RuntimeScalar fallback) {
        if ((key == null || key.isEmpty()) && fallback != null) {
            key = displacedLocalizedCodeRefs().get(fallback);
        }
        if (key == null || key.isEmpty() || !localizedCodeRefDepth().containsKey(key)) {
            return fallback;
        }
        RuntimeScalar localized = globalCodeRefs.get(key);
        return localized != null ? localized : fallback;
    }

    /**
     * Checks if a global code reference exists AND is defined (has a real subroutine),
     * without auto-creating an entry.
     *
     * @param key The key of the global code reference.
     * @return True if the code reference exists and is defined, false otherwise.
     */
    public static boolean isGlobalCodeRefDefined(String key) {
        RuntimeScalar var = globalCodeRefs.get(key);
        if (var != null && var.type == RuntimeScalarType.CODE && var.value instanceof RuntimeCode runtimeCode) {
            return runtimeCode.defined();
        }
        return false;
    }

    private static boolean codeSlotExists(RuntimeScalar var) {
        return var != null
                && var.type == RuntimeScalarType.CODE
                && var.value instanceof RuntimeCode runtimeCode
                && (runtimeCode.defined() || runtimeCode.isDeclared);
    }

    public static RuntimeScalar existsGlobalCodeRefAsScalar(String key) {
        RuntimeScalar var = globalCodeRefs.get(key);
        return codeSlotExists(var) ? scalarTrue : scalarFalse;
    }

    public static RuntimeScalar existsGlobalCodeRefAsScalar(RuntimeScalar key) {
        // Handle GLOB type: extract CODE slot from the glob
        if (key.type == RuntimeScalarType.GLOB && key.value instanceof RuntimeGlob glob) {
            return existsGlobalCodeRefAsScalar(glob.globName);
        }
        // Handle RuntimeCode objects by extracting the subroutine name
        if (key.type == RuntimeScalarType.CODE && key.value instanceof RuntimeCode runtimeCode) {
            return (runtimeCode.defined() || runtimeCode.isDeclared) ? scalarTrue : scalarFalse;
        }
        return existsGlobalCodeRefAsScalar(key.toString());
    }

    public static RuntimeScalar existsGlobalCodeRefAsScalar(RuntimeScalar key, String packageName) {
        // Handle values that are already CODE/GLOB scalars before falling back
        // to package-relative symbolic name lookup.
        if (key.type == RuntimeScalarType.GLOB && key.value instanceof RuntimeGlob glob) {
            return existsGlobalCodeRefAsScalar(glob.globName);
        }
        if (key.type == RuntimeScalarType.CODE && key.value instanceof RuntimeCode runtimeCode) {
            return (runtimeCode.defined() || runtimeCode.isDeclared) ? scalarTrue : scalarFalse;
        }

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

        RuntimeScalar var = globalCodeRefs.get(key);
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
        // Handle values that are already CODE/GLOB scalars before falling back
        // to package-relative symbolic name lookup. This covers `defined &$coderef`,
        // where the parser passes the CODE scalar rather than a symbolic name.
        if (key.type == RuntimeScalarType.GLOB && key.value instanceof RuntimeGlob glob) {
            return definedGlobalCodeRefAsScalar(glob.globName);
        }
        if (key.type == RuntimeScalarType.CODE && key.value instanceof RuntimeCode runtimeCode) {
            return runtimeCode.defined() ? scalarTrue : scalarFalse;
        }

        // Use proper package name resolution like createCodeReference
        String name = NameNormalizer.normalizeVariableName(key.toString(), packageName);

        // Built-ins are ONLY accessible via CORE:: prefix, not from main:: or other packages
        // So just delegate to the main method which checks for CORE:: prefix
        return definedGlobalCodeRefAsScalar(name);
    }


    public static RuntimeScalar removeGlobalCodeRefForStashDelete(String key) {
        RuntimeScalar deleted = globalCodeRefs.remove(key);
        if (deleted != null || pinnedCodeRefs().containsKey(key)) {
            deletedCodeRefPins().add(key);
            clearPackageCache();
            invalidatePackageRootSnapshot();
        }
        // Decrement stashRefCount on the removed CODE ref
        if (deleted != null && deleted.value instanceof RuntimeCode removedCode) {
            if (removedCode.stashRefCount > 0) {
                removedCode.stashRefCount--;
            }
        }
        return deleted;
    }

    public static RuntimeScalar deleteGlobalCodeRefAsScalar(String key) {
        RuntimeScalar deleted = removeGlobalCodeRefForStashDelete(key);
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
        pinnedCodeRefs().keySet().removeIf(k -> k.startsWith(prefix));
        deletedCodeRefPins().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Clears the package existence cache.
     * Should be called when new packages are loaded or code refs are modified.
     */
    public static void clearPackageCache() {
        packageExistsCache.clear();
    }

    /**
     * Checks if a Perl package is loaded by scanning for any methods in its namespace
     *
     * @param className The name of the package/class to check
     * @return true if any methods exist in the class namespace
     */
    public static boolean isPackageLoaded(String className) {
        // Check cache first
        Boolean cached = packageExistsCache.get(className);
        if (cached != null) {
            return cached;
        }

        // Ensure we have the :: suffix for the prefix check
        final String prefix = className.endsWith("::") ? className : className + "::";

        // Check if any code references exist directly in this class (not in sub-packages).
        // A key like "Foo::Bar::baz" belongs to package "Foo::Bar", not "Foo".
        // After stripping the prefix, the remaining part must NOT contain "::"
        // to be a direct member of this package.
        boolean exists = globalCodeRefs.keySet().stream()
                .anyMatch(key -> key.startsWith(prefix) && !key.substring(prefix.length()).contains("::"));

        // Cache the result
        packageExistsCache.put(className, exists);
        return exists;
    }

    /**
     * Runtime check for typed lexical declarations (my TYPE $var).
     * Throws a compile-time-like error matching Perl's "No such class TYPE"
     * if the package is not loaded at the point of execution.
     *
     * @param className The type annotation class name
     */
    public static void checkClassExists(String className) {
        if (!isPackageLoaded(className)) {
            throw new RuntimeException("No such class " + className);
        }
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
        if (fullName == null) return null;
        int lastDoubleColon = fullName.lastIndexOf("::");
        if (lastDoubleColon >= 0) {
            String pkgPart = fullName.substring(0, lastDoubleColon + 2);
            RuntimeHash stashHash = globalHashes.get(pkgPart);
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
        RuntimeGlob standardGlob = currentRuntimeStandardIOGlob(key);
        if (standardGlob != null) {
            return standardGlob;
        }
        // A stash glob is itself the lvalue that owns an alias. Resolving it
        // through the currently aliased hash would make a second assignment
        // (`*Alias:: = *Other::`) replace the old source stash instead.
        boolean stashGlob = key.endsWith("::") && !key.endsWith(":::");
        String resolvedKey = stashGlob ? key : resolveStashHashRedirect(key);
        RuntimeGlob glob = globalIORefs.get(resolvedKey);
        if (glob == null) {
            glob = new RuntimeGlob(resolvedKey);
            globalIORefs.put(resolvedKey, glob);
        }
        return glob;
    }

    /**
     * Vivifies the IO slot for a bareword filehandle seen at compile time.
     * Generic glob creation must not define {@code *name{IO}}, but Perl creates
     * a PVIO object when it parses a bareword filehandle argument such as
     * {@code open FH, ...}. BEGIN blocks can observe that placeholder before
     * the runtime open call installs the real handle.
     */
    public static RuntimeGlob vivifyGlobalIO(String key) {
        String resolvedKey = resolveStashHashRedirect(key);
        RuntimeGlob glob = getGlobalIO(key);
        markStashEntryVisible(resolvedKey);
        if (glob.IO == null || glob.IO.type == RuntimeScalarType.UNDEF || glob.IO.value == null) {
            glob.setIO(new RuntimeIO());
        }
        return glob;
    }

    /**
     * Peek at a glob entry without vivifying it. Returns null if no glob has
     * been registered under this name. Used by anon-sub naming lookups
     * (see dev/modules/anon_sub_naming.md) to read *PKG::__ANON__'s
     * nameOverride without creating an empty glob as a side effect.
     */
    public static RuntimeGlob peekGlobalIO(String key) {
        RuntimeGlob standardGlob = currentRuntimeStandardIOGlob(key);
        if (standardGlob != null) {
            return standardGlob;
        }
        String resolvedKey = resolveStashHashRedirect(key);
        return globalIORefs.get(resolvedKey);
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
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null && runtime.isStandardIOGlobVisible(key)) return true;
        if (isVisibleGlobalIORef(key)) return true;
        // Follow stash hash redirects so a bareword-handle existence probe in
        // an aliased package sees IO placeholders that got redirected at
        // auto-viv time. Without this, the parser path that tries
        // parseBarewordHandle("DATA") in package Dst after `*Dst:: = *Src::`
        // misses the handle and falls back to the hard-coded main::DATA path,
        // which then reads from an empty placeholder.
        String resolved = resolveStashHashRedirect(key);
        if (!resolved.equals(key) && isVisibleGlobalIORef(resolved)) return true;
        return false;
    }

    /**
     * Checks if a global IO reference exists AND has an actual IO handle (not just an empty glob),
     * without auto-creating an entry.
     *
     * @param key The key of the global IO reference.
     * @return True if the IO reference exists and has a real IO handle, false otherwise.
     */
    public static boolean isGlobalIODefined(String key) {
        RuntimeGlob glob = currentRuntimeStandardIOGlob(key);
        if (glob == null) {
            glob = globalIORefs.get(key);
        }
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
        RuntimeGlob standardGlob = currentRuntimeStandardIOGlob(key);
        return standardGlob != null ? standardGlob : globalIORefs.get(key);
    }

    /** Replace an IO glob without leaking standard-handle localization across runtimes. */
    static void replaceGlobalIO(String key, RuntimeGlob glob) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null && runtime.standardIOGlob(key) != null) {
            runtime.replaceStandardIOGlob(key, glob);
            runtime.showStandardIOGlob(key);
        } else {
            globalIORefs.put(key, glob);
        }
    }

    private static RuntimeGlob currentRuntimeStandardIOGlob(String key) {
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        return runtime != null ? runtime.standardIOGlob(key) : null;
    }

    static void removeGlobalIORefsForNamespace(String prefix) {
        globalIORefs.keySet().removeIf(key -> key.startsWith(prefix));
        PerlRuntime runtime = PerlRuntime.currentOrNull();
        if (runtime != null) {
            runtime.hideStandardIOGlobsWithPrefix(prefix);
        }
        invalidateStashEnumerationCache();
    }

    /**
     * Finds a global IO glob whose stash key ends with {@code "::"+bareName} and whose IO
     * slot holds an active {@link RuntimeIO}. Used when a bareword handle was compiled to a
     * plain string and package-qualified lookup missed the real glob (see GAAS/MD5
     * {@code addfile(F)}). Prefers non-{@code main::} keys, then lexicographic key order.
     */
    public static RuntimeGlob pickGlobWithOpenIoForSimpleHandleName(String bareName) {
        if (bareName == null || bareName.contains("::") || !bareName.matches("[A-Za-z_]\\w*")) {
            return null;
        }
        String suffix = "::" + bareName;
        ArrayList<String> keys = new ArrayList<>();
        synchronized (globalIORefs) {
            for (Map.Entry<String, RuntimeGlob> e : globalIORefs.entrySet()) {
                String k = e.getKey();
                if (!k.endsWith(suffix)) {
                    continue;
                }
                RuntimeGlob g = e.getValue();
                if (g == null) {
                    continue;
                }
                RuntimeScalar ios = g.getIO();
                if (ios != null && ios.getRuntimeIO() != null) {
                    keys.add(k);
                }
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        keys.sort((a, b) -> {
            boolean am = a.startsWith("main::");
            boolean bm = b.startsWith("main::");
            if (am != bm) {
                return am ? 1 : -1;
            }
            return a.compareTo(b);
        });
        return globalIORefs.get(keys.get(0));
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
        
        // Check if glob was explicitly assigned
        if (globalGlobs.getOrDefault(varName, false)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check scalar slot - slot existence makes glob defined (not value definedness)
        // In Perl, `defined *FOO` is true if $FOO exists, even if $FOO is undef
        if (globalVariables.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check array slot - exists = defined (even if empty)
        if (globalArrays.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check hash slot - exists = defined (even if empty)
        if (globalHashes.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check code slot - slot existence makes glob defined
        if (globalCodeRefs.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check IO slot (via globalIORefs)
        if (globalIORefs.containsKey(varName)) {
            return RuntimeScalarCache.scalarTrue;
        }
        
        // Check format slot
        if (globalFormatRefs.containsKey(varName)) {
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
        RuntimeFormat format = globalFormatRefs.get(key);
        if (format == null) {
            format = new RuntimeFormat(key);
            globalFormatRefs.put(key, format);
            markStashEntryVisible(key);
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
        globalFormatRefs.put(key, format);
        markStashEntryVisible(key);
    }

    /**
     * Checks if a global format reference exists.
     *
     * @param key The key of the global format reference.
     * @return True if the global format reference exists, false otherwise.
     */
    public static boolean existsGlobalFormat(String key) {
        return globalFormatRefs.containsKey(key);
    }

    public static RuntimeScalar existsGlobalFormatAsScalar(String key) {
        return globalFormatRefs.containsKey(key) ? scalarTrue : scalarFalse;
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
        RuntimeFormat format = globalFormatRefs.get(key);
        return format != null && format.isFormatDefined();
    }

    public static RuntimeScalar definedGlobalFormatAsScalar(String key) {
        return globalFormatRefs.containsKey(key) ?
                (globalFormatRefs.get(key).isFormatDefined() ? scalarTrue : scalarFalse) : scalarFalse;
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
        // Reset scalar variables
        for (Map.Entry<String, RuntimeScalar> entry : globalVariables.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Reset to undef instead of removing to maintain reference integrity
                entry.getValue().set(RuntimeScalar.undef());
            }
        }

        // Reset array variables
        for (Map.Entry<String, RuntimeArray> entry : globalArrays.entrySet()) {
            String key = entry.getKey();

            if (key.startsWith(currentPackage) && shouldResetVariable(key, currentPackage, resetChars)) {
                // Clear the array
                entry.getValue().elements.clear();
            }
        }

        // Reset hash variables
        for (Map.Entry<String, RuntimeHash> entry : globalHashes.entrySet()) {
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
        for (Map.Entry<String, RuntimeArray> entry : globalArrays.entrySet()) {
            if (entry.getKey().endsWith("::ISA")) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}

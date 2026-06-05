package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * The HashSpecialVariable class mimics the behavior of Perl's special variables
 * %+, %-, and the "stash".
 * <p>
 * In Perl, %+ and %- are used to access named capturing groups from regular expressions,
 * while the stash represents a package's symbol table, containing all the typeglobs
 * for that package.
 * <p>
 * This class extends AbstractMap to provide a map-like interface for accessing these
 * special variables. Depending on the mode of operation, it can either provide access
 * to named capturing groups or to the stash.
 */
public class HashSpecialVariable extends AbstractMap<String, RuntimeScalar> {

    // Mode of operation for this special variable
    private HashSpecialVariable.Id mode = null;

    // Namespace for the stash, if any
    private String namespace = null;
    private static long stashEntryCacheVersion = -1;
    private static final Map<String, List<StashEntryName>> stashEntryCache = new HashMap<>();

    private static final class StashEntryName {
        final String entryKey;
        final String globName;

        StashEntryName(String entryKey, String globName) {
            this.entryKey = entryKey;
            this.globName = globName;
        }
    }

    /**
     * Constructs a HashSpecialVariable for the given Matcher.
     *
     * @param mode The mode of operation, either CAPTURE_ALL or CAPTURE.
     */
    public HashSpecialVariable(HashSpecialVariable.Id mode) {
        this.mode = mode;
    }

    /**
     * Constructs a HashSpecialVariable for the given Stash.
     *
     * @param mode      The mode of operation, which should be STASH.
     * @param namespace The namespace to be used for the stash.
     */
    public HashSpecialVariable(HashSpecialVariable.Id mode, String namespace) {
        this.mode = mode;
        this.namespace = namespace;
    }

    /**
     * Retrieves the stash for a given namespace. The stash is a hash that represents
     * a package's symbol table, containing all the typeglobs for that package.
     *
     * @param namespace The namespace for which the stash is to be retrieved.
     * @return A RuntimeHash object representing the stash.
     */
    public static RuntimeHash getStash(String namespace) {
        return GlobalVariable.getGlobalHash(namespace);
    }

    /**
     * Returns a set view of the mappings contained in this map. The set is dynamically
     * constructed based on the current state of the Matcher or the global variables,
     * depending on the mode of operation.
     *
     * @return A set of map entries representing the current state of the special variable.
     */
    @Override
    public Set<Entry<String, RuntimeScalar>> entrySet() {
        Set<Entry<String, RuntimeScalar>> entries = new HashSet<>();
        if (this.mode == Id.CAPTURE_ALL || this.mode == Id.CAPTURE) {
            Map<String, List<String>> namedCaptures = RuntimeRegex.lastNamedCaptureGroups;
            if (namedCaptures != null) {
                for (Map.Entry<String, List<String>> e : namedCaptures.entrySet()) {
                    if (this.mode == Id.CAPTURE_ALL) {
                        entries.add(new SimpleEntry<>(e.getKey(), captureAllArrayRef(e.getValue())));
                    } else {
                        RuntimeScalar matched = firstDefinedCapture(e.getValue());
                        if (matched.getDefinedBoolean()) {
                            entries.add(new SimpleEntry<>(e.getKey(), matched));
                        }
                    }
                }
            }
        } else if (this.mode == Id.STASH) {
            // System.out.println("EntrySet ");
            for (StashEntryName entry : cachedStashEntries(namespace)) {
                entries.add(new SimpleEntry<>(entry.entryKey, new RuntimeStashEntry(entry.globName, true)));
            }
        }
        return entries;
    }

    /**
     * Retrieves the value associated with the specified key in this map. The behavior
     * of this method depends on the mode of operation.
     * <p>
     * In CAPTURE_ALL or CAPTURE mode, it retrieves the value of the named capturing
     * group from the Matcher. In STASH mode, it checks if the key exists in the global
     * variables and returns a corresponding RuntimeScalar.
     *
     * @param key The key whose associated value is to be returned.
     * @return The value associated with the specified key, or scalarUndef if not found.
     */
    @Override
    public RuntimeScalar get(Object key) {
        if (this.mode == Id.CAPTURE_ALL || this.mode == Id.CAPTURE) {
            Map<String, List<String>> namedCaptures = RuntimeRegex.lastNamedCaptureGroups;
            if (namedCaptures != null && key instanceof String name) {
                List<String> captures = namedCaptures.get(name);
                if (captures == null) return scalarUndef;
                if (this.mode == Id.CAPTURE_ALL) {
                    return captureAllArrayRef(captures);
                } else {
                    return firstDefinedCapture(captures);
                }
            }
        } else if (this.mode == Id.STASH) {
            String prefix = stashGlobNameFor(String.valueOf(key));
            // System.out.println("Get Key " + prefix);
            if (containsNamespace(GlobalVariable.globalVariables, prefix) ||
                    containsNamespace(GlobalVariable.globalArrays, prefix) ||
                    containsNamespace(GlobalVariable.globalHashes, prefix) ||
                    containsNamespace(GlobalVariable.globalCodeRefs, prefix) ||
                    GlobalVariable.containsVisibleGlobalIORefWithPrefix(prefix) ||
                    containsNamespace(GlobalVariable.globalFormatRefs, prefix)) {
                return new RuntimeStashEntry(prefix, true);
            }
            return new RuntimeStashEntry(prefix, false);
        }
        return scalarUndef;
    }

    @Override
    public boolean containsKey(Object key) {
        if (this.mode == Id.CAPTURE_ALL) {
            // For %-, all named groups exist (even non-participating ones)
            Map<String, List<String>> namedCaptures = RuntimeRegex.lastNamedCaptureGroups;
            return namedCaptures != null && key instanceof String name && namedCaptures.containsKey(name);
        }
        if (this.mode == Id.CAPTURE) {
            // For %+, only groups that actually captured
            Map<String, List<String>> namedCaptures = RuntimeRegex.lastNamedCaptureGroups;
            if (namedCaptures != null && key instanceof String name) {
                List<String> captures = namedCaptures.get(name);
                return captures != null && captures.stream().anyMatch(v -> v != null);
            }
            return false;
        }
        if (this.mode == Id.STASH) {
            if (!(key instanceof String name)) return false;
            return stashContainsEntry(name);
        }
        return super.containsKey(key);
    }

    @Override
    public Set<String> keySet() {
        if (this.mode != Id.STASH) {
            return super.keySet();
        }

        Set<String> keys = new HashSet<>();
        for (StashEntryName entry : cachedStashEntries(namespace)) {
            keys.add(entry.entryKey);
        }
        return keys;
    }

    private static List<StashEntryName> cachedStashEntries(String namespace) {
        long version = GlobalVariable.stashEnumerationVersion();
        if (stashEntryCacheVersion != version) {
            stashEntryCache.clear();
            stashEntryCacheVersion = version;
        }
        List<StashEntryName> cached = stashEntryCache.get(namespace);
        if (cached != null) {
            return cached;
        }
        List<StashEntryName> computed = computeStashEntries(namespace);
        stashEntryCache.put(namespace, computed);
        return computed;
    }

    private static List<StashEntryName> computeStashEntries(String namespace) {
        Set<String> uniqueKeys = new HashSet<>();
        List<StashEntryName> entries = new ArrayList<>();
        addCachedStashEntriesFromGlobalKeys(namespace, GlobalVariable.globalVariables.keySet(), uniqueKeys, entries);
        addCachedStashEntriesFromGlobalKeys(namespace, GlobalVariable.globalArrays.keySet(), uniqueKeys, entries);
        addCachedStashEntriesFromGlobalKeys(namespace, GlobalVariable.globalHashes.keySet(), uniqueKeys, entries);
        addCachedStashEntriesFromGlobalKeys(namespace, GlobalVariable.globalCodeRefs.keySet(), uniqueKeys, entries);
        for (String key : GlobalVariable.globalIORefs.keySet()) {
            if (!GlobalVariable.isIORefHiddenAfterStashDelete(key)) {
                addCachedStashEntryFromGlobalKey(namespace, key, uniqueKeys, entries);
            }
        }
        addCachedStashEntriesFromGlobalKeys(namespace, GlobalVariable.globalFormatRefs.keySet(), uniqueKeys, entries);
        return entries;
    }

    private static void addCachedStashEntriesFromGlobalKeys(String namespace,
                                                            Iterable<String> globalKeys,
                                                            Set<String> uniqueKeys,
                                                            List<StashEntryName> entries) {
        for (String key : globalKeys) {
            addCachedStashEntryFromGlobalKey(namespace, key, uniqueKeys, entries);
        }
    }

    private static void addCachedStashEntryFromGlobalKey(String namespace,
                                                         String key,
                                                         Set<String> uniqueKeys,
                                                         List<StashEntryName> entries) {
        boolean isMainStash = "main::".equals(namespace);
        String entryKey = stashEntryKeyFromGlobalKey(namespace, key, isMainStash);
        if (entryKey == null || entryKey.isEmpty() || entryKey.equals("a") || entryKey.equals("b")) {
            return;
        }
        if (uniqueKeys.add(entryKey)) {
            entries.add(new StashEntryName(entryKey, stashGlobNameForEntryKey(namespace, entryKey, key)));
        }
    }

    private static String stashEntryKeyFromGlobalKey(String namespace, String key, boolean isMainStash) {
        if (key.startsWith(namespace)) {
            String remainingKey = key.substring(namespace.length());
            int nextSeparatorIndex = remainingKey.indexOf("::");
            if (nextSeparatorIndex == -1) {
                return remainingKey;
            }
            // Stash keys for nested packages include the trailing "::"
            // (e.g. "Foo::" not "Foo") - this is how Perl indicates sub-packages.
            return remainingKey.substring(0, nextSeparatorIndex + 2);
        }
        if (isMainStash) {
            // For %main::, also include top-level packages that aren't explicitly
            // prefixed with "main::". In Perl, $Foo::x and $main::Foo::x are the same.
            // Variables in top-level packages are stored as "Foo::x", not "main::Foo::x".
            int separatorIndex = key.indexOf("::");
            if (separatorIndex > 0) {
                return key.substring(0, separatorIndex + 2);
            }
        }
        return null;
    }

    private static String stashGlobNameForEntryKey(String namespace, String entryKey, String originalGlobalKey) {
        if ("main::".equals(namespace) && originalGlobalKey.startsWith(entryKey)) {
            return entryKey;
        }
        return namespace + entryKey;
    }

    private static RuntimeScalar captureAllArrayRef(List<String> captures) {
        RuntimeArray arr = new RuntimeArray();
        for (String v : captures) {
            arr.push(v != null ? new RuntimeScalar(v) : new RuntimeScalar());
        }
        return arr.createReference();
    }

    private static RuntimeScalar firstDefinedCapture(List<String> captures) {
        for (String v : captures) {
            if (v != null) {
                return new RuntimeScalar(v);
            }
        }
        return scalarUndef;
    }

    @Override
    public RuntimeScalar put(String key, RuntimeScalar value) {
        if (this.mode == Id.STASH) {
            String fullKey = namespace + key;
            // System.out.println("Get Key " + fullKey + " value " + value);

            RuntimeScalar oldValue = new RuntimeStashEntry(fullKey, true);
            if (value.getDefinedBoolean()) {
                oldValue.set(value);
            }

            // Any stash mutation can affect method lookup; clear method resolution caches.
            InheritanceResolver.invalidateCache();
            GlobalVariable.invalidatePackageRootSnapshot();

            return oldValue;
        }
        return scalarUndef;
    }

    @Override
    public RuntimeScalar remove(Object key) {
        if (this.mode == Id.STASH) {
            String fullKey = namespace + key;

            // Check if the glob exists
            boolean exists = containsNamespace(GlobalVariable.globalVariables, fullKey) ||
                    containsNamespace(GlobalVariable.globalArrays, fullKey) ||
                    containsNamespace(GlobalVariable.globalHashes, fullKey) ||
                    containsNamespace(GlobalVariable.globalCodeRefs, fullKey) ||
                    GlobalVariable.containsVisibleGlobalIORefWithPrefix(fullKey) ||
                    containsNamespace(GlobalVariable.globalFormatRefs, fullKey);

            if (!exists) {
                return scalarUndef;
            }

            // Remove only from the visible stash, not from pinned code refs:
            // compiled call sites keep their CV, while future lookups must see
            // the deletion and create an undefined slot.
            RuntimeScalar code = GlobalVariable.removeGlobalCodeRefForStashDelete(fullKey);
            GlobalVariable.clearGlobalPseudoConstant(fullKey);
            RuntimeScalar scalar = GlobalVariable.globalVariables.remove(fullKey);
            RuntimeArray array = GlobalVariable.globalArrays.remove(fullKey);
            RuntimeHash hash = GlobalVariable.globalHashes.remove(fullKey);
            RuntimeGlob io = GlobalVariable.globalIORefs.get(fullKey);
            if (io != null) {
                GlobalVariable.hideIORefAfterStashDelete(fullKey);
            }
            RuntimeScalar format = GlobalVariable.globalFormatRefs.remove(fullKey);
            GlobalVariable.invalidatePackageRootSnapshot();

            // Any stash mutation can affect method lookup; clear method resolution caches.
            InheritanceResolver.invalidateCache();

            // Return a detached glob with all saved slots.
            // Matches RuntimeStash.deleteGlob() behavior: the returned glob lets callers
            // access old slot values (e.g., *{$old}{SCALAR} in namespace::clean).
            return RuntimeGlob.createDetachedWithSlots(scalar, array, hash, io, code);
        }
        return scalarUndef;
    }

    @Override
    public void clear() {
        if (this.mode == Id.STASH) {
            String prefix = namespace;

            GlobalVariable.clearGlobalPseudoConstantsForNamespace(prefix);
            GlobalVariable.globalVariables.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalArrays.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalHashes.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalCodeRefs.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalIORefs.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalFormatRefs.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.invalidateStashEnumerationCache();
            GlobalVariable.clearHiddenIORefsForNamespace(prefix);
            GlobalVariable.invalidatePackageRootSnapshot();

            InheritanceResolver.invalidateCache();
            GlobalVariable.clearPackageCache();
            return;
        }
        super.clear();
    }

    /**
     * Checks if any key in the map starts with the given namespace followed by the typeglob name.
     * This method is used in STASH mode to determine if a particular typeglob exists in the
     * global variables.
     *
     * @param map    The map to check.
     * @param prefix The typeglob name to match.
     * @return True if a matching key is found, false otherwise.
     */
    private boolean containsNamespace(Map<String, ?> map, String prefix) {
        for (String key : map.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean stashContainsEntry(String name) {
        String prefix = namespace + name;
        if (containsAnySlotWithPrefix(prefix)) {
            return true;
        }
        // Top-level packages are represented in %main:: as "Foo::", but
        // their symbols are stored under "Foo::bar" rather than
        // "main::Foo::bar". Enumeration already exposes those packages; direct
        // exists/get lookups need the same fallback for code like Carp::_fetch_sub.
        return "main::".equals(namespace)
                && name.endsWith("::")
                && containsAnySlotWithPrefix(name);
    }

    private String stashGlobNameFor(String name) {
        String prefix = namespace + name;
        if (containsAnySlotWithPrefix(prefix)) {
            return prefix;
        }
        if ("main::".equals(namespace)
                && name.endsWith("::")
                && containsAnySlotWithPrefix(name)) {
            return name;
        }
        return prefix;
    }

    private boolean containsAnySlotWithPrefix(String prefix) {
        return containsNamespace(GlobalVariable.globalVariables, prefix) ||
                containsNamespace(GlobalVariable.globalArrays, prefix) ||
                containsNamespace(GlobalVariable.globalHashes, prefix) ||
                containsNamespace(GlobalVariable.globalCodeRefs, prefix) ||
                GlobalVariable.containsVisibleGlobalIORefWithPrefix(prefix) ||
                containsNamespace(GlobalVariable.globalFormatRefs, prefix);
    }

    // Enum to represent the mode of operation for HashSpecialVariable
    public enum Id {
        CAPTURE_ALL,  // Represents Perl %- for accessing all capturing groups
        CAPTURE,      // Represents Perl %+ for accessing named capturing groups
        STASH         // Represents the Perl stash for accessing the symbol table
    }
}

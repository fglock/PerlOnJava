package org.perlonjava.runtime;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

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
        RuntimeHash stash = new RuntimeHash();
        stash.elements = new HashSpecialVariable(HashSpecialVariable.Id.STASH, namespace);
        return stash;
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
            Matcher matcher = RuntimeRegex.globalMatcher;
            if (matcher != null) {
                Map<String, Integer> namedGroups = matcher.pattern().namedGroups();
                for (String name : namedGroups.keySet()) {
                    String matchedValue = matcher.group(name);
                    if (matchedValue != null) {
                        entries.add(new SimpleEntry<>(name, new RuntimeScalar(matchedValue)));
                    }
                }
            }
        } else if (this.mode == Id.STASH) {
            // Collect all keys from GlobalVariable
            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(GlobalVariable.globalVariables.keySet());
            allKeys.addAll(GlobalVariable.globalArrays.keySet());
            allKeys.addAll(GlobalVariable.globalHashes.keySet());
            allKeys.addAll(GlobalVariable.globalCodeRefs.keySet());
            allKeys.addAll(GlobalVariable.globalIORefs.keySet());

            // Process each key to extract the namespace part
            Set<String> uniqueKeys = new HashSet<>(); // Set to track unique keys
            for (String key : allKeys) {
                if (key.startsWith(namespace)) {
                    String remainingKey = key.substring(namespace.length());
                    int nextSeparatorIndex = remainingKey.indexOf("::");
                    String entryKey;
                    if (nextSeparatorIndex == -1) {
                        entryKey = remainingKey;
                    } else {
                        entryKey = remainingKey.substring(0, nextSeparatorIndex + 2);
                    }

                    // Add the entry only if it's not already in the set of unique keys
                    if (uniqueKeys.add(entryKey)) {
                        RuntimeGlob glob = new RuntimeGlob(key);
                        entries.add(new SimpleEntry<>(entryKey, glob));
                    }
                }
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
            Matcher matcher = RuntimeRegex.globalMatcher;
            if (matcher != null && key instanceof String name) {
                String matchedValue = matcher.group(name);
                if (matchedValue != null) {
                    if (this.mode == Id.CAPTURE_ALL) {
                        return new RuntimeArray(new RuntimeScalar(matchedValue)).createReference();
                    } else {
                        return new RuntimeScalar(matchedValue);
                    }
                }
            }
        } else if (this.mode == Id.STASH) {
            if (key instanceof String name) {
                boolean found = containsNamespace(GlobalVariable.globalVariables, name) ||
                        containsNamespace(GlobalVariable.globalArrays, name) ||
                        containsNamespace(GlobalVariable.globalHashes, name) ||
                        containsNamespace(GlobalVariable.globalCodeRefs, name) ||
                        containsNamespace(GlobalVariable.globalIORefs, name);
                if (found) {
                    return new RuntimeGlob(namespace + name);
                }
            }
        }
        return scalarUndef;
    }

    /**
     * Checks if any key in the map starts with the given namespace followed by the typeglob name.
     * This method is used in STASH mode to determine if a particular typeglob exists in the
     * global variables.
     *
     * @param map  The map to check.
     * @param name The typeglob name to match.
     * @return True if a matching key is found, false otherwise.
     */
    private boolean containsNamespace(Map<String, ?> map, String name) {
        String prefix = namespace + name;
        for (String key : map.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // Enum to represent the mode of operation for HashSpecialVariable
    public enum Id {
        CAPTURE_ALL,  // Represents Perl %- for accessing all capturing groups
        CAPTURE,      // Represents Perl %+ for accessing named capturing groups
        STASH         // Represents the Perl stash for accessing the symbol table
    }
}

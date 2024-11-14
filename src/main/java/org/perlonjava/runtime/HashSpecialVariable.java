package org.perlonjava.runtime;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * HashSpecialVariable provides a dynamic view over named capturing groups
 * in a Matcher object, reflecting the current state of the Matcher.
 * This implements the Perl special variables %+, %-.
 * It also implements Perl "stash".
 * Stash is the hash that represents a package's symbol table,
 * containing all the typeglobs for that package.
 */
public class HashSpecialVariable extends AbstractMap<String, RuntimeScalar> {

    // Mode of operation for this special variable
    private HashSpecialVariable.Id mode = null;
    private String namespace = null;

    /**
     * Constructs a HashSpecialVariable for the given Matcher.
     */
    public HashSpecialVariable(HashSpecialVariable.Id mode) {
        this.mode = mode;
    }

    /**
     * Constructs a HashSpecialVariable for the given Stash.
     */
    public HashSpecialVariable(HashSpecialVariable.Id mode, String namespace) {
        this.mode = mode;
        this.namespace = namespace;
    }

    public static RuntimeHash getStash(String namespace) {
        RuntimeHash stash = new RuntimeHash();
        stash.elements = new HashSpecialVariable(HashSpecialVariable.Id.STASH, namespace);
        return stash;
    }

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
                        RuntimeGlob glob = new RuntimeGlob(entryKey);
                        RuntimeScalar scalar = new RuntimeScalar(glob);
                        entries.add(new SimpleEntry<>(entryKey, scalar));
                    }
                }
            }
        }
        return entries;
    }

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
                    return new RuntimeScalar(new RuntimeGlob(name));
                }
            }
        }
        return scalarUndef;
    }

    /**
     * Checks if any key in the map starts with the given namespace followed by "::".
     *
     * @param map  The map to check.
     * @param name The namespace to match.
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

    /**
     * Enum to represent the mode of operation for HashSpecialVariable.
     */
    public enum Id {
        CAPTURE_ALL,  // Perl %-
        CAPTURE, // Perl %+
        STASH
    }
}

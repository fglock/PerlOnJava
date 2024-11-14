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
    private final HashSpecialVariable.Id mode;

    public static HashSpecialVariable getStash(String namespace) {
        // TODO Use namespace to get the stash
        return new HashSpecialVariable(HashSpecialVariable.Id.STASH);
    }

    /**
     * Constructs a HashSpecialVariable for the given Matcher.
     */
    public HashSpecialVariable(HashSpecialVariable.Id mode) {
        this.mode = mode;
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
            for (String key : allKeys) {
                String namespace = extractNamespace(key);
                if (namespace != null) {
                    entries.add(new SimpleEntry<>(namespace, new RuntimeScalar(namespace)));
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
            if (this.mode == Id.STASH && key instanceof String namespace) {
                // Check if any key in the global maps starts with the namespace followed by "::"
                if (containsNamespace(GlobalVariable.globalVariables, namespace) ||
                        containsNamespace(GlobalVariable.globalArrays, namespace) ||
                        containsNamespace(GlobalVariable.globalHashes, namespace) ||
                        containsNamespace(GlobalVariable.globalCodeRefs, namespace) ||
                        containsNamespace(GlobalVariable.globalIORefs, namespace)) {
                    return new RuntimeScalar(namespace);
                }
            }
        }
        return scalarUndef;
    }

    /**
     * Checks if any key in the map starts with the given namespace followed by "::".
     *
     * @param map The map to check.
     * @param namespace The namespace to match.
     * @return True if a matching key is found, false otherwise.
     */
    private boolean containsNamespace(Map<String, ?> map, String namespace) {
        String prefix = namespace + "::";
        for (String key : map.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the namespace from a key by taking the substring before the last "::".
     *
     * @param key The key from which to extract the namespace.
     * @return The namespace part of the key, or null if no namespace is present.
     */
    private String extractNamespace(String key) {
        int lastIndex = key.lastIndexOf("::");
        if (lastIndex != -1) {
            return key.substring(0, lastIndex);
        }
        return null;
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

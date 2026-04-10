package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

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
            Matcher matcher = RuntimeRegex.globalMatcher;
            if (matcher != null) {
                Map<String, Integer> namedGroups = matcher.pattern().namedGroups();
                for (String name : namedGroups.keySet()) {
                    String matchedValue = matcher.group(name);
                    if (this.mode == Id.CAPTURE_ALL) {
                        // For %-, values are always array refs (even for non-participating groups)
                        RuntimeArray arr = new RuntimeArray();
                        if (matchedValue != null) {
                            arr.push(new RuntimeScalar(matchedValue));
                        } else {
                            arr.push(new RuntimeScalar()); // undef for non-participating groups
                        }
                        entries.add(new SimpleEntry<>(name, arr.createReference()));
                    } else {
                        // For %+, only include groups that actually matched
                        if (matchedValue != null) {
                            entries.add(new SimpleEntry<>(name, new RuntimeScalar(matchedValue)));
                        }
                    }
                }
            }
        } else if (this.mode == Id.STASH) {
            // System.out.println("EntrySet ");
            // Collect all keys from GlobalVariable
            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(GlobalVariable.getGlobalVariablesMap().keySet());
            allKeys.addAll(GlobalVariable.getGlobalArraysMap().keySet());
            allKeys.addAll(GlobalVariable.getGlobalHashesMap().keySet());
            allKeys.addAll(GlobalVariable.getGlobalCodeRefsMap().keySet());
            allKeys.addAll(GlobalVariable.getGlobalIORefsMap().keySet());
            allKeys.addAll(GlobalVariable.getGlobalFormatRefsMap().keySet());

            // Process each key to extract the namespace part
            Set<String> uniqueKeys = new HashSet<>(); // Set to track unique keys
            boolean isMainStash = "main::".equals(namespace);
            for (String key : allKeys) {
                String entryKey = null;
                String globName = null;

                if (key.startsWith(namespace)) {
                    String remainingKey = key.substring(namespace.length());
                    int nextSeparatorIndex = remainingKey.indexOf("::");
                    if (nextSeparatorIndex == -1) {
                        entryKey = remainingKey;
                    } else {
                        // Stash keys for nested packages include the trailing "::"
                        // (e.g. "Foo::" not "Foo") - this is how Perl indicates sub-packages
                        entryKey = remainingKey.substring(0, nextSeparatorIndex + 2);
                    }
                    // entryKey already includes "::" for nested packages
                    globName = namespace + entryKey;
                } else if (isMainStash) {
                    // For %main::, also include top-level packages that aren't explicitly
                    // prefixed with "main::". In Perl, $Foo::x and $main::Foo::x are the same.
                    // Variables in top-level packages are stored as "Foo::x", not "main::Foo::x".
                    int separatorIndex = key.indexOf("::");
                    if (separatorIndex > 0) {
                        // This is a top-level package (like "Foo::test")
                        // Extract "Foo::" as the entry key
                        entryKey = key.substring(0, separatorIndex + 2);
                        // The glob name is the original key prefix
                        globName = entryKey;
                    }
                }

                if (entryKey == null) {
                    continue;
                }

                // Special sort variables should not show up in stash enumeration
                if (entryKey.equals("a") || entryKey.equals("b")) {
                    continue;
                }

                if (entryKey.isEmpty()) {
                    continue;
                }

                // Add the entry only if it's not already in the set of unique keys
                if (uniqueKeys.add(entryKey)) {
                    entries.add(new SimpleEntry<>(entryKey, new RuntimeStashEntry(globName, true)));
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
                // Check if this is a valid named group
                if (!matcher.pattern().namedGroups().containsKey(name)) {
                    return scalarUndef;
                }
                String matchedValue = matcher.group(name);
                if (this.mode == Id.CAPTURE_ALL) {
                    // For %-, always return array ref (with undef for non-participating groups)
                    RuntimeArray arr = new RuntimeArray();
                    if (matchedValue != null) {
                        arr.push(new RuntimeScalar(matchedValue));
                    } else {
                        arr.push(new RuntimeScalar()); // undef
                    }
                    return arr.createReference();
                } else {
                    // For %+, return the matched value or undef
                    if (matchedValue != null) {
                        return new RuntimeScalar(matchedValue);
                    }
                }
            }
        } else if (this.mode == Id.STASH) {
            String prefix = namespace + key;
            // System.out.println("Get Key " + prefix);
            if (containsNamespace(GlobalVariable.getGlobalVariablesMap(), prefix) ||
                    containsNamespace(GlobalVariable.getGlobalArraysMap(), prefix) ||
                    containsNamespace(GlobalVariable.getGlobalHashesMap(), prefix) ||
                    containsNamespace(GlobalVariable.getGlobalCodeRefsMap(), prefix) ||
                    containsNamespace(GlobalVariable.getGlobalIORefsMap(), prefix) ||
                    containsNamespace(GlobalVariable.getGlobalFormatRefsMap(), prefix)) {
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
            Matcher matcher = RuntimeRegex.globalMatcher;
            if (matcher != null && key instanceof String name) {
                return matcher.pattern().namedGroups().containsKey(name);
            }
            return false;
        }
        if (this.mode == Id.CAPTURE) {
            // For %+, only groups that actually captured
            Matcher matcher = RuntimeRegex.globalMatcher;
            if (matcher != null && key instanceof String name) {
                return matcher.pattern().namedGroups().containsKey(name) && matcher.group(name) != null;
            }
            return false;
        }
        return super.containsKey(key);
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

            return oldValue;
        }
        return scalarUndef;
    }

    @Override
    public RuntimeScalar remove(Object key) {
        if (this.mode == Id.STASH) {
            String fullKey = namespace + key;

            // Check if the glob exists
            boolean exists = containsNamespace(GlobalVariable.getGlobalVariablesMap(), fullKey) ||
                    containsNamespace(GlobalVariable.getGlobalArraysMap(), fullKey) ||
                    containsNamespace(GlobalVariable.getGlobalHashesMap(), fullKey) ||
                    containsNamespace(GlobalVariable.getGlobalCodeRefsMap(), fullKey) ||
                    containsNamespace(GlobalVariable.getGlobalIORefsMap(), fullKey) ||
                    containsNamespace(GlobalVariable.getGlobalFormatRefsMap(), fullKey);

            if (!exists) {
                return scalarUndef;
            }

            // Get references to all the slots before deleting
            // Only remove from globalCodeRefs, NOT pinnedCodeRefs, to allow compiled code
            // to continue calling the subroutine (Perl caches CVs at compile time)
            RuntimeScalar code = GlobalVariable.getGlobalCodeRefsMap().remove(fullKey);
            RuntimeScalar scalar = GlobalVariable.getGlobalVariablesMap().remove(fullKey);
            RuntimeArray array = GlobalVariable.getGlobalArraysMap().remove(fullKey);
            RuntimeHash hash = GlobalVariable.getGlobalHashesMap().remove(fullKey);
            RuntimeGlob io = GlobalVariable.getGlobalIORefsMap().remove(fullKey);
            RuntimeScalar format = GlobalVariable.getGlobalFormatRefsMap().remove(fullKey);

            // Any stash mutation can affect method lookup; clear method resolution caches.
            InheritanceResolver.invalidateCache();

            // Return a detached glob with all saved non-CODE slots.
            // Matches RuntimeStash.deleteGlob() behavior: the returned glob lets callers
            // access old slot values (e.g., *{$old}{SCALAR} in namespace::clean).
            return RuntimeGlob.createDetachedWithSlots(scalar, array, hash, io);
        }
        return scalarUndef;
    }

    @Override
    public void clear() {
        if (this.mode == Id.STASH) {
            String prefix = namespace;

            GlobalVariable.getGlobalVariablesMap().keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.getGlobalArraysMap().keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.getGlobalHashesMap().keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.getGlobalCodeRefsMap().keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.getGlobalIORefsMap().keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.getGlobalFormatRefsMap().keySet().removeIf(k -> k.startsWith(prefix));

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

    // Enum to represent the mode of operation for HashSpecialVariable
    public enum Id {
        CAPTURE_ALL,  // Represents Perl %- for accessing all capturing groups
        CAPTURE,      // Represents Perl %+ for accessing named capturing groups
        STASH         // Represents the Perl stash for accessing the symbol table
    }
}

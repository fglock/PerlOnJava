package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.regex.CaptureNameEncoder;
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
                // Collect entries by decoded Perl name so that duplicate-name
                // captures (e.g. `(?<y>a)|(?<y>b)`) merge into a single key.
                // Note: Java's Pattern.namedGroups() returns an unordered map
                // (ImmutableCollections.MapN), so we must explicitly sort each
                // bucket by group number so that the *leftmost* alternative
                // wins (Perl semantics for $+{name}).
                java.util.Map<String, java.util.List<String>> byPerlName = new java.util.LinkedHashMap<>();
                for (String name : namedGroups.keySet()) {
                    if (CaptureNameEncoder.isInternalCapture(name)) {
                        continue;
                    }
                    String perlName = CaptureNameEncoder.decodeGroupName(name);
                    byPerlName.computeIfAbsent(perlName, k -> new java.util.ArrayList<>()).add(name);
                }
                for (java.util.List<String> jns : byPerlName.values()) {
                    jns.sort(java.util.Comparator.comparingInt(namedGroups::get));
                }
                for (Map.Entry<String, java.util.List<String>> e : byPerlName.entrySet()) {
                    String perlName = e.getKey();
                    java.util.List<String> javaNames = e.getValue();
                    if (this.mode == Id.CAPTURE_ALL) {
                        // For %-, value is an arrayref containing every alternative
                        // (matched ones get the captured value, unmatched get undef).
                        RuntimeArray arr = new RuntimeArray();
                        for (String jn : javaNames) {
                            String v = matcher.group(jn);
                            arr.push(v != null ? new RuntimeScalar(v) : new RuntimeScalar());
                        }
                        entries.add(new SimpleEntry<>(perlName, arr.createReference()));
                    } else {
                        // For %+, only include the alternative that actually matched.
                        // For duplicate names at most one branch will have matched.
                        for (String jn : javaNames) {
                            String v = matcher.group(jn);
                            if (v != null) {
                                entries.add(new SimpleEntry<>(perlName, new RuntimeScalar(v)));
                                break;
                            }
                        }
                    }
                }
            }
        } else if (this.mode == Id.STASH) {
            // System.out.println("EntrySet ");
            // Collect all keys from GlobalVariable
            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(GlobalVariable.globalVariables.keySet());
            allKeys.addAll(GlobalVariable.globalArrays.keySet());
            allKeys.addAll(GlobalVariable.globalHashes.keySet());
            allKeys.addAll(GlobalVariable.globalCodeRefs.keySet());
            allKeys.addAll(GlobalVariable.globalIORefs.keySet());
            allKeys.addAll(GlobalVariable.globalFormatRefs.keySet());

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
                // Encode the Perl name to Java regex name (underscore encoding)
                String encodedName = CaptureNameEncoder.encodeGroupName(name);
                Map<String, Integer> namedGroups = matcher.pattern().namedGroups();
                // Collect every Java group whose decoded Perl name matches the
                // requested key. For non-duplicated names this is just the
                // single direct match; for duplicated names we may have several.
                java.util.List<String> javaNames = collectJavaNamesFor(namedGroups, encodedName);
                if (javaNames.isEmpty()) {
                    return scalarUndef;
                }
                if (this.mode == Id.CAPTURE_ALL) {
                    // For %-, always return array ref containing one slot per alternative.
                    RuntimeArray arr = new RuntimeArray();
                    for (String jn : javaNames) {
                        String v = matcher.group(jn);
                        arr.push(v != null ? new RuntimeScalar(v) : new RuntimeScalar());
                    }
                    return arr.createReference();
                } else {
                    // For %+, return the matched value (or undef if no branch matched).
                    for (String jn : javaNames) {
                        String v = matcher.group(jn);
                        if (v != null) {
                            return new RuntimeScalar(v);
                        }
                    }
                }
            }
        } else if (this.mode == Id.STASH) {
            String prefix = namespace + key;
            // System.out.println("Get Key " + prefix);
            if (containsNamespace(GlobalVariable.globalVariables, prefix) ||
                    containsNamespace(GlobalVariable.globalArrays, prefix) ||
                    containsNamespace(GlobalVariable.globalHashes, prefix) ||
                    containsNamespace(GlobalVariable.globalCodeRefs, prefix) ||
                    containsNamespace(GlobalVariable.globalIORefs, prefix) ||
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
            Matcher matcher = RuntimeRegex.globalMatcher;
            if (matcher != null && key instanceof String name) {
                String encodedName = CaptureNameEncoder.encodeGroupName(name);
                return !collectJavaNamesFor(matcher.pattern().namedGroups(), encodedName).isEmpty();
            }
            return false;
        }
        if (this.mode == Id.CAPTURE) {
            // For %+, only groups that actually captured
            Matcher matcher = RuntimeRegex.globalMatcher;
            if (matcher != null && key instanceof String name) {
                String encodedName = CaptureNameEncoder.encodeGroupName(name);
                for (String jn : collectJavaNamesFor(matcher.pattern().namedGroups(), encodedName)) {
                    if (matcher.group(jn) != null) {
                        return true;
                    }
                }
            }
            return false;
        }
        return super.containsKey(key);
    }

    /**
     * Returns every Java capture-group name in the matcher's pattern whose
     * decoded Perl name equals {@code encodedPerlName}. For typical patterns
     * this is at most one entry; for duplicate-name patterns like
     * {@code (?<y>a)|(?<y>b)} the preprocessor renames the second occurrence
     * to {@code yZpjdupZ0}, etc., and this helper collects all of them.
     */
    private static java.util.List<String> collectJavaNamesFor(Map<String, Integer> namedGroups, String encodedPerlName) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (namedGroups == null) {
            return out;
        }
        if (namedGroups.containsKey(encodedPerlName)) {
            out.add(encodedPerlName);
        }
        // Also pick up duplicate-marker variants (e.g. nameZpjdupZ0, ZpjdupZ1, ...).
        for (String jn : namedGroups.keySet()) {
            if (!jn.equals(encodedPerlName)
                    && CaptureNameEncoder.isDuplicateMarkerName(jn)
                    && CaptureNameEncoder.stripDuplicateMarker(jn).equals(encodedPerlName)) {
                out.add(jn);
            }
        }
        // Java's Pattern.namedGroups() doesn't preserve insertion order, so sort
        // by group number to match Perl's source order. This makes `$+{name}`
        // return the *leftmost* alternative for duplicate-named captures.
        out.sort(java.util.Comparator.comparingInt(namedGroups::get));
        return out;
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
            boolean exists = containsNamespace(GlobalVariable.globalVariables, fullKey) ||
                    containsNamespace(GlobalVariable.globalArrays, fullKey) ||
                    containsNamespace(GlobalVariable.globalHashes, fullKey) ||
                    containsNamespace(GlobalVariable.globalCodeRefs, fullKey) ||
                    containsNamespace(GlobalVariable.globalIORefs, fullKey) ||
                    containsNamespace(GlobalVariable.globalFormatRefs, fullKey);

            if (!exists) {
                return scalarUndef;
            }

            // Get references to all the slots before deleting
            // Only remove from globalCodeRefs, NOT pinnedCodeRefs, to allow compiled code
            // to continue calling the subroutine (Perl caches CVs at compile time)
            RuntimeScalar code = GlobalVariable.globalCodeRefs.remove(fullKey);
            // Decrement stashRefCount on the removed CODE ref
            if (code != null && code.value instanceof RuntimeCode removedCode) {
                if (removedCode.stashRefCount > 0) {
                    removedCode.stashRefCount--;
                }
            }
            RuntimeScalar scalar = GlobalVariable.globalVariables.remove(fullKey);
            RuntimeArray array = GlobalVariable.globalArrays.remove(fullKey);
            RuntimeHash hash = GlobalVariable.globalHashes.remove(fullKey);
            RuntimeGlob io = GlobalVariable.globalIORefs.remove(fullKey);
            RuntimeScalar format = GlobalVariable.globalFormatRefs.remove(fullKey);

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

            GlobalVariable.globalVariables.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalArrays.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalHashes.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalCodeRefs.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalIORefs.keySet().removeIf(k -> k.startsWith(prefix));
            GlobalVariable.globalFormatRefs.keySet().removeIf(k -> k.startsWith(prefix));

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

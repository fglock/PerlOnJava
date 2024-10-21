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
 * This implements the Perl special variable %+.
 */
public class HashSpecialVariable extends AbstractMap<String, RuntimeScalar> {

    // Mode of operation for this special variable
    private final HashSpecialVariable.Id mode;

    /**
     * Constructs a HashSpecialVariable for the given Matcher.
     */
    public HashSpecialVariable(HashSpecialVariable.Id mode) {
        this.mode = mode;
    }

    @Override
    public Set<Entry<String, RuntimeScalar>> entrySet() {
        Matcher matcher = RuntimeRegex.globalMatcher;
        Set<Entry<String, RuntimeScalar>> entries = new HashSet<>();
        if (matcher != null) {
            Map<String, Integer> namedGroups = matcher.pattern().namedGroups();
            for (String name : namedGroups.keySet()) {
                String matchedValue = matcher.group(name);
                if (matchedValue != null) {
                    entries.add(new SimpleEntry<>(name, new RuntimeScalar(matchedValue)));
                }
            }
        }
        return entries;
    }

    @Override
    public RuntimeScalar get(Object key) {
        Matcher matcher = RuntimeRegex.globalMatcher;
        if (matcher != null && key instanceof String name) {
            String matchedValue = matcher.group(name);
            if (matchedValue != null) {
                if (this.mode == Id.CAPTURE_ALL) {
                    return new RuntimeArray(new RuntimeScalar(matchedValue)).createReference();
                } else if (this.mode == Id.CAPTURE) {
                    return new RuntimeScalar(matchedValue);
                }
                return scalarUndef;
            }
        }
        return scalarUndef;
    }

    /**
     * Enum to represent the mode of operation for HashSpecialVariable.
     */
    public enum Id {
        CAPTURE_ALL,  // Perl %-
        CAPTURE // Perl %+
    }
}

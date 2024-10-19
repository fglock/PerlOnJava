package org.perlonjava.runtime;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * HashSpecialVariable provides a dynamic view over named capturing groups
 * in a Matcher object, reflecting the current state of the Matcher.
 * This implements the Perl special variable %+.
 */
public class HashSpecialVariable extends AbstractMap<String, RuntimeScalar> {

    private final Matcher matcher;
    private final Map<String, Integer> namedGroups;

    /**
     * Constructs a HashSpecialVariable for the given Matcher.
     *
     * @param matcher the Matcher object to query for named capturing groups
     */
    public HashSpecialVariable(Matcher matcher) {
        this.matcher = matcher;
        this.namedGroups = matcher.pattern().namedGroups();  // Use Java 20's built-in namedGroups support
    }

    @Override
    public Set<Entry<String, RuntimeScalar>> entrySet() {
        Set<Entry<String, RuntimeScalar>> entries = new HashSet<>();
        for (String name : namedGroups.keySet()) {
            String matchedValue = matcher.group(name);  // Use Matcher.group(String name)
            if (matchedValue != null) {
                entries.add(new SimpleEntry<>(name, new RuntimeScalar(matchedValue)));
            }
        }
        return entries;
    }

    @Override
    public RuntimeScalar get(Object key) {
        if (key instanceof String) {
            String name = (String) key;
            String matchedValue = matcher.group(name);  // Use Matcher.group(String name)
            if (matchedValue != null) {
                return new RuntimeScalar(matchedValue);
            }
        }
        return scalarUndef;
    }
}

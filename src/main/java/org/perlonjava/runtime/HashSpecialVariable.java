package org.perlonjava.runtime;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        this.namedGroups = extractNamedGroups(matcher.pattern());
    }

    /**
     * Extracts named groups and their indices from the given pattern.
     *
     * @param pattern the regex pattern
     * @return a map of named group names to their indices
     */
    private Map<String, Integer> extractNamedGroups(Pattern pattern) {
        Map<String, Integer> namedGroups = new HashMap<>();
        String regex = pattern.toString();
        Matcher groupMatcher = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(regex);
        int index = 1; // Group indices start at 1

        while (groupMatcher.find()) {
            String groupName = groupMatcher.group(1);
            namedGroups.put(groupName, index++);
        }

        return namedGroups;
    }

    @Override
    public Set<Entry<String, RuntimeScalar>> entrySet() {
        Set<Entry<String, RuntimeScalar>> entries = new HashSet<>();
        for (String name : namedGroups.keySet()) {
            int groupIndex = namedGroups.get(name);
            if (groupIndex != -1 && matcher.group(name) != null) {
                entries.add(new SimpleEntry<>(name, new RuntimeScalar(matcher.group(name))));
            }
        }
        return entries;
    }

    @Override
    public RuntimeScalar get(Object key) {
        if (key instanceof String) {
            String name = (String) key;
            int groupIndex = namedGroups.getOrDefault(name, -1);
            if (groupIndex != -1 && matcher.group(groupIndex) != null) {
                return new RuntimeScalar(matcher.group(groupIndex));
            }
        }
        return scalarUndef;
    }
}

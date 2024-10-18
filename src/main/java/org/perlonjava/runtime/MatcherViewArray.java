package org.perlonjava.runtime;

import java.util.AbstractList;
import java.util.regex.Matcher;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * MatcherViewArray provides a dynamic view over a Matcher object,
 * representing the end positions of each capturing group in the Matcher.
 * This class does not store data internally but queries the Matcher
 * whenever its methods are called, ensuring it reflects the current state
 * of the Matcher.
 *
 * <p>Example usage:</p>
 * <pre>
 *     // Create a RuntimeArray and assign MatcherViewArray to its elements
 *     RuntimeArray runtimeArray = new RuntimeArray();
 *     runtimeArray.elements = matcherViewArray;
 * </pre>
 */public class MatcherViewArray extends AbstractList<RuntimeScalar> {

    private final Mode mode;

    /**
     * Constructs a MatcherViewArray for the given Matcher with a specified mode.
     *
     * @param mode the mode of operation, either Mode.END or Mode.START
     */
    public MatcherViewArray(Mode mode) {
        this.mode = mode;
    }

    /**
     * Returns the position of the capturing group at the specified index.
     * The position returned depends on the mode: end position for Mode.END,
     * and start position for Mode.START.
     *
     * @param index the index of the capturing group
     * @return a RuntimeScalar representing the position of the group
     */
    @Override
    public RuntimeScalar get(int index) {
        Matcher matcher = RuntimeRegex.globalMatcher;

        if (matcher == null) {
            return scalarUndef;
        }

        if (index < 0 || index > matcher.groupCount()) {
            return scalarUndef;
        }

        int position;
        if (mode == Mode.END) {
            // Retrieve the end position of the group at the specified index
            position = matcher.end(index);
        } else {
            // Retrieve the start position of the group at the specified index
            position = matcher.start(index);
        }

        return getScalarInt(position);
    }

    /**
     * Returns the number of capturing groups in the Matcher, plus one for the
     * entire match. This reflects the dynamic nature of the Matcher.
     *
     * @return the number of capturing groups plus one
     */
    @Override
    public int size() {
        Matcher matcher = RuntimeRegex.globalMatcher;

        if (matcher == null) {
            return 0;
        }

        // +1 because groupCount is zero-based, and we include the entire match
        return matcher.groupCount() + 1;
    }

    /**
     * Enum to represent the mode of operation for MatcherViewArray.
     * END corresponds to "@+" (end positions), and START corresponds to "@-" (start positions).
     */
    public enum Mode {
        END,  // Represents the end positions of capturing groups
        START // Represents the start positions of capturing groups
    }
}
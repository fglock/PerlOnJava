package org.perlonjava.runtime;

import java.util.AbstractList;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * MatcherViewArray provides a dynamic view over a Matcher object,
 * representing the end positions of each capturing group in the Matcher.
 * This class does not store data internally but queries the Matcher
 * whenever its methods are called, ensuring it reflects the current state
 * of the Matcher.
 */
public class MatcherViewArray extends AbstractList<RuntimeScalar> {

    private final Id mode;

    /**
     * Constructs a MatcherViewArray for the given Matcher with a specified mode.
     *
     * @param mode the mode of operation
     */
    public MatcherViewArray(Id mode) {
        this.mode = mode;
    }

    /**
     * Returns the position of the capturing group at the specified index.
     * The position returned depends on the mode: end position for Id.END,
     * and start position for Id.START.
     *
     * @param index the index of the capturing group
     * @return a RuntimeScalar representing the position of the group
     */
    @Override
    public RuntimeScalar get(int index) {
        if (mode == Id.LAST_MATCH_END) {
            // Retrieve the end position of the group at the specified index
            return RuntimeRegex.matcherEnd(index);
        } else if (mode == Id.LAST_MATCH_START) {
            // Retrieve the start position of the group at the specified index
            return RuntimeRegex.matcherStart(index);
        } else {
            return scalarUndef;
        }
    }

    /**
     * Returns the number of capturing groups in the Matcher, plus one for the
     * entire match. This reflects the dynamic nature of the Matcher.
     *
     * @return the number of capturing groups plus one
     */
    @Override
    public int size() {
        if (mode == Id.LAST_MATCH_END || mode == Id.LAST_MATCH_START) {
            // Retrieve the number of capturing groups in the Matcher
            return RuntimeRegex.matcherSize();
        } else {
            return 0;
        }
    }

    /**
     * Enum to represent the mode of operation for MatcherViewArray.
     * END corresponds to "@+" (end positions), and START corresponds to "@-" (start positions).
     */
    public enum Id {
        LAST_MATCH_END,  // Represents the end positions of capturing groups
        LAST_MATCH_START // Represents the start positions of capturing groups
    }
}
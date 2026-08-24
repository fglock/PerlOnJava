package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.regex.RuntimeRegex;

import java.util.AbstractList;

/**
 * ArraySpecialVariable provides a dynamic view over an internal object, such as a Matcher object,
 * representing the start or end positions of each capturing group in the Matcher.
 * This class does not store data internally but queries the Matcher
 * whenever its methods are called, ensuring it reflects the current state
 * of the Matcher.
 */
public class ArraySpecialVariable extends AbstractList<RuntimeScalar> {

    // Mode of operation for this special variable, determining whether it tracks start or end positions
    private final Id mode;

    /**
     * Constructs an ArraySpecialVariable for the given mode.
     *
     * @param mode the mode of operation, determining whether to track start or end positions
     */
    public ArraySpecialVariable(Id mode) {
        this.mode = mode;
    }

    ArraySpecialVariable snapshotView() {
        return new ArraySpecialVariable(mode);
    }

    /**
     * Returns the position of the capturing group at the specified index.
     * The position returned depends on the mode: end position for Id.LAST_MATCH_END,
     * and start position for Id.LAST_MATCH_START.
     *
     * @param index the index of the capturing group
     * @return a RuntimeScalar representing the position of the group
     */
    @Override
    public RuntimeScalar get(int index) {
        if (mode == Id.LAST_MATCH_END) {
            return new ScalarSpecialVariable(
                    ScalarSpecialVariable.Id.MATCH_END_OFFSET, index);
        } else if (mode == Id.LAST_MATCH_START) {
            return new ScalarSpecialVariable(
                    ScalarSpecialVariable.Id.MATCH_START_OFFSET, index);
        } else if (mode == Id.CAPTURE) {
            return new ScalarSpecialVariable(
                    ScalarSpecialVariable.Id.MATCH_CAPTURE, index);
        } else {
            return RuntimeScalarCache.scalarUndef;
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
        if (mode == Id.LAST_MATCH_START) {
            return RuntimeRegex.matcherStartSize();
        } else if (mode == Id.LAST_MATCH_END) {
            // Retrieve the number of capturing groups in the Matcher
            return RuntimeRegex.matcherSize();
        } else if (mode == Id.CAPTURE) {
            return RuntimeRegex.matcherCaptureSize();
        } else {
            return 0;
        }
    }

    /**
     * Enum to represent the mode of operation for ArraySpecialVariable.
     * LAST_MATCH_END corresponds to "@+" (end positions), and LAST_MATCH_START corresponds to "@-" (start positions).
     */
    public enum Id {
        LAST_MATCH_END,   // Represents the end positions of capturing groups
        LAST_MATCH_START, // Represents the start positions of capturing groups
        CAPTURE           // Represents the contents of the capture buffers
    }
}

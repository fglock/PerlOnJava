package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeScalar;

import java.util.HashMap;
import java.util.Map;

public class ScalarFlipFlopOperator {

    // Map to store the state (flip-flop) and sequence count for each operator instance
    public static final Map<Integer, ScalarFlipFlopOperator> flipFlops = new HashMap<>();
    public static Integer currentId = 0;

    private final boolean isThreeDot; // true for three dots, false for two dots
    private boolean currentState;
    private int currentSequence;

    public ScalarFlipFlopOperator(boolean isThreeDot) {
        this.isThreeDot = isThreeDot;
        this.currentState = false;
        this.currentSequence = 0;
    }

    // Core logic for evaluating the scalar range operator
    public static RuntimeScalar evaluate(int id, RuntimeScalar left, RuntimeScalar right) {
        ScalarFlipFlopOperator ff = flipFlops.get(id);
        boolean leftOperand = left.getBoolean();
        boolean rightOperand = right.getBoolean();
        if (!ff.currentState) {
            // If current state is false, evaluate the left operand
            if (leftOperand) {
                ff.currentState = true;
                ff.currentSequence = 1;  // Start the sequence
            }
        } else {
            // If current state is true, evaluate the right operand
            ff.currentSequence++;

            // In the case of three dots (...), the right operand isn't evaluated the same time
            // the left operand becomes true. Instead, we defer checking the right operand until
            // the next iteration, achieved through the isThreeDot flag and the sequence counter
            // currentSequence.
            if (!ff.isThreeDot || ff.currentSequence > 1) {
                if (rightOperand) {
                    ff.currentState = false;  // Flip back to false after right operand is true
                    return new RuntimeScalar(ff.currentSequence + "E0");  // End of the sequence, append "E0"
                }
            }
        }
        return new RuntimeScalar(ff.currentState ? String.valueOf(ff.currentSequence) : "");  // Return sequence or empty string
    }
}


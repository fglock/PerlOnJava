package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Map;

public class ScalarRangeOperator {

    // Map to store the state (flip-flop) and sequence count for each operator instance
    private static final Map<String, Boolean> stateMap = new HashMap<>();
    private static final Map<String, Integer> sequenceMap = new HashMap<>();
    
    private final String id;

    // Constructor to initialize the range operator with a unique identifier
    public ScalarRangeOperator(String id) {
        this.id = id;
        stateMap.putIfAbsent(id, false);   // Initialize to false state
        sequenceMap.putIfAbsent(id, 0);    // Initialize sequence to 0
    }

    // Method to reset state and sequence if necessary
    public void reset() {
        stateMap.put(id, false);
        sequenceMap.put(id, 0);
    }

    // Core logic for evaluating the scalar range operator
    public String evaluate(boolean leftOperand, boolean rightOperand) {
        boolean currentState = stateMap.get(id);
        int currentSequence = sequenceMap.get(id);

        if (!currentState) {
            // If current state is false, evaluate the left operand
            if (leftOperand) {
                currentState = true;
                currentSequence = 1;  // Start the sequence
            }
        } else {
            // If current state is true, evaluate the right operand
            currentSequence++;
            if (rightOperand) {
                currentState = false;  // Flip back to false after right operand is true
                return currentSequence + "E0";  // End of the sequence, append "E0"
            }
        }

        // Update the state and sequence in the maps
        stateMap.put(id, currentState);
        sequenceMap.put(id, currentSequence);

        return currentState ? String.valueOf(currentSequence) : "";  // Return sequence or empty string
    }

    public static void main(String[] args) {
        // Example usage
        ScalarRangeOperator rangeOp = new ScalarRangeOperator("exampleRange");

        // Test case: Using boolean expressions as operands
        boolean[] leftOperands = {false, true, false, false, false};
        boolean[] rightOperands = {false, false, false, true, false};

        for (int i = 0; i < leftOperands.length; i++) {
            String result = rangeOp.evaluate(leftOperands[i], rightOperands[i]);
            System.out.println("Evaluation " + (i + 1) + ": " + result);
        }

        // Reset the state if needed
        rangeOp.reset();
    }
}


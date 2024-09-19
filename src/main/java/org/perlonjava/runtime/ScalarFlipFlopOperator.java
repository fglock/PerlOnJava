package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Map;

public class ScalarFlipFlopOperator {

    // Map to store the state (flip-flop) and sequence count for each operator instance
    private static final Map<String, Boolean> stateMap = new HashMap<>();
    private static final Map<String, Integer> sequenceMap = new HashMap<>();

    private final String id;
    private final boolean isThreeDot; // true for three dots, false for two dots

    // Constructor to initialize the flip-flop operator with a unique identifier
    public ScalarFlipFlopOperator(String id, boolean isThreeDot) {
        this.id = id;
        this.isThreeDot = isThreeDot;
        stateMap.putIfAbsent(id, false);   // Initialize to false state
        sequenceMap.putIfAbsent(id, 0);    // Initialize sequence to 0
    }

    public static void main(String[] args) {
        // Example usage for two dots operator
        ScalarFlipFlopOperator twoDotRange = new ScalarFlipFlopOperator("exampleRange2Dot", false);

        // Test case: Using boolean expressions as operands for two dots
        boolean[] leftOperands = {false, true, false, false, false};
        boolean[] rightOperands = {false, false, false, true, false};

        for (int i = 0; i < leftOperands.length; i++) {
            String result = twoDotRange.evaluate(leftOperands[i], rightOperands[i]);
            System.out.println("2 Dot Evaluation " + (i + 1) + ": " + result);
        }

        // Example usage for three dots operator
        ScalarFlipFlopOperator threeDotRange = new ScalarFlipFlopOperator("exampleRange3Dot", true);

        // Test case: Using boolean expressions as operands for three dots
        for (int i = 0; i < leftOperands.length; i++) {
            String result = threeDotRange.evaluate(leftOperands[i], rightOperands[i]);
            System.out.println("3 Dot Evaluation " + (i + 1) + ": " + result);
        }
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

            // In the case of three dots (...), the right operand isn't evaluated the same time
            // the left operand becomes true. Instead, we defer checking the right operand until
            // the next iteration, achieved through the isThreeDot flag and the sequence counter
            // currentSequence.
            if (!isThreeDot || currentSequence > 1) {
                if (rightOperand) {
                    currentState = false;  // Flip back to false after right operand is true
                    return currentSequence + "E0";  // End of the sequence, append "E0"
                }
            }
        }

        // Update the state and sequence in the maps
        stateMap.put(id, currentState);
        sequenceMap.put(id, currentSequence);

        return currentState ? String.valueOf(currentSequence) : "";  // Return sequence or empty string
    }
}


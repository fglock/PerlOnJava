package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.PerlRange;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

/**
 * Implements a scalar flip-flop operator, similar to Perl's flip-flop operator.
 * This operator maintains a state that toggles between true and false based on
 * the evaluation of left and right operands.
 */
public class ScalarFlipFlopOperator {

    // Map to store the state (flip-flop) and sequence count for each operator instance
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    public static int allocateId() {
        return NEXT_ID.getAndIncrement();
    }

    public static Map<Integer, ScalarFlipFlopOperator> flipFlops() {
        return PerlRuntime.current().flipFlopState;
    }

    private final boolean isThreeDot; // true for three dots, false for two dots
    private boolean currentState;
    private int currentSequence;

    /**
     * Constructs a ScalarFlipFlopOperator with the specified dot type.
     *
     * @param isThreeDot true if the operator is a three-dot operator, false if two-dot.
     */
    public ScalarFlipFlopOperator(boolean isThreeDot) {
        this.isThreeDot = isThreeDot;
        this.currentState = false;
        this.currentSequence = 0;
    }

    /**
     * Evaluates the scalar flip-flop operator for the given operands.
     *
     * @param id    The unique identifier for the operator instance.
     * @param left  The left operand as a RuntimeScalar.
     * @param right The right operand as a RuntimeScalar.
     * @return A RuntimeScalar representing the current sequence count or an empty string.
     */
    public static RuntimeScalar evaluate(int id, RuntimeScalar left, RuntimeScalar right) {
        return evaluate(id, left, right, false, false);
    }

    /**
     * Numeric literals used as scalar flip-flop endpoints compare with the
     * current input line number ({@code $.}), rather than merely supplying a
     * truthy numeric value. List-context ranges retain their literal bounds.
     */
    public static RuntimeScalar evaluate(int id, RuntimeScalar left, RuntimeScalar right,
            boolean leftIsLineNumberEndpoint, boolean rightIsLineNumberEndpoint) {
        return evaluate(id, left, right, leftIsLineNumberEndpoint, rightIsLineNumberEndpoint, true);
    }

    /** Evaluate with the lexical uninitialized-warning decision made at the call site. */
    public static RuntimeScalar evaluate(int id, RuntimeScalar left, RuntimeScalar right,
            boolean leftIsLineNumberEndpoint, boolean rightIsLineNumberEndpoint,
            boolean warnUninitialized) {
        ScalarFlipFlopOperator ff = flipFlops().get(id);
        boolean leftOperand = endpointMatches(left, leftIsLineNumberEndpoint, "flip", warnUninitialized);
        boolean rightOperand = endpointMatches(right, rightIsLineNumberEndpoint, "flop", warnUninitialized);
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

    /** Resolve a range operator in a subroutine whose caller context is only known at runtime. */
    public static RuntimeBase evaluateInContext(int id, RuntimeScalar left, RuntimeScalar right, int context) {
        return evaluateInContext(id, left, right, context, false, false);
    }

    public static RuntimeBase evaluateInContext(int id, RuntimeScalar left, RuntimeScalar right,
            int context, boolean leftIsLineNumberEndpoint, boolean rightIsLineNumberEndpoint) {
        return evaluateInContext(id, left, right, context, leftIsLineNumberEndpoint,
                rightIsLineNumberEndpoint, true);
    }

    public static RuntimeBase evaluateInContext(int id, RuntimeScalar left, RuntimeScalar right,
            int context, boolean leftIsLineNumberEndpoint, boolean rightIsLineNumberEndpoint,
            boolean warnUninitialized) {
        if (RuntimeContextType.isListLike(context)) {
            // A scalar wrapper nested in a prototype-driven call can arrive
            // here with its final context selected only at runtime. Numeric
            // endpoints still consult $. before that selection, just as the
            // scalar flip-flop path does.
            if (warnUninitialized && leftIsLineNumberEndpoint && rightIsLineNumberEndpoint) {
                warnUndefinedInputLine("flip");
            }
            return PerlRange.createRange(left, right);
        }
        return evaluate(id, left, right, leftIsLineNumberEndpoint, rightIsLineNumberEndpoint,
                warnUninitialized);
    }

    private static boolean endpointMatches(RuntimeScalar operand, boolean isLineNumberEndpoint,
            String endpointName, boolean warnUninitialized) {
        boolean stringEndpoint = operand.isString();
        if (!isLineNumberEndpoint && !stringEndpoint) return operand.getBoolean();
        if (stringEndpoint && !org.perlonjava.runtime.runtimetypes.ScalarUtils
                .looksLikeNumber(operand)) {
            // Both endpoints are evaluated for a scalar flip-flop.  Route the
            // diagnostic directly through warn so a local __WARN__ handler
            // cannot replace the runtime warning context after the first
            // endpoint and suppress the second diagnostic.
            org.perlonjava.runtime.operators.WarnDie.warn(
                    new RuntimeScalar("Argument \"" + operand + "\" isn't numeric in range (or "
                            + endpointName + ")"),
                    org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarEmptyString);
        }
        RuntimeScalar inputLine = GlobalVariable.getGlobalVariable("main::.");
        if (warnUninitialized) {
            warnUndefinedInputLine(endpointName);
        }
        return inputLine.getInt() == numericEndpointValue(operand, stringEndpoint);
    }

    /**
     * The range operator owns the numeric diagnostic for non-numeric string
     * endpoints.  Do not numify through RuntimeScalar#getInt() afterwards:
     * that path issues a second, context-free warning and can bypass a local
     * __WARN__ handler while it is dynamically protected.
     */
    private static int numericEndpointValue(RuntimeScalar operand, boolean stringEndpoint) {
        if (!stringEndpoint) return operand.getInt();
        String text = operand.toString().trim();
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void warnUndefinedInputLine(String endpointName) {
        RuntimeScalar inputLine = GlobalVariable.getGlobalVariable("main::.");
        if (!inputLine.getDefinedBoolean()) {
            // Numeric endpoints consult $. even when this expression is being
            // evaluated as an argument to a testing helper. Its helper frame
            // must not suppress the diagnostic from the original range.
            org.perlonjava.runtime.operators.WarnDie.warn(
                    new RuntimeScalar("Use of uninitialized value $. in range (or "
                            + endpointName + ")"),
                    org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarEmptyString);
        }
    }
}

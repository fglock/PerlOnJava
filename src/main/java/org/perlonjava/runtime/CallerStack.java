package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * The CallerStack class implements a virtual calling stack used during parsing.
 * It allows for the retrieval of the "calling sequence" of parsing modules, which can be
 * used to present a stack trace to the user in case of an error. This is particularly useful
 * for implementing the caller() function during operations like import() and unimport().
 */
public class CallerStack {
    private static final List<CallerInfo> callerStack = new ArrayList<>();

    /**
     * Pushes a new CallerInfo object onto the stack, representing a new entry in the calling sequence.
     *
     * @param packageName The name of the package where the call originated.
     * @param filename    The name of the file where the call originated.
     * @param line        The line number in the file where the call originated.
     */
    public static void push(String packageName, String filename, int line) {
        callerStack.add(new CallerInfo(packageName, filename, line));
    }

    /**
     * Retrieves the most recent CallerInfo object from the stack without removing it.
     * Zero is the most recent entry.
     *
     * @return The most recent CallerInfo object, or null if the stack is empty.
     */
    public static CallerInfo peek(int callFrame) {
        if (callerStack.isEmpty()) {
            return null;
        }
        int index = callerStack.size() - 1 - callFrame;
        if (index < 0) {
            return null;
        }
        return callerStack.get(index);
    }

    /**
     * Removes and returns the most recent CallerInfo object from the stack.
     *
     * @return The most recent CallerInfo object, or null if the stack is empty.
     */
    public static CallerInfo pop() {
        if (callerStack.isEmpty()) {
            return null;
        }
        return callerStack.removeLast();
    }

    /**
     * Retrieves a copy of the current calling stack.
     *
     * @return A list containing all CallerInfo objects in the stack.
     */
    public static List<CallerInfo> getStack() {
        return new ArrayList<>(callerStack);
    }

    /**
     * The CallerInfo record represents a single frame in the calling stack.
     * Each frame contains the source location information including package,
     * file and line number where the call originated.
     */
    public record CallerInfo(String packageName, String filename, int line) {
        /**
         * Creates a CallerInfo frame with source location details.
         * The canonical constructor is used since no additional validation
         * or processing is needed for the parameters.
         *
         * @param packageName The fully qualified package name of the calling code
         * @param filename    The source file name containing the call
         * @param line        The source line number of the call
         */
        public CallerInfo {
        }

        @Override
        public String toString() {
            return String.format("(%s, %s, %d)", packageName, filename, line);
        }
    }
}

package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;
import java.util.List;

/**
 * The CallerStack class implements a virtual calling stack used during parsing.
 * It allows for the retrieval of the "calling sequence" of parsing modules, which can be
 * used to present a stack trace to the user in case of an error. This is particularly useful
 * for implementing the caller() function during operations like import() and unimport().
 */
public class CallerStack {
    // Store either CallerInfo (resolved) or LazyCallerInfo (deferred)
    private static final List<Object> callerStack = new ArrayList<>();

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
     * Pushes a lazy CallerInfo onto the stack. The actual filename and line number
     * will be computed only when peek() is called, avoiding expensive line number
     * lookups for subroutine calls that never use caller().
     *
     * @param packageName The name of the package where the call originated.
     * @param resolver    A function to compute the CallerInfo when needed.
     */
    public static void pushLazy(String packageName, CallerInfoResolver resolver) {
        callerStack.add(new LazyCallerInfo(packageName, resolver));
    }

    /**
     * Retrieves the most recent CallerInfo object from the stack without removing it.
     * Zero is the most recent entry.
     * If the entry is lazy, it will be resolved and replaced with the actual CallerInfo.
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
        Object entry = callerStack.get(index);
        if (entry instanceof CallerInfo ci) {
            return ci;
        } else if (entry instanceof LazyCallerInfo lazy) {
            // Resolve the lazy entry and cache it
            CallerInfo resolved = lazy.resolve();
            callerStack.set(index, resolved);
            return resolved;
        }
        return null;
    }

    /**
     * Removes and returns the most recent CallerInfo object from the stack.
     * If the entry is lazy, it will NOT be resolved (saves computation on pop).
     *
     * @return The most recent CallerInfo object, or null if the stack is empty.
     */
    public static CallerInfo pop() {
        if (callerStack.isEmpty()) {
            return null;
        }
        Object entry = callerStack.removeLast();
        if (entry instanceof CallerInfo ci) {
            return ci;
        } else if (entry instanceof LazyCallerInfo lazy) {
            return null;
        }
        return null;
    }

    /**
     * Retrieves a copy of the current calling stack.
     * Lazy entries will be resolved.
     *
     * @return A list containing all CallerInfo objects in the stack.
     */
    public static List<CallerInfo> getStack() {
        List<CallerInfo> result = new ArrayList<>();
        for (int i = 0; i < callerStack.size(); i++) {
            Object entry = callerStack.get(i);
            if (entry instanceof CallerInfo ci) {
                result.add(ci);
            } else if (entry instanceof LazyCallerInfo lazy) {
                CallerInfo resolved = lazy.resolve();
                callerStack.set(i, resolved);
                result.add(resolved);
            }
        }
        return result;
    }

    /**
     * Count the number of consecutive lazy (interpreter-pushed) entries from the top
     * of the stack, starting from the given call frame index.
     * This is used by ExceptionFormatter to skip past interpreter CallerStack entries
     * that sit on top of compile-time entries from parseUseDeclaration/runSpecialBlock.
     *
     * @param startCallFrame The call frame index to start counting from (0 = top of stack)
     * @return The number of consecutive lazy entries from startCallFrame
     */
    public static int countLazyFromTop(int startCallFrame) {
        int count = 0;
        int index = callerStack.size() - 1 - startCallFrame;
        while (index >= 0 && callerStack.get(index) instanceof LazyCallerInfo) {
            count++;
            index--;
        }
        return count;
    }

    /**
     * Functional interface for lazy resolution of caller info.
     */
    @FunctionalInterface
    public interface CallerInfoResolver {
        CallerInfo resolve();
    }

    /**
     * Holds deferred caller info computation.
     */
    private record LazyCallerInfo(String packageName, CallerInfoResolver resolver) {
        CallerInfo resolve() {
            return resolver.resolve();
        }
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

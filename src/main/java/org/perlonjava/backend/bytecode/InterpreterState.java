package org.perlonjava.backend.bytecode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Maintains minimal interpreter execution state for stack trace generation.
 * Thread-safe via ThreadLocal. This enables proper Perl-level stack traces
 * when exceptions occur in interpreted code.
 * <p>
 * Design: Minimal overhead approach matching codegen's zero-overhead strategy.
 * Only tracks the call stack for stack trace generation, not PC updates.
 */
public class InterpreterState {
    private static final ThreadLocal<Deque<InterpreterFrame>> frameStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Represents a single interpreter call frame.
     * Contains minimal information needed for stack trace formatting.
     */
    public static class InterpreterFrame {
        public final InterpretedCode code;
        public final String packageName;
        public final String subroutineName;

        public InterpreterFrame(InterpretedCode code, String packageName, String subroutineName) {
            this.code = code;
            this.packageName = packageName;
            this.subroutineName = subroutineName;
        }
    }

    /**
     * Push a new interpreter frame onto the stack.
     * Called at entry to BytecodeInterpreter.execute().
     *
     * @param code           The InterpretedCode being executed
     * @param packageName    The package context (e.g., "main")
     * @param subroutineName The subroutine name (or null for main code)
     */
    public static void push(InterpretedCode code, String packageName, String subroutineName) {
        frameStack.get().push(new InterpreterFrame(code, packageName, subroutineName));
    }

    /**
     * Pop the current interpreter frame from the stack.
     * Called at exit from BytecodeInterpreter.execute() (in finally block).
     */
    public static void pop() {
        Deque<InterpreterFrame> stack = frameStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /**
     * Get the current (topmost) interpreter frame.
     * Used by ExceptionFormatter to detect interpreter execution.
     *
     * @return The current frame, or null if not executing interpreted code
     */
    public static InterpreterFrame current() {
        Deque<InterpreterFrame> stack = frameStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Get the complete interpreter call stack.
     * Used by caller() operator to introspect the call stack.
     *
     * @return A list of frames from most recent (index 0) to oldest
     */
    public static List<InterpreterFrame> getStack() {
        return new ArrayList<>(frameStack.get());
    }
}
package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

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
    /**
     * Thread-local RuntimeScalar holding the runtime current package name.
     *
     * <p><b>Design principle:</b> Package is a <em>compile-time</em> concept for name
     * resolution. All variable and subroutine names are fully qualified at compile time
     * by the ScopedSymbolTable / BytecodeCompiler. This field exists only for
     * <em>runtime introspection</em> — it does NOT affect name resolution.</p>
     *
     * <p>Used by:</p>
     * <ul>
     *   <li>{@code caller()} — to return the correct calling package</li>
     *   <li>{@code eval STRING} — to compile code in the right package (via
     *       BytecodeCompiler inheriting from ctx.symbolTable)</li>
     *   <li>{@code SET_PACKAGE} opcode ({@code package Foo;}) — sets it directly</li>
     *   <li>{@code PUSH_PACKAGE} opcode ({@code package Foo { }}) — saves via
     *       DynamicVariableManager then sets</li>
     * </ul>
     *
     * <p><b>Eval scoping:</b> Both eval STRING paths (EvalStringHandler for JVM bytecode,
     * RuntimeCode for interpreter) must push/pop this field via DynamicVariableManager
     * around eval execution. Without this, SET_PACKAGE opcodes inside the eval leak
     * into the caller's package state, breaking caller() and subsequent eval compilations.
     * This was the root cause of the signatures.t regression (601→446).</p>
     *
     * <p>Scoped package blocks ({@code package Foo { }}) are automatically restored
     * when the scope exits via POP_LOCAL_LEVEL (DynamicVariableManager.popToLocalLevel).</p>
     */
    public static final ThreadLocal<RuntimeScalar> currentPackage =
            ThreadLocal.withInitial(() -> new RuntimeScalar("main"));

    /**
     * Update the runtime current-package tracker. Exposed as a static helper
     * so JVM-compiled `package Foo;` sites can invoke it cheaply via
     * INVOKESTATIC.
     */
    public static void setCurrentPackageStatic(String name) {
        currentPackage.get().set(name);
    }
    private static final ThreadLocal<Deque<InterpreterFrame>> frameStack =
            ThreadLocal.withInitial(ArrayDeque::new);
    // Use ArrayList of mutable int holders for O(1) PC updates (no pop/push overhead)
    private static final ThreadLocal<ArrayList<int[]>> pcStack =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * Push a new interpreter frame onto the stack.
     * Called at entry to BytecodeInterpreter.execute().
     *
     * @param code           The InterpretedCode being executed
     * @param packageName    The package context (e.g., "main")
     * @param subroutineName The subroutine name (or null for main code)
     * @return The PC holder array for direct updates (avoids ThreadLocal lookups in hot loop)
     */
    public static int[] push(InterpretedCode code, String packageName, String subroutineName) {
        // Use pre-created frame from InterpretedCode when possible
        InterpreterFrame frame = code.getOrCreateFrame(packageName, subroutineName);
        return pushFrame(frame);
    }

    /**
     * Push a pre-created interpreter frame onto the stack.
     * This avoids allocating a new InterpreterFrame on every call.
     *
     * @param frame The pre-created InterpreterFrame
     * @return The PC holder array for direct updates
     */
    public static int[] pushFrame(InterpreterFrame frame) {
        frameStack.get().push(frame);
        int[] pcHolder = new int[]{0};  // Mutable holder for PC
        pcStack.get().add(pcHolder);
        return pcHolder;
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

        ArrayList<int[]> pcs = pcStack.get();
        if (!pcs.isEmpty()) {
            pcs.removeLast();
        }
    }

    public static void setCurrentPc(int pc) {
        ArrayList<int[]> pcs = pcStack.get();
        if (!pcs.isEmpty()) {
            pcs.getLast()[0] = pc;  // Direct mutation, no allocation
        }
    }

    /**
     * Push a new PC holder and return it for direct updates.
     * This avoids repeated ThreadLocal.get() calls in the hot interpreter loop.
     *
     * @return The int[1] holder for direct PC updates, or null if push failed
     */
    public static int[] pushAndGetPcHolder() {
        int[] holder = new int[]{0};
        pcStack.get().add(holder);
        return holder;
    }

    /**
     * Pop the PC holder. Called when execution completes.
     */
    public static void popPcHolder() {
        ArrayList<int[]> pcs = pcStack.get();
        if (!pcs.isEmpty()) {
            pcs.removeLast();
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

    public static List<Integer> getPcStack() {
        ArrayList<int[]> pcs = pcStack.get();
        ArrayList<Integer> result = new ArrayList<>(pcs.size());
        // Reverse order to match frameStack (Deque iterates most-recent-first,
        // but pcStack ArrayList stores oldest-first via add())
        for (int i = pcs.size() - 1; i >= 0; i--) {
            result.add(pcs.get(i)[0]);
        }
        return result;
    }

    /**
         * Represents a single interpreter call frame.
         * Contains minimal information needed for stack trace formatting.
         */
        public record InterpreterFrame(InterpretedCode code, String packageName, String subroutineName) {
    }

}

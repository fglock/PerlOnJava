package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.WarningBitsRegistry;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
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
    public static final CurrentPackageFacade currentPackage = new CurrentPackageFacade();

    public static final class CurrentPackageFacade {
        public RuntimeScalar get() {
            return org.perlonjava.runtime.runtimetypes.PerlRuntime.current()
                    .executionState().currentPackage;
        }
    }

    /**
     * Update the runtime current-package tracker. Exposed as a static helper
     * so JVM-compiled `package Foo;` sites can invoke it cheaply via
     * INVOKESTATIC.
     */
    public static void setCurrentPackageStatic(String name) {
        GlobalVariable.ensurePackageStash(name);
        currentPackage.get().set(name);
    }

    /**
     * Scoped variant of {@link #setCurrentPackageStatic}: pushes the current
     * package value onto the DynamicVariableManager stack so it will be
     * restored when the enclosing scope exits, then sets the new value.
     * <p>
     * Matches Perl 5 semantics: {@code package Foo;} is lexically scoped to
     * the enclosing block / eval / file. Without the push, a {@code package Foo;}
     * inside e.g. {@code Carp::caller_info}'s {@code { package DB; ... }} block
     * would leak "DB" past the block, corrupting subsequent {@code do FILE}
     * calls (which inherit the caller's package).
     */
    public static void setCurrentPackageLocal(String name) {
        GlobalVariable.ensurePackageStash(name);
        RuntimeScalar pkg = currentPackage.get();
        org.perlonjava.runtime.runtimetypes.DynamicVariableManager.pushLocalVariable(pkg);
        pkg.set(name);
    }
    private static Deque<InterpreterFrame> frameStack() {
        return org.perlonjava.runtime.runtimetypes.PerlRuntime.current()
                .executionState().interpreterFrames;
    }

    private static ArrayList<int[]> pcStack() {
        return org.perlonjava.runtime.runtimetypes.PerlRuntime.current()
                .executionState().interpreterPcs;
    }

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
        frameStack().push(frame);
        int[] pcHolder = new int[]{0};  // Mutable holder for PC
        pcStack().add(pcHolder);
        return pcHolder;
    }

    /**
     * Push a virtual eval-frame for caller() while an interpreted eval block is
     * active but has not created a separate Java/interpreter call frame.
     *
     * @return true if a frame was pushed and must be popped by the caller
     */
    public static boolean pushEvalFrameForCurrentInterpreter() {
        InterpreterFrame current = current();
        if (current == null || current.code() == null) {
            return false;
        }

        String packageName = currentPackage.get().toString();
        if (packageName == null || packageName.isEmpty()) {
            packageName = current.packageName();
        }

        frameStack().push(new InterpreterFrame(
                current.code(),
                packageName,
                "(eval)",
                true,
                WarningBitsRegistry.getRuntimeWarningBits()));

        ArrayList<int[]> pcs = pcStack();
        int currentPc = pcs.isEmpty() ? 0 : pcs.getLast()[0];
        pcs.add(new int[]{currentPc});
        return true;
    }

    /**
     * Pop the current interpreter frame from the stack.
     * Called at exit from BytecodeInterpreter.execute() (in finally block).
     */
    public static void pop() {
        Deque<InterpreterFrame> stack = frameStack();
        if (!stack.isEmpty()) {
            stack.pop();
        }

        ArrayList<int[]> pcs = pcStack();
        if (!pcs.isEmpty()) {
            pcs.removeLast();
        }
    }

    public static void setCurrentPc(int pc) {
        ArrayList<int[]> pcs = pcStack();
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
        pcStack().add(holder);
        return holder;
    }

    /**
     * Pop the PC holder. Called when execution completes.
     */
    public static void popPcHolder() {
        ArrayList<int[]> pcs = pcStack();
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
        Deque<InterpreterFrame> stack = frameStack();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Get the complete interpreter call stack.
     * Used by caller() operator to introspect the call stack.
     *
     * @return A list of frames from most recent (index 0) to oldest
     */
    public static List<InterpreterFrame> getStack() {
        return new ArrayList<>(frameStack());
    }

    public static List<Integer> getPcStack() {
        ArrayList<int[]> pcs = pcStack();
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
        public record InterpreterFrame(InterpretedCode code,
                                       String packageName,
                                       String subroutineName,
                                       boolean virtualEvalFrame,
                                       String warningBits) {
            public InterpreterFrame(InterpretedCode code, String packageName, String subroutineName) {
                this(code, packageName, subroutineName, false,
                        code == null ? null : code.warningBitsString);
            }

            public InterpreterFrame(InterpretedCode code, String packageName,
                                    String subroutineName, boolean virtualEvalFrame) {
                this(code, packageName, subroutineName, virtualEvalFrame,
                        code == null ? null : code.warningBitsString);
            }
    }

}

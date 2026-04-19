package org.perlonjava.runtime.runtimetypes;

import java.util.ArrayList;

/**
 * Runtime cleanup stack for my-variables during exception unwinding.
 * <p>
 * Parallels the {@code local} mechanism (InterpreterState save/restore):
 * my-variables are registered at creation time, and cleaned up on exception
 * via {@link #unwindTo(int)}. On normal scope exit, existing
 * {@code scopeExitCleanup} bytecodes handle cleanup, and {@link #popMark(int)}
 * discards the registrations without cleanup.
 * <p>
 * This ensures DESTROY fires for blessed objects held in my-variables when
 * {@code die} propagates through a subroutine that lacks an enclosing
 * {@code eval} in the same frame.
 * <p>
 * No {@code blessedObjectExists} guard is used in {@link #pushMark()},
 * {@link #register(Object)}, or {@link #popMark(int)} because a my-variable
 * may be created (and registered) BEFORE the first {@code bless()} call in
 * the same subroutine. The per-call overhead is negligible: O(1) amortized
 * ArrayList operations per my-variable, inlined by HotSpot.
 * <p>
 * Thread model: single-threaded (matches MortalList).
 *
 * @see MortalList#evalExceptionScopeCleanup(Object)
 */
public class MyVarCleanupStack {

    private static final ArrayList<Object> stack = new ArrayList<>();

    /**
     * Called at subroutine entry (in {@code RuntimeCode.apply()}).
     * Returns a mark position for later {@link #popMark(int)} or
     * {@link #unwindTo(int)}.
     *
     * @return mark position (always >= 0)
     */
    public static int pushMark() {
        return stack.size();
    }

    /**
     * Called by emitted bytecode when a my-variable is created.
     * Registers the variable for potential exception cleanup.
     * <p>
     * Always registers unconditionally — the variable may later hold a
     * blessed reference even if no bless() has happened yet at the point
     * of the {@code my} declaration. The {@code scopeExitCleanup} methods
     * are idempotent, so double-cleanup (normal exit + exception) is safe.
     *
     * @param var the RuntimeScalar, RuntimeHash, or RuntimeArray object
     */
    public static void register(Object var) {
        stack.add(var);
    }

    /**
     * Called by emitted bytecode at normal block scope exit AFTER
     * {@code scopeExitCleanup} has run. Removes the most recent entry
     * matching {@code var} (by object identity) so the static stack no
     * longer holds the scalar alive. Without this, block-scoped
     * my-variables stayed registered until the enclosing subroutine
     * returned, keeping their RuntimeBase targets alive past their
     * Perl-level scope and causing leaks visible through the
     * reachability walker.
     * <p>
     * Paired with {@link #register(Object)} — every register has a
     * matching unregister on normal exit, and a matching
     * {@link #unwindTo(int)} walk on exception exit.
     *
     * @param var the RuntimeScalar/Array/Hash previously registered
     */
    public static void unregister(Object var) {
        if (var == null) return;
        // Block-scoped my-vars pop in reverse declaration order, so
        // scan from the top of the stack for a fast amortized match.
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) == var) {
                stack.remove(i);
                return;
            }
        }
    }

    /**
     * Called on exception in {@code RuntimeCode.apply()}.
     * Runs {@link MortalList#evalExceptionScopeCleanup(Object)} for all
     * registered-but-not-yet-cleaned variables since the mark, in LIFO order.
     * <p>
     * Variables that were already cleaned up by normal scope exit have their
     * cleanup methods as no-ops (idempotent).
     *
     * @param mark the mark position from {@link #pushMark()}
     */
    public static void unwindTo(int mark) {
        for (int i = stack.size() - 1; i >= mark; i--) {
            Object var = stack.removeLast();
            if (var != null) {
                MortalList.evalExceptionScopeCleanup(var);
            }
        }
    }

    /**
     * Called on normal exit in {@code RuntimeCode.apply()}.
     * Discards registrations without running cleanup (normal scope-exit
     * bytecodes already handled it).
     *
     * @param mark the mark position from {@link #pushMark()}
     */
    public static void popMark(int mark) {
        while (stack.size() > mark) {
            stack.removeLast();
        }
    }
}

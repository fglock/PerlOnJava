package org.perlonjava.runtime.runtimetypes;

/**
 * Phase B2a (refcount_alignment_52leaks_plan.md): module-init guard.
 * <p>
 * A thread-local counter that tracks how deep we are inside
 * module-initialization code ({@code require} / {@code use} / BEGIN
 * blocks / {@code eval STRING}). Auto-triggered reachability sweeps
 * consult {@link #inModuleInit()} and skip firing when it's true —
 * module-init chains (like DBICTest::BaseResult's {@code use}
 * sequence) rely on weak-refed intermediate state remaining defined,
 * and firing a sweep mid-init corrupts that.
 * <p>
 * Expected usage: any code path that runs Perl-compiled code on
 * behalf of {@code require}/{@code use}/{@code BEGIN}/{@code eval
 * STRING} wraps the call in {@code try { enter(); ... } finally { exit(); }}.
 * <p>
 * Not thread-safe across JVM threads, but per-thread state is
 * correctly isolated. Matches PerlOnJava's single-threaded
 * execution model (see {@code weaken-destroy.md} §5 Limitations).
 */
public class ModuleInitGuard {

    // Use int[1] to avoid autoboxing on every enter/exit.
    private static final ThreadLocal<int[]> depth =
            ThreadLocal.withInitial(() -> new int[]{0});

    /** Enter module-initialization state (increments depth). */
    public static void enter() {
        depth.get()[0]++;
    }

    /** Exit module-initialization state (decrements depth). */
    public static void exit() {
        int[] d = depth.get();
        if (d[0] > 0) d[0]--;
    }

    /** True if currently inside require/use/BEGIN/eval-STRING execution. */
    public static boolean inModuleInit() {
        return depth.get()[0] > 0;
    }

    /** Diagnostic: current depth. */
    public static int currentDepth() {
        return depth.get()[0];
    }
}

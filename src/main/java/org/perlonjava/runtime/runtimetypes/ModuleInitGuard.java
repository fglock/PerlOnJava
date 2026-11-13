package org.perlonjava.runtime.runtimetypes;

/**
 * Tracks module-initialization depth for weak CODE cleanup decisions.
 *
 * <p>Some code-reference cleanup checks are deferred while a module is
 * compiling and publishing its package slots. A thread-local depth keeps those
 * checks from running in the middle of {@code require}, {@code use}, BEGIN, or
 * string eval initialization.
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

    /** Enter module-initialization state (increments depth). */
    public static void enter() {
        PerlRuntime.current().executionState().moduleInitDepth++;
    }

    /** Exit module-initialization state (decrements depth). */
    public static void exit() {
        ExecutionRuntimeState state = PerlRuntime.current().executionState();
        if (state.moduleInitDepth > 0) state.moduleInitDepth--;
    }

    /** True if currently inside require/use/BEGIN/eval-STRING execution. */
    public static boolean inModuleInit() {
        return PerlRuntime.current().executionState().moduleInitDepth > 0;
    }

    /** Diagnostic: current depth. */
    public static int currentDepth() {
        return PerlRuntime.current().executionState().moduleInitDepth;
    }
}

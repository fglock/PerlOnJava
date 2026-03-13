package org.perlonjava.runtime.runtimetypes;

/**
 * Functional interface for Perl subroutine invocation.
 * <p>
 * This interface replaces the MethodHandle-based approach for subroutine calls,
 * providing better type safety, improved JIT optimization, and cleaner code.
 * <p>
 * Generated Perl subroutine classes implement this interface directly, allowing
 * direct interface method calls instead of reflective MethodHandle.invoke() calls.
 * <p>
 * Performance benefits:
 * <ul>
 *   <li>Direct interface calls are faster than MethodHandle.invoke()</li>
 *   <li>Better JIT inlining opportunities</li>
 *   <li>No boxing/unboxing - return type known at compile time</li>
 *   <li>Simpler exception handling - no InvocationTargetException wrapping</li>
 * </ul>
 *
 * @see RuntimeCode
 */
@FunctionalInterface
public interface PerlSubroutine {
    /**
     * Invokes the Perl subroutine.
     *
     * @param args       the arguments passed to the subroutine (aliased as @_)
     * @param callContext the calling context (scalar, list, or void)
     * @return the result of the subroutine as a RuntimeList
     * @throws Exception if an error occurs during execution
     */
    RuntimeList apply(RuntimeArray args, int callContext) throws Exception;
}

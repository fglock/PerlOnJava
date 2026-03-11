package org.perlonjava.runtime.runtimetypes;

/**
 * DeferBlock wraps a code reference to be executed when a scope exits.
 * It implements DynamicState so it can be pushed onto the DynamicVariableManager
 * stack and have its code executed during scope cleanup (popToLocalLevel).
 *
 * <p>This enables Perl's {@code defer} feature which schedules code to run
 * at scope exit, regardless of how the scope is exited (normal flow, return,
 * exception, etc.).</p>
 *
 * <p>Multiple defer blocks in the same scope execute in LIFO (last-in, first-out)
 * order, which is naturally handled by the stack-based DynamicVariableManager.</p>
 *
 * <p>The defer block captures the enclosing subroutine's {@code @_} at registration
 * time, so the block sees the same {@code @_} as the enclosing scope (per Perl semantics).</p>
 *
 * @see DynamicVariableManager#pushLocalVariable(DynamicState)
 * @see DynamicVariableManager#popToLocalLevel(int)
 */
public class DeferBlock implements DynamicState {

    /**
     * The code reference (RuntimeScalar with type=CODE) to execute when the scope exits.
     */
    private final RuntimeScalar codeRef;

    /**
     * The captured @_ from the enclosing subroutine at the time defer was registered.
     * This ensures the defer block sees the same @_ as the enclosing scope.
     */
    private final RuntimeArray capturedArgs;

    /**
     * Constructs a DeferBlock with the given code reference.
     * Uses an empty @_ (for blocks not inside a subroutine).
     *
     * @param codeRef the code reference (RuntimeScalar with type=CODE) to execute at scope exit
     */
    public DeferBlock(RuntimeScalar codeRef) {
        this(codeRef, new RuntimeArray());
    }

    /**
     * Constructs a DeferBlock with the given code reference and captured @_.
     *
     * @param codeRef the code reference (RuntimeScalar with type=CODE) to execute at scope exit
     * @param capturedArgs the @_ to pass when executing the defer block
     */
    public DeferBlock(RuntimeScalar codeRef, RuntimeArray capturedArgs) {
        this.codeRef = codeRef;
        this.capturedArgs = capturedArgs;
    }

    /**
     * Called when this DeferBlock is pushed onto the dynamic variable stack.
     * For defer blocks, this is a no-op since we don't need to save any state -
     * we just need to register the block for later execution.
     */
    @Override
    public void dynamicSaveState() {
        // Nothing to save - defer just registers code for later execution
    }

    /**
     * Called when this DeferBlock is popped from the dynamic variable stack
     * during scope cleanup. This executes the defer block's code.
     *
     * <p>Exceptions thrown by the defer block are propagated to the caller.
     * If multiple defer blocks throw exceptions, the last exception wins
     * (per Perl semantics).</p>
     */
    @Override
    public void dynamicRestoreState() {
        // Execute the defer block by calling the code reference with the captured @_
        RuntimeCode.apply(codeRef, capturedArgs, RuntimeContextType.VOID);
    }
}

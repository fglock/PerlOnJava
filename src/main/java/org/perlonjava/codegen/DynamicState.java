package org.perlonjava.codegen;

/**
 * The {@code DynamicState} interface provides a contract for objects that
 * need to manage their state dynamically. Implementing classes are expected
 * to provide mechanisms to save and restore their internal state.
 *
 * <p>This can be particularly useful in scenarios where an object's state
 * needs to be temporarily altered and then reverted back to its original
 * state, such as in undo/redo operations, transactional processing, or
 * during complex computations where intermediate states are necessary.</p>
 *
 * <p>Implementers of this interface should ensure that the state saving and
 * restoring operations are efficient and do not introduce significant
 * overhead.</p>
 */
public interface DynamicState {

    /**
     * Saves the current state of the object.
     *
     * <p>This method should capture all necessary information about the
     * object's current state so that it can be restored later. The exact
     * details of what constitutes the "state" will depend on the specific
     * implementation and the context in which the object is used.</p>
     *
     * <p>Implementers should consider thread-safety and ensure that the
     * state is saved consistently, especially if the object is accessed
     * concurrently by multiple threads.</p>
     */
    void dynamicSaveState();

    /**
     * Restores the object's state to the last saved state.
     *
     * <p>This method should revert the object back to the state that was
     * captured by the most recent call to {@link #dynamicSaveState()}. If no state
     * has been saved yet, the behavior of this method is undefined and
     * should be documented by the implementing class.</p>
     *
     * <p>Implementers should ensure that the restoration process is
     * efficient and does not leave the object in an inconsistent state,
     * especially in the presence of concurrent modifications.</p>
     */
    void dynamicRestoreState();
}

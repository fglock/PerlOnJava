package org.perlonjava.runtime;

import java.util.Stack;

/**
 * TiedVariableBase provides a common base class for all tied variable types in Perl.
 * This includes tied scalars ($v), array elements ($a[10]), and hash elements ($h{a}).
 * 
 * <p>All tied variables share common behavior:</p>
 * <ul>
 * <li>Delegation of operations to tie handler methods (FETCH, STORE, etc.)</li>
 * <li>Dynamic state management for local() operations</li>
 * <li>Lazy evaluation through vivification</li>
 * <li>Proper cleanup through DESTROY and UNTIE</li>
 * </ul>
 */
public abstract class TiedVariableBase extends RuntimeBaseProxy {
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();
    
    /** The tied object (handler) that implements the tie interface methods. */
    protected final RuntimeScalar tiedObject;
    
    /** The package name that this variable is tied to. */
    protected final String tiedPackage;

    /**
     * Creates a new TiedVariableBase instance.
     *
     * @param tiedObject  the blessed object that handles tie operations
     * @param tiedPackage the package name this variable is tied to
     */
    public TiedVariableBase(RuntimeScalar tiedObject, String tiedPackage) {
        super();
        this.tiedObject = tiedObject;
        this.tiedPackage = tiedPackage;
    }

    /**
     * Calls a tie method on the tied object.
     * 
     * @param method the method name to call
     * @param args   additional arguments to pass to the method
     * @return the result of the method call
     */
    protected RuntimeScalar tieCall(String method, RuntimeBase... args) {
        // Prepare arguments: self + method-specific args
        RuntimeBase[] allArgs = new RuntimeBase[args.length + 1];
        allArgs[0] = tiedObject;
        System.arraycopy(args, 0, allArgs, 1, args.length);

        // Call the Perl method
        return RuntimeCode.call(
                tiedObject,
                new RuntimeScalar(method),
                tiedPackage,
                new RuntimeArray(allArgs),
                RuntimeContextType.SCALAR
        ).getFirst();
    }

    /**
     * Calls a tie method if it exists in the tied object's class hierarchy.
     * Used for optional methods like DESTROY and UNTIE.
     * 
     * @param methodName the method name to call
     * @return the result of the method call, or undef if method doesn't exist
     */
    protected RuntimeScalar tieCallIfExists(String methodName) {
        // Check if method exists in the class hierarchy
        RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(methodName, tiedPackage, null, 0);
        if (method == null) {
            // Method doesn't exist, return undef
            return RuntimeScalarCache.scalarUndef;
        }

        // Method exists, call it
        return RuntimeCode.apply(method, new RuntimeArray(tiedObject), RuntimeContextType.SCALAR).getFirst();
    }

    /**
     * Fetches the current value from the tied variable.
     * This method must be implemented by subclasses to provide the appropriate
     * arguments to the FETCH method.
     * 
     * @return the fetched value
     */
    public abstract RuntimeScalar tiedFetch();

    /**
     * Stores a value into the tied variable.
     * This method must be implemented by subclasses to provide the appropriate
     * arguments to the STORE method.
     * 
     * @param value the value to store
     * @return the result of the STORE operation
     */
    public abstract RuntimeScalar tiedStore(RuntimeScalar value);

    /**
     * Vivifies the variable by fetching its current value from the tied object.
     * This ensures the proxy has the most up-to-date value.
     */
    @Override
    void vivify() {
        RuntimeScalar fetchedValue = tiedFetch();
        this.type = fetchedValue.type;
        this.value = fetchedValue.value;
        this.blessId = fetchedValue.blessId;
    }

    /**
     * Sets the value by calling STORE on the tied object.
     *
     * @param value The new value to set.
     * @return The result of the STORE operation.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        return tiedStore(value);
    }

    /**
     * Called when the tied variable goes out of scope (delegates to DESTROY if exists).
     */
    public RuntimeScalar tiedDestroy() {
        return tieCallIfExists("DESTROY");
    }

    /**
     * Unties the variable (delegates to UNTIE if exists).
     */
    public RuntimeScalar tiedUntie() {
        return tieCallIfExists("UNTIE");
    }

    // Common delegated operations that require vivification
    
    @Override
    public RuntimeScalar defined() {
        vivify();
        return super.defined();
    }

    @Override
    public boolean getDefinedBoolean() {
        vivify();
        return super.getDefinedBoolean();
    }

    @Override
    public int getInt() {
        vivify();
        return super.getInt();
    }

    @Override
    public double getDouble() {
        vivify();
        return super.getDouble();
    }

    @Override
    public RuntimeScalar getNumber() {
        vivify();
        return super.getNumber();
    }

    @Override
    public String toString() {
        vivify();
        return super.toString();
    }

    @Override
    public boolean getBoolean() {
        vivify();
        return super.getBoolean();
    }

    @Override
    public RuntimeScalar scalar() {
        vivify();
        return this;
    }

    @Override
    public boolean isString() {
        vivify();
        return type == RuntimeScalarType.STRING;
    }

    /**
     * Creates a copy of the current value after vivification.
     * 
     * @return a new RuntimeScalar with the current value
     */
    public RuntimeScalar fetch() {
        vivify();
        return new RuntimeScalar(this);
    }

    // Auto-increment/decrement operations
    
    @Override
    public RuntimeScalar preAutoIncrement() {
        RuntimeScalar incremented = this.fetch().preAutoIncrement();
        return this.set(incremented);
    }

    @Override
    public RuntimeScalar postAutoIncrement() {
        RuntimeScalar old = this.fetch();
        RuntimeScalar incremented = old.postAutoIncrement();
        this.set(this); // Store the incremented value
        return old; // Return the old value
    }

    @Override
    public RuntimeScalar preAutoDecrement() {
        RuntimeScalar decremented = this.fetch().preAutoDecrement();
        return this.set(decremented);
    }

    @Override
    public RuntimeScalar postAutoDecrement() {
        RuntimeScalar old = this.fetch();
        RuntimeScalar decremented = old.postAutoDecrement();
        this.set(this); // Store the decremented value
        return old; // Return the old value
    }

    // Array and scalar addition operations
    
    @Override
    public void addToArray(RuntimeArray array) {
        vivify();
        super.addToArray(array);
    }

    @Override
    public RuntimeScalar addToScalar(RuntimeScalar v) {
        vivify();
        return super.addToScalar(v);
    }

    // Dynamic state management for local() operations
    
    /**
     * Saves the current state of the tied variable.
     * This fetches the current value from the tied object and saves it.
     */
    @Override
    public void dynamicSaveState() {
        // Fetch current value from tied object
        RuntimeScalar currentValue = tiedFetch();

        // Create a new RuntimeScalar to save the current state
        RuntimeScalar currentState = new RuntimeScalar();
        currentState.type = currentValue.type;
        currentState.value = currentValue.value;
        currentState.blessId = currentValue.blessId;

        dynamicStateStack.push(currentState);
    }

    /**
     * Restores the most recently saved state of the tied variable.
     * This stores the saved value back to the tied object.
     */
    @Override
    public void dynamicRestoreState() {
        if (!dynamicStateStack.isEmpty()) {
            // Pop the most recent saved state from the stack
            RuntimeScalar previousState = dynamicStateStack.pop();

            // Store the previous value back to the tied object
            tiedStore(previousState);

            // Update our local copy if it exists
            if (lvalue != null) {
                lvalue.set(previousState);
                lvalue.blessId = previousState.blessId;
                this.type = lvalue.type;
                this.value = lvalue.value;
                this.blessId = previousState.blessId;
            }
        }
    }

    // Getters
    
    public RuntimeScalar getTiedObject() {
        return tiedObject;
    }

    public String getTiedPackage() {
        return tiedPackage;
    }
}


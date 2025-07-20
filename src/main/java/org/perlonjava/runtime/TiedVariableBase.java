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
    /** The tied object (handler) that implements the tie interface methods. */
    protected final RuntimeScalar self;
    
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
        this.self = tiedObject;
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
        // Call the Perl method
        return RuntimeCode.call(
                self,
                new RuntimeScalar(method),
                tiedPackage,
                new RuntimeArray(args),
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
        return RuntimeCode.apply(method, new RuntimeArray(self), RuntimeContextType.SCALAR).getFirst();
    }

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
        return this.tiedStore(value);
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
    
    public RuntimeScalar getSelf() {
        return self;
    }

    public String getTiedPackage() {
        return tiedPackage;
    }
}


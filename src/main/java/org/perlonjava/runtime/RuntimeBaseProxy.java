package org.perlonjava.runtime;

/**
 * An abstract class that serves as a proxy for a RuntimeScalar object.
 * It provides methods to manipulate and access the underlying scalar value.
 * The class ensures that the underlying scalar is properly initialized
 * before any operations are performed on it.
 * <p>
 * Note: The value is created with the value `undef`.
 */
public abstract class RuntimeBaseProxy extends RuntimeScalar {

    // The underlying scalar value that this proxy represents.
    RuntimeScalar lvalue;

    /**
     * "Blesses" a Perl reference into an object by associating it with a class name.
     * This method is used to convert a Perl reference into an object of a specified class.
     *
     * @param runtimeBaseProxy
     * @param className        A RuntimeScalar representing the name of the class to bless the reference into.
     * @return A RuntimeScalar representing the blessed object.
     */
    public static RuntimeScalar bless(RuntimeBaseProxy runtimeBaseProxy, RuntimeScalar className) {
        runtimeBaseProxy.vivify();
        RuntimeScalar ret = RuntimeScalar.bless(runtimeBaseProxy.lvalue, className);
        runtimeBaseProxy.type = runtimeBaseProxy.lvalue.type;
        runtimeBaseProxy.value = runtimeBaseProxy.lvalue.value;
        return ret;
    }

    /**
     * Ensures that the underlying scalar value is initialized.
     * This method must be implemented by subclasses to provide
     * the specific initialization logic.
     */
    abstract void vivify();

    /**
     * Sets the value of the underlying scalar.
     *
     * @param value The new value to set.
     * @return The updated underlying scalar.
     */
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        vivify(); // Ensure the scalar is initialized.
        this.lvalue.set(value);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return lvalue;
    }

    @Override
    public RuntimeScalar set(String value) {
        return set(new RuntimeScalar(value));
    }

    @Override
    public RuntimeScalar set(long value) {
        return set(new RuntimeScalar(value));
    }

    @Override
    public RuntimeScalar set(int value) {
        return set(new RuntimeScalar(value));
    }

    @Override
    public RuntimeScalar set(boolean value) {
        return set(new RuntimeScalar(value));
    }

    /**
     * Undefines the underlying scalar value.
     *
     * @return The updated underlying scalar after undefining.
     */
    public RuntimeScalar undefine() {
        vivify();
        RuntimeScalar ret = lvalue.undefine();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Removes the last character from the underlying scalar value.
     *
     * @return The updated underlying scalar after chopping.
     */
    public RuntimeScalar chop() {
        vivify();
        RuntimeScalar ret = lvalue.chop();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Removes the trailing newline from the underlying scalar value.
     *
     * @return The updated underlying scalar after chomp.
     */
    public RuntimeScalar chomp() {
        vivify();
        RuntimeScalar ret = lvalue.chomp();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Retrieves a value from a hash using the given index.
     * Implements `$v->{key}`
     *
     * @param index The index to use for retrieval.
     * @return The value retrieved from the hash.
     */
    @Override
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        vivify();
        RuntimeScalar ret = lvalue.hashDerefGet(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Retrieves a value from an array using the given index.
     * Implements `$v->[key]`
     *
     * @param index The index to use for retrieval.
     * @return The value retrieved from the array.
     */
    @Override
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        vivify();
        RuntimeScalar ret = lvalue.arrayDerefGet(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a pre-increment operation on the underlying scalar.
     *
     * @return The updated underlying scalar after pre-increment.
     */
    @Override
    public RuntimeScalar preAutoIncrement() {
        vivify();
        RuntimeScalar ret = lvalue.preAutoIncrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a post-increment operation on the underlying scalar.
     *
     * @return The updated underlying scalar after post-increment.
     */
    public RuntimeScalar postAutoIncrement() {
        vivify();
        RuntimeScalar ret = lvalue.postAutoIncrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a pre-decrement operation on the underlying scalar.
     *
     * @return The updated underlying scalar after pre-decrement.
     */
    @Override
    public RuntimeScalar preAutoDecrement() {
        vivify();
        RuntimeScalar ret = lvalue.preAutoDecrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    /**
     * Performs a post-decrement operation on the underlying scalar.
     *
     * @return The updated underlying scalar after post-decrement.
     */
    public RuntimeScalar postAutoDecrement() {
        vivify();
        RuntimeScalar ret = lvalue.postAutoDecrement();
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    public void setBlessId(int blessId) {
        vivify();
        lvalue.setBlessId(blessId);
        this.blessId = blessId;
    }
}
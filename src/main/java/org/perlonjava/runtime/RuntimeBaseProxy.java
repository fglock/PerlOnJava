package org.perlonjava.runtime;

import org.perlonjava.operators.ReferenceOperators;

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
     * @param runtimeBaseProxy The RuntimeBaseProxy object to bless.
     * @param className        A RuntimeScalar representing the name of the class to bless the reference into.
     * @return A RuntimeScalar representing the blessed object.
     */
    public static RuntimeScalar bless(RuntimeBaseProxy runtimeBaseProxy, RuntimeScalar className) {
        runtimeBaseProxy.vivify();
        RuntimeScalar ret = ReferenceOperators.bless(runtimeBaseProxy.lvalue, className);
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

    @Override
    public RuntimeHash hashDeref() {
        vivify();  // Ensure the scalar exists in parent hash
        RuntimeHash result = lvalue.hashDeref();  // Delegate to the actual scalar
        // Update proxy's type and value to match lvalue after vivification
        this.type = lvalue.type;
        this.value = lvalue.value;
        return result;
    }

    @Override
    public RuntimeArray arrayDeref() {
        vivify();  // Ensure the scalar exists in parent hash
        RuntimeArray result = lvalue.arrayDeref();  // Delegate to the actual scalar
        // Update proxy's type and value to match lvalue after vivification
        this.type = lvalue.type;
        this.value = lvalue.value;
        return result;
    }

    @Override
    public RuntimeArray arrayDerefNonStrict(String packageName) {
        vivify();  // Ensure the scalar exists in parent hash/array
        RuntimeArray result = lvalue.arrayDerefNonStrict(packageName);  // Delegate to the actual scalar
        // Update proxy's type and value to match lvalue after vivification
        this.type = lvalue.type;
        this.value = lvalue.value;
        return result;
    }

    @Override
    public RuntimeHash hashDerefNonStrict(String packageName) {
        vivify();  // Ensure the scalar exists in parent hash/array
        RuntimeHash result = lvalue.hashDerefNonStrict(packageName);  // Delegate to the actual scalar
        // Update proxy's type and value to match lvalue after vivification
        this.type = lvalue.type;
        this.value = lvalue.value;
        return result;
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
        // Don't vivify read-only scalars (like constants from constant subroutines)
        // This matches Perl's behavior where FILE1->[0] returns undef without error
        // If lvalue is null and this is read-only, just return undef
        if (lvalue == null && this instanceof RuntimeScalarReadOnly) {
            return RuntimeScalarCache.scalarUndef;
        }
        vivify();
        RuntimeScalar ret = lvalue.arrayDerefGet(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    // Method to implement `exists $v->{key}`
    @Override
    public RuntimeScalar hashDerefExists(RuntimeScalar index) {
        vivify();
        RuntimeScalar ret = lvalue.hashDerefExists(index);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return ret;
    }

    // Non-strict versions (allow symbolic references)
    public RuntimeScalar hashDerefGetNonStrict(RuntimeScalar index, String currentPackage) {
        vivify();
        return lvalue.hashDerefGetNonStrict(index, currentPackage);
    }

    public RuntimeScalar hashDerefDeleteNonStrict(RuntimeScalar index, String currentPackage) {
        vivify();
        return lvalue.hashDerefDeleteNonStrict(index, currentPackage);
    }

    public RuntimeScalar hashDerefExistsNonStrict(RuntimeScalar index, String currentPackage) {
        vivify();
        return lvalue.hashDerefExistsNonStrict(index, currentPackage);
    }

    public RuntimeScalar arrayDerefGetNonStrict(RuntimeScalar index, String currentPackage) {
        vivify();
        return lvalue.arrayDerefGetNonStrict(index, currentPackage);
    }

    public RuntimeScalar arrayDerefDeleteNonStrict(RuntimeScalar index, String currentPackage) {
        vivify();
        return lvalue.arrayDerefDeleteNonStrict(index, currentPackage);
    }

    public RuntimeScalar arrayDerefExistsNonStrict(RuntimeScalar index, String currentPackage) {
        vivify();
        return lvalue.arrayDerefExistsNonStrict(index, currentPackage);
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
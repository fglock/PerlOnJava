package org.perlonjava.runtime.runtimetypes;

// This interface defines the behavior for scalar references in the runtime.
// Any class that implements this interface must provide implementations for the specified methods.
public interface RuntimeScalarReference {

    /**
     * Returns a string representation of the scalar reference.
     * This method is intended to provide a human-readable format of the reference.
     *
     * @return A string representation of the scalar reference, looks like "REF(HEXNUMBER)"
     */
    String toStringRef();

    /**
     * Retrieves the int value of the scalar reference.
     * If the value cannot be converted to a int, it should return a default or converted value.
     *
     * @return The int representation of the scalar reference. In general, this is the hash value of the object.
     */
    int getIntRef();

    /**
     * Retrieves the double value of the scalar reference.
     * Similar to getIntRef, this method converts the scalar value to a double.
     *
     * @return The double representation of the scalar reference.
     */
    double getDoubleRef();

    /**
     * Retrieves the boolean value of the scalar reference.
     * This method should return true or false based on the underlying value of the reference.
     *
     * @return The boolean representation of the scalar reference. In general, all references are `true`.
     */
    boolean getBooleanRef();
}


package org.perlonjava.runtime;

/**
 * The RuntimeBaseEntity class is a base class for scalar, hash, and array variables.
 */
public abstract class RuntimeBaseEntity implements RuntimeDataProvider {
    // Index to the class that this reference belongs
    public int blessId;

    // Add the object to a list
    public void addToList(RuntimeList list) {
        list.add(this);
    }

    // Get the array value of the object as aliases
    public RuntimeArray getArrayOfAlias() {
        RuntimeArray arr = new RuntimeArray();
        this.setArrayOfAlias(arr);
        return arr;
    }

}


package org.perlonjava.runtime;

public class RuntimeArrayProxy extends RuntimeScalar {
    private final RuntimeArray parent;
    private final int key;

    public RuntimeArrayProxy(RuntimeArray parent, int key) {
        super();
        this.parent = parent;
        this.key = key;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    // Setters
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (key < parent.elements.size()) {
            ((RuntimeScalar) parent.elements.get(key)).set(value);
        } else {
            for (int i = parent.elements.size(); i < key; i++) {
                parent.elements.add(i, new RuntimeScalar());
            }
            parent.elements.add(key, value.clone());
        }
        RuntimeScalar lvalue = (RuntimeScalar) parent.elements.get(key);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return lvalue;
    }

    // Method to implement `$v->{key}`
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        if (key >= parent.elements.size()) {
            for (int i = parent.elements.size(); i <= key; i++) {
                parent.elements.add(i, new RuntimeScalar());
            }
        }
        return ((RuntimeScalar) parent.elements.get(key)).hashDerefGet(index);
    }

    // Method to implement `$v->[key]`
    public RuntimeScalar arrayDerefGet(RuntimeScalar index) {
        if (key >= parent.elements.size()) {
            for (int i = parent.elements.size(); i <= key; i++) {
                parent.elements.add(i, new RuntimeScalar());
            }
        }
        return ((RuntimeScalar) parent.elements.get(key)).arrayDerefGet(index);
    }

    /*

    @Override
    public Object getValue() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.getValue() : null;
    }

    @Override
    public void setValue(Object newValue) {
        parent.set(key, new RuntimeScalar(newValue));
    }

    @Override
    public RuntimeScalarType getType() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.getType() : RuntimeScalarType.UNDEF;
    }

    @Override
    public String getString() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.getString() : "";
    }

    @Override
    public int getInteger() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.getInteger() : 0;
    }

    @Override
    public double getFloat() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.getFloat() : 0.0;
    }

    @Override
    public boolean getBoolean() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.getBoolean() : false;
    }

    @Override
    public RuntimeScalar getReference() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.getReference() : this;
    }

    @Override
    public RuntimeScalar dereference() {
        return this; // A proxy is already a kind of reference
    }

    @Override
    public RuntimeScalar not() {
        return super.not(); // Use the parent implementation
    }

    @Override
    public RuntimeScalar add(RuntimeScalar other) {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.add(other) : super.add(other);
    }

    // Implement other arithmetic operations similarly (subtract, multiply, etc.)

    @Override
    public String toString() {
        RuntimeScalar element = parent.get(key);
        return (element != null) ? element.toString() : "";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RuntimeHashProxy that = (RuntimeHashProxy) obj;
        return parent.equals(that.parent) && key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return 31 * parent.hashCode() + key.hashCode();
    }

    */
}


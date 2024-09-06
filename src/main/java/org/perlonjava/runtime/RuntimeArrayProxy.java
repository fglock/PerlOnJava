package org.perlonjava.runtime;

public class RuntimeArrayProxy extends RuntimeBaseEntity implements RuntimeScalarBehavior {
    private final RuntimeList parent;
    private final int index;

    public RuntimeArrayProxy(RuntimeList parent, int index) {
        this.parent = parent;
        this.index = index;
    }

    @Override
    public Object getValue() {
        RuntimeScalar element = parent.get(index);
        return (element != null) ? element.getValue() : null;
    }

    @Override
    public void setValue(Object newValue) {
        parent.set(index, new RuntimeScalar(newValue));
    }

    @Override
    public boolean getBoolean() {
        Object value = getValue();
        return (value != null) && !value.toString().isEmpty() && !value.toString().equals("0");
    }

    // Implement other methods from RuntimeScalarBehavior and RuntimeBaseEntity as needed
}


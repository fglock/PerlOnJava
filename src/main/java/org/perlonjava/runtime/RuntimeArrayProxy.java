package org.perlonjava.runtime;

public class RuntimeArrayProxy extends RuntimeBaseProxy {
    private final RuntimeArray parent;
    private final int key;

    public RuntimeArrayProxy(RuntimeArray parent, int key) {
        super();
        this.parent = parent;
        this.key = key;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    void vivify() {
        if (lvalue == null) {
            if (key >= parent.elements.size()) {
                for (int i = parent.elements.size(); i <= key; i++) {
                    parent.elements.add(i, new RuntimeScalar());
                }
            }
            lvalue = parent.elements.get(key);
        }
    }
}


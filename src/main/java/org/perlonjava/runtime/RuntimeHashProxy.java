package org.perlonjava.runtime;

public class RuntimeHashProxy extends RuntimeBaseProxy {
    private final RuntimeHash parent;
    private final String key;

    public RuntimeHashProxy(RuntimeHash parent, String key) {
        super();
        this.parent = parent;
        this.key = key;
        // Note: this.type is RuntimeScalarType.UNDEF
    }

    void vivify() {
        if (lvalue == null) {
            if (!parent.elements.containsKey(key)) {
                parent.elements.put(key, new RuntimeScalar());
            }
            lvalue = (RuntimeScalar) parent.elements.get(key);
        }
    }
}


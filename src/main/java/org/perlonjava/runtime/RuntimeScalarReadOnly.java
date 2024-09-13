package org.perlonjava.runtime;

public class RuntimeScalarReadOnly extends RuntimeBaseProxy {

    public RuntimeScalarReadOnly() {
        super();
    }

    @Override
    void vivify() {
        throw new RuntimeException("Can't modify constant item");
    }
}


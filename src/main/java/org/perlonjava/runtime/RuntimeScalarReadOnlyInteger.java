package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeScalarCache.getIntegerScalar;

public class RuntimeScalarReadOnlyInteger extends RuntimeScalarReadOnly {

    final int i;
    final String s;

    public RuntimeScalarReadOnlyInteger(int i) {
        super();
        this.i = i;
        this.s = Integer.toString(i);
        this.value = i;
        this.type = RuntimeScalarType.INTEGER;
    }

    @Override
    public int getInt() {
        return i;
    }

    @Override
    public String toString() {
        return s;
    }
}


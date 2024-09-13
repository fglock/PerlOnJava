package org.perlonjava.runtime;

public class RuntimeScalarReadOnly extends RuntimeBaseProxy {

    final boolean b;
    final int i;
    final String s;

    public RuntimeScalarReadOnly() {
        super();
        this.b = false;
        this.i = 0;
        this.s = "";
        this.value = null;
        this.type = RuntimeScalarType.UNDEF;
    }

    public RuntimeScalarReadOnly(int i) {
        super();
        this.b = (i != 0);
        this.i = i;
        this.s = Integer.toString(i);
        this.value = i;
        this.type = RuntimeScalarType.INTEGER;
    }

    public RuntimeScalarReadOnly(boolean b) {
        super();
        this.b = b;
        this.i = b ? 1 : 0;
        this.s = b ? "1" : "";
        this.value = i;
        this.type = RuntimeScalarType.INTEGER;
    }

    @Override
    void vivify() {
        throw new RuntimeException("Can't modify constant item");
    }

    @Override
    public int getInt() {
        return i;
    }

    @Override
    public double getDouble() {
        return i;
    }

    @Override
    public String toString() {
        return s;
    }

    @Override
    public boolean getBoolean() {
        return b;
    }
}

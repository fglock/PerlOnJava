package org.perlonjava.runtime;

public class RuntimeScalarReadOnly extends RuntimeBaseProxy {

    final boolean b;
    final int i;
    final String s;
    final double d;

    public RuntimeScalarReadOnly() {
        super();
        this.b = false;
        this.i = 0;
        this.s = "";
        this.d = 0;
        this.value = null;
        this.type = RuntimeScalarType.UNDEF;
    }

    public RuntimeScalarReadOnly(int i) {
        super();
        this.b = (i != 0);
        this.i = i;
        this.s = Integer.toString(i);
        this.d = i;
        this.value = i;
        this.type = RuntimeScalarType.INTEGER;
    }

    public RuntimeScalarReadOnly(boolean b) {
        super();
        this.b = b;
        this.i = b ? 1 : 0;
        this.s = b ? "1" : "";
        this.d = b ? 1 : 0;
        this.value = i;
        this.type = RuntimeScalarType.INTEGER;
    }

    public RuntimeScalarReadOnly(String s) {
        super();
        RuntimeScalar temp = new RuntimeScalar(s);
        this.b = "".equals(s);
        this.i = temp.getInt();
        this.s = s;
        this.d = temp.getDouble();
        this.value = s;
        this.type = RuntimeScalarType.STRING;
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
        return d;
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

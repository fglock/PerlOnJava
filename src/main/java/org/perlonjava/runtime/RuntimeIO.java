package org.perlonjava.runtime;

public class RuntimeIO implements RuntimeScalarReference {

    public RuntimeIO() {
    }

    public String toStringRef() {
        return "IO(" + this.hashCode() + ")";
    }

    public int getIntRef() {
        return this.hashCode();
    }

    public double getDoubleRef() {
        return this.hashCode();
    }

    public boolean getBooleanRef() {
        return true;
    }
}

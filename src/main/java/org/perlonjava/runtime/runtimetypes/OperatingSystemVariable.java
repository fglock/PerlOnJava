package org.perlonjava.runtime.runtimetypes;

/** Perl's $^O: mutable for compatibility, but rejects tainted assignments. */
public final class OperatingSystemVariable extends RuntimeScalar {
    public OperatingSystemVariable(String value) {
        super(value);
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        RuntimeScalar.checkTaint(value, "assigning to $^O");
        return super.set(value);
    }
}

package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.nativ.LinuxProcessTitle;

/** Perl's per-runtime {@code $0} scalar with its process-wide Linux title side effect. */
public final class ProgramNameVariable extends RuntimeScalar {
    public ProgramNameVariable initialize(String value) {
        super.set(value);
        return this;
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        RuntimeScalar result = super.set(value);
        LinuxProcessTitle.set(toString());
        return result;
    }

    @Override
    public RuntimeScalar set(String value) {
        RuntimeScalar result = super.set(value);
        LinuxProcessTitle.set(toString());
        return result;
    }
}

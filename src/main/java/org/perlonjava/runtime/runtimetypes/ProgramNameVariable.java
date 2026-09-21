package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.nativ.LinuxProcessTitle;
import org.perlonjava.runtime.operators.WarnDie;

import java.nio.charset.StandardCharsets;

/** Perl's per-runtime {@code $0} scalar with its process-wide Linux title side effect. */
public final class ProgramNameVariable extends RuntimeScalar {
    public ProgramNameVariable initialize(String value) {
        setProgramName(value);
        return this;
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        return setProgramName(value == null ? null : value.toString());
    }

    @Override
    public RuntimeScalar set(String value) {
        return setProgramName(value);
    }

    private RuntimeScalar setProgramName(String value) {
        if (value != null && value.codePoints().anyMatch(codePoint -> codePoint > 0xff)) {
            WarnDie.warn(new RuntimeScalar("Wide character in $0\n"),
                    RuntimeScalarCache.scalarEmptyString);
            super.set(new RuntimeScalar(value.getBytes(StandardCharsets.UTF_8)));
        } else {
            super.set(value);
        }
        LinuxProcessTitle.set(value == null ? "" : value);
        return this;
    }
}

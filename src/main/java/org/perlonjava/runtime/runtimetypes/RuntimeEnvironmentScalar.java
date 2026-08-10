package org.perlonjava.runtime.runtimetypes;

import java.math.BigInteger;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.BYTE_STRING;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.STRING;

/** Scalar stored in Perl's magical %ENV hash. */
final class RuntimeEnvironmentScalar extends RuntimeScalar {
    RuntimeEnvironmentScalar(RuntimeScalar value) {
        super(value);
        normalizeEnvironmentString();
        // Values imported from the process environment enter Perl as tainted.
        // Later assignments preserve the assigned value's own taint state.
        tainted = true;
    }

    private RuntimeScalar normalizeEnvironmentString() {
        if (type == STRING) {
            String string = toString();
            boolean latin1 = true;
            for (int i = 0; i < string.length(); i++) {
                if (string.charAt(i) > 0xff) {
                    latin1 = false;
                    break;
                }
            }
            if (latin1) {
                type = BYTE_STRING;
            }
        } else if (type != RuntimeScalarType.UNDEF && type < RuntimeScalarType.GLOB) {
            value = toString();
            type = BYTE_STRING;
            numericLiteralText = null;
            numericContextSeen = false;
        }
        return this;
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        super.set(value);
        return normalizeEnvironmentString();
    }

    @Override
    public RuntimeScalar set(String value) {
        super.set(value);
        return normalizeEnvironmentString();
    }

    @Override
    public RuntimeScalar set(int value) {
        super.set(value);
        return normalizeEnvironmentString();
    }

    @Override
    public RuntimeScalar set(long value) {
        super.set(value);
        return normalizeEnvironmentString();
    }

    @Override
    public RuntimeScalar set(BigInteger value) {
        super.set(value);
        return normalizeEnvironmentString();
    }

    @Override
    public RuntimeScalar set(boolean value) {
        super.set(value);
        return normalizeEnvironmentString();
    }
}

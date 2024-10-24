package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

public class ArithmeticOperators {

    public static RuntimeScalar add(RuntimeScalar runtimeScalar, int arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2);
        } else {
            return getScalarInt(arg1.getInt() + arg2);
        }
    }

    public static RuntimeScalar add(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() + arg2.getDouble());
        } else {
            return getScalarInt(arg1.getInt() + arg2.getInt());
        }
    }
}

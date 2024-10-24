package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

public class ArithmeticOperators {

    public static RuntimeScalar add(RuntimeScalar arg1, int arg2) {
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

    public static RuntimeScalar subtract(RuntimeScalar arg1, int arg2) {
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2);
        } else {
            return getScalarInt(arg1.getInt() - arg2);
        }
    }

    public static RuntimeScalar subtract(RuntimeScalar arg1, RuntimeScalar arg2) {
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() - arg2.getDouble());
        } else {
            return getScalarInt(arg1.getInt() - arg2.getInt());
        }
    }

    public static RuntimeScalar multiply(RuntimeScalar arg1, RuntimeScalar arg2) {
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return new RuntimeScalar(arg1.getDouble() * arg2.getDouble());
        } else {
            return getScalarInt((long) arg1.getInt() * (long) arg2.getInt());
        }
    }

    public static RuntimeScalar divide(RuntimeScalar arg1, RuntimeScalar arg2) {
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        double divisor = arg2.getDouble();
        if (divisor == 0.0) {
            throw new PerlCompilerException("Illegal division by zero");
        }
        return new RuntimeScalar(arg1.getDouble() / divisor);
    }

    public static RuntimeScalar modulus(RuntimeScalar arg1, RuntimeScalar arg2) {
        int divisor = arg2.getInt();
        int result = arg1.getInt() % divisor;
        if (result != 0.0 && ((divisor > 0.0 && result < 0.0) || (divisor < 0.0 && result > 0.0))) {
            result += divisor;
        }
        return new RuntimeScalar(result);
    }
}

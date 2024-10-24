package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarBoolean;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

public class CompareOperators {
    public static RuntimeScalar lessThan(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() < arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() < arg2.getInt());
        }
    }

    public static RuntimeScalar lessThanOrEqual(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() <= arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() <= arg2.getInt());
        }
    }

    public static RuntimeScalar greaterThan(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() > arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() > arg2.getInt());
        }
    }

    public static RuntimeScalar greaterThanOrEqual(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() >= arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() >= arg2.getInt());
        }
    }

    public static RuntimeScalar equalTo(RuntimeScalar runtimeScalar, int arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() == (double) arg2);
        } else {
            return getScalarBoolean(arg1.getInt() == arg2);
        }
    }

    public static RuntimeScalar equalTo(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() == arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() == arg2.getInt());
        }
    }

    public static RuntimeScalar notEqualTo(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarBoolean(arg1.getDouble() != arg2.getDouble());
        } else {
            return getScalarBoolean(arg1.getInt() != arg2.getInt());
        }
    }

    public static RuntimeScalar spaceship(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        RuntimeScalar arg1 = runtimeScalar;
        if (arg1.type == RuntimeScalarType.STRING) {
            arg1 = arg1.parseNumber();
        }
        if (arg2.type == RuntimeScalarType.STRING) {
            arg2 = arg2.parseNumber();
        }
        if (arg1.type == RuntimeScalarType.DOUBLE || arg2.type == RuntimeScalarType.DOUBLE) {
            return getScalarInt(Double.compare(arg1.getDouble(), arg2.getDouble()));
        } else {
            return getScalarInt(Integer.compare(arg1.getInt(), arg2.getInt()));
        }
    }

    public static RuntimeScalar cmp(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return getScalarInt(runtimeScalar.toString().compareTo(arg2.toString()));
    }

    public static RuntimeScalar eq(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return getScalarBoolean(runtimeScalar.toString().equals(arg2.toString()));
    }

    public static RuntimeScalar ne(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return getScalarBoolean(!runtimeScalar.toString().equals(arg2.toString()));
    }

    public static RuntimeScalar lt(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) < 0);
    }

    public static RuntimeScalar le(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) <= 0);
    }

    public static RuntimeScalar gt(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) > 0);
    }

    public static RuntimeScalar ge(RuntimeScalar runtimeScalar, RuntimeScalar arg2) {
        return getScalarBoolean(runtimeScalar.toString().compareTo(arg2.toString()) >= 0);
    }
}

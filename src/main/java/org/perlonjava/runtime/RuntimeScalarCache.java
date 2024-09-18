package org.perlonjava.runtime;

public class RuntimeScalarCache {

    static int minInt = -100;
    static int maxInt = 100;
    static RuntimeScalarReadOnly[] scalarInt = new RuntimeScalarReadOnly[maxInt - minInt + 1];
    static RuntimeScalarReadOnly scalarTrue;
    static RuntimeScalarReadOnly scalarFalse;
    static RuntimeScalarReadOnly scalarUndef;
    static RuntimeScalarReadOnly scalarEmptyString;

    static {
        for (int i = minInt; i <= maxInt; i++) {
            scalarInt[i - minInt] = new RuntimeScalarReadOnly(i);
        }
        scalarFalse = new RuntimeScalarReadOnly(false);
        scalarTrue = new RuntimeScalarReadOnly(true);
        scalarUndef = new RuntimeScalarReadOnly();
        scalarEmptyString = new RuntimeScalarReadOnly("");
    }

    static RuntimeScalar getScalarBoolean(boolean b) {
        return b ? scalarTrue : scalarFalse;
    }

    public static RuntimeScalar getScalarInt(int i) {
        if (i >= minInt && i <= maxInt) {
            return scalarInt[i - minInt];
        }
        return new RuntimeScalar(i);
    }

    static RuntimeScalar getScalarInt(long i) {
        if (i >= minInt && i <= maxInt) {
            return scalarInt[(int) i - minInt];
        }
        return new RuntimeScalar(i);
    }
}


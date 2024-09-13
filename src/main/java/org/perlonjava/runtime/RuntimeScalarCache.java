package org.perlonjava.runtime;

public class RuntimeScalarCache {

    static int minInt = -100;
    static int maxInt = 100;
    static RuntimeScalarReadOnlyInteger intCache[] = new RuntimeScalarReadOnlyInteger[maxInt - minInt + 1];

    static {
        for (int i = minInt; i <= maxInt; i++) {
            intCache[i - minInt] = new RuntimeScalarReadOnlyInteger(i);
        }
    }

    static RuntimeScalar getIntegerScalar(int i) {
        if (i >= minInt && i <= maxInt) {
            return intCache[i - minInt];
        }
        return new RuntimeScalar(i);
    }

    static RuntimeScalar getIntegerScalar(long i) {
        if (i >= minInt && i <= maxInt) {
            return intCache[(int) i - minInt];
        }
        return new RuntimeScalar(i);
    }
}


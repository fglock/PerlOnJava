package org.perlonjava.runtime.runtimetypes;

public class RuntimeScalarType {

    public static final int INTEGER = 0;
    public static final int DOUBLE = 1;
    public static final int STRING = 2;
    public static final int BYTE_STRING = 3;
    public static final int UNDEF = 4;
    public static final int VSTRING = 5;
    public static final int BOOLEAN = 6;
    public static final int GLOB = 7;
    public static final int JAVAOBJECT = 8;
    // --- Magic boundary ---
    // Types below TIED_SCALAR (0..8) require no special handling during set operations.
    // Code such as `this.type < TIED_SCALAR` relies on this ordering to fast-path
    // plain assignments. Do NOT insert new plain types at or above TIED_SCALAR
    // without updating those guards.
    public static final int TIED_SCALAR = 9;
    public static final int DUALVAR = 10;
    public static final int FORMAT = 11;
    public static final int READONLY_SCALAR = 12;
    public static final int PROXY = 13;  // Proxy with lazy evaluation (e.g. ScalarSpecialVariable)
    // Reference types (with high bit set)
    // Package-private so that refCount tracking in RuntimeScalar.setLarge() can
    // test (type & REFERENCE_BIT) without an extra method call.
    static final int REFERENCE_BIT = 0x8000;
    // References with bit pattern
    public static final int REGEX = 100 | REFERENCE_BIT;
    public static final int CODE = 101 | REFERENCE_BIT;
    public static final int REFERENCE = 102 | REFERENCE_BIT;
    public static final int ARRAYREFERENCE = 103 | REFERENCE_BIT;
    public static final int HASHREFERENCE = 104 | REFERENCE_BIT;
    public static final int GLOBREFERENCE = 105 | REFERENCE_BIT;

    private RuntimeScalarType() {
    } // Prevent instantiation

    // Get blessing ID as an integer
    public static int blessedId(RuntimeScalar runtimeScalar) {
        if (runtimeScalar.type == READONLY_SCALAR) return blessedId((RuntimeScalar) runtimeScalar.value);
        if ((runtimeScalar.type & REFERENCE_BIT) != 0) {
            if (runtimeScalar.value == null) return 0;
            return ((RuntimeBase) runtimeScalar.value).blessId;
        }
        return 0;
    }

    public static boolean isReference(RuntimeScalar runtimeScalar) {
        if (runtimeScalar.type == READONLY_SCALAR) return isReference((RuntimeScalar) runtimeScalar.value);
        return (runtimeScalar.type & REFERENCE_BIT) != 0;
    }
}


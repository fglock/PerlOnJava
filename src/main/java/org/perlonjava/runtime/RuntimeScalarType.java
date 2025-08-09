package org.perlonjava.runtime;

public class RuntimeScalarType {

    // Reference types (with high bit set)
    private static final int REFERENCE_BIT = 0x8000;

    public static final int INTEGER = 0;
    public static final int DOUBLE = 1;
    public static final int STRING = 2;
    public static final int UNDEF = 3;
    public static final int VSTRING = 4;
    public static final int BOOLEAN = 5;
    public static final int GLOB = 6;
    public static final int REGEX = 7;
    public static final int JAVAOBJECT = 8;
    public static final int TIED_SCALAR = 9;
    public static final int DUALVAR = 10;
    // References with bit pattern
    public static final int CODE = 100 | REFERENCE_BIT;
    public static final int REFERENCE = 101 | REFERENCE_BIT;
    public static final int ARRAYREFERENCE = 102 | REFERENCE_BIT;
    public static final int HASHREFERENCE = 103 | REFERENCE_BIT;
    public static final int GLOBREFERENCE = 104 | REFERENCE_BIT;

    private RuntimeScalarType() {
    } // Prevent instantiation

    // Get blessing ID as an integer
    public static int blessedId(RuntimeScalar runtimeScalar) {
        return (runtimeScalar.type & REFERENCE_BIT) != 0 ? ((RuntimeBase) runtimeScalar.value).blessId : 0;
    }

    public static boolean isReference(RuntimeScalar runtimeScalar) {
        return (runtimeScalar.type & REFERENCE_BIT) != 0;
    }
}


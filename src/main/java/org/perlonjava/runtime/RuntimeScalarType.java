package org.perlonjava.runtime;

public class RuntimeScalarType {

    // Reference types (with high bit set)
    public static final int REFERENCE_BIT = 0x8000;

    public static final int INTEGER = 0;
    public static final int DOUBLE = 1;
    public static final int STRING = 2;
    public static final int UNDEF = 3;
    public static final int VSTRING = 4;
    public static final int BOOLEAN = 5;
    public static final int GLOB = 6;
    public static final int REGEX = 7;
    public static final int JAVAOBJECT = 8;
    // References with bit pattern
    public static final int CODE = 9 | REFERENCE_BIT;
    public static final int REFERENCE = 10 | REFERENCE_BIT;
    public static final int ARRAYREFERENCE = 11 | REFERENCE_BIT;
    public static final int HASHREFERENCE = 12 | REFERENCE_BIT;
    public static final int GLOBREFERENCE = 13 | REFERENCE_BIT;

    private RuntimeScalarType() {
    } // Prevent instantiation
}


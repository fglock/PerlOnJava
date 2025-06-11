package org.perlonjava.runtime;

public class RuntimeScalarType {

    // Reference types (with high bit set)
    public static final int REFERENCE_BIT = 0x8000;

    public static final int INTEGER = 0;
    public static final int DOUBLE = 1;
    public static final int STRING = 2;
    public static final int CODE = 3 | REFERENCE_BIT;
    public static final int UNDEF = 4;
    public static final int REFERENCE = 5 | REFERENCE_BIT;
    public static final int GLOB = 6;
    public static final int REGEX = 7;
    public static final int ARRAYREFERENCE = 8 | REFERENCE_BIT;
    public static final int HASHREFERENCE = 9 | REFERENCE_BIT;
    public static final int GLOBREFERENCE = 10 | REFERENCE_BIT;
    public static final int VSTRING = 11;
    public static final int BOOLEAN = 12;
    public static final int JAVAOBJECT = 13;

    private RuntimeScalarType() {
    } // Prevent instantiation
}


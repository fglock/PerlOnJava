package org.perlonjava.runtime;

// Enum to represent the type of value stored in the scalar
public enum RuntimeScalarType {
    INTEGER,
    DOUBLE,
    STRING,
    CODE,
    UNDEF,
    REFERENCE,
    GLOB,
    ARRAYREFERENCE,
    HASHREFERENCE,
    GLOBREFERENCE
    // also BLESSED and special literals like regular expressions
}

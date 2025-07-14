# PerlOnJava Variable and Value System Documentation

## Overview

PerlOnJava implements Perl's dynamic type system in Java, supporting Perl's three main variable types (scalars, arrays, and hashes) along with references, type coercion, autovivification, and object blessing. The system is designed to faithfully reproduce Perl's behavior while leveraging Java's performance characteristics.

## Core Architecture

### Type Hierarchy

```
RuntimeBase (abstract)
├── RuntimeScalar
│   ├── RuntimeGlob
│   │   ├── [contains] RuntimeScalar(RuntimeIO)
│   │   └── RuntimeStashEntry
│   └── RuntimeBaseProxy (abstract - for lvalues and special variables)
│       ├── RuntimeScalarReadOnly (cacheable immutable values)
│       ├── RuntimeArrayProxyEntry
│       ├── RuntimeHashProxyEntry
│       ├── RuntimeArraySizeLvalue
│       ├── RuntimePosLvalue
│       ├── RuntimeSubstrLvalue
│       ├── RuntimeVecLvalue
│       └── ScalarSpecialVariable (variables such as $`, $&, $', or $1)
├── PerlRange
├── RuntimeCode
├── RuntimeList
├── RuntimeArray
│   ├── [contains] ArraySpecialVariable
│   └── [contains] AutovivificationArray
└── RuntimeHash
    ├── [contains] HashSpecialVariable
    ├── [contains] AutovivificationHash
    └── RuntimeStash
```


### Scalar Type System

PerlOnJava uses integer constants to represent scalar types, defined in `RuntimeScalarType`:

```java
// Basic types (0-8)
INTEGER = 0
DOUBLE = 1
STRING = 2
UNDEF = 3
VSTRING = 4
BOOLEAN = 5
GLOB = 6
REGEX = 7
JAVAOBJECT = 8

// Reference types (with high bit set: 0x8000)
CODE = 9 | REFERENCE_BIT
REFERENCE = 10 | REFERENCE_BIT
ARRAYREFERENCE = 11 | REFERENCE_BIT
HASHREFERENCE = 12 | REFERENCE_BIT
GLOBREFERENCE = 13 | REFERENCE_BIT
```

The reference bit pattern allows quick identification of reference types using bitwise operations.

## Variable Implementation

### RuntimeScalar

The `RuntimeScalar` class represents Perl scalar variables with:
- **Dynamic typing**: Type can change at runtime
- **Type field**: Integer indicating current type
- **Value field**: Object holding the actual value
- **Automatic coercion**: Values convert between types as needed

Key features:
```java
public class RuntimeScalar {
    public int type;      // Current type from RuntimeScalarType
    public Object value;  // Actual value (Integer, Double, String, etc.)
    public int blessId;   // For blessed objects (inherited from RuntimeBase)
}
```

### RuntimeArray

Implements Perl arrays as dynamic lists:
```java
public class RuntimeArray {
    public List<RuntimeScalar> elements;
}
```

Features:
- Negative indexing support
- Autovivification for out-of-bounds access
- Array slicing
- Push/pop/shift/unshift operations

### RuntimeHash

Implements Perl hashes as Java HashMaps:
```java
public class RuntimeHash {
    public Map<String, RuntimeScalar> elements;
}
```

Features:
- Key-value pair storage
- Exists/delete operations
- Hash slicing
- Iterator support for `each()`

## Memory Management and Caching

### RuntimeScalarCache

Implements caching for frequently-used immutable values:
- **Integer cache**: -100 to 100 (configurable)
- **Boolean cache**: true/false values
- **String cache**: Dynamic caching up to 100 characters
- **Common values**: undef, empty string, 0, 1

Benefits:
- Reduced memory allocation
- Faster comparisons (same object reference)
- Improved performance for common operations

### Read-Only Scalars

`RuntimeScalarReadOnly` provides immutable scalar values used in caching:
- Pre-computed type conversions
- Throws exception on modification attempts
- Optimal memory usage

## Autovivification

Autovivification allows automatic creation of data structures when dereferencing undefined values.

### Implementation Strategy

1. **Lazy Creation**: Variables aren't created until actually needed
2. **Proxy Pattern**: Special proxy objects delay creation
3. **Write Triggers**: First write operation triggers actual creation

### AutovivificationArray

```java
class AutovivificationArray extends ArrayList<RuntimeScalar> {
    private RuntimeScalar scalar;  // Parent scalar to vivify
    
    void vivify(RuntimeArray newArray) {
        scalar.type = ARRAYREFERENCE;
        scalar.value = newArray;
        newArray.elements = new ArrayList<>(this);
    }
}
```

### AutovivificationHash

Similar pattern for hash autovivification, converting undefined scalars to hash references on first write.

## Lvalue Operations

Lvalues represent assignable locations in Perl. PerlOnJava implements several lvalue types:

### RuntimeBaseProxy

Abstract base class for all lvalue proxies:
- Delays vivification until assignment
- Maintains reference to parent container
- Implements pass-through operations

### Specific Lvalue Types

1. **RuntimeArrayProxyEntry**: `$array[10]` where index is out of bounds
2. **RuntimeHashProxyEntry**: `$hash{key}` for non-existent keys
3. **RuntimeArraySizeLvalue**: `$#array` (last index of array)
4. **RuntimePosLvalue**: `pos()` function lvalue
5. **RuntimeVecLvalue**: `vec()` function lvalue
6. **RuntimeSubstrLvalue**: `substr()` function lvalue

## Reference and Dereferencing

### Reference Creation

```java
// Array reference: \@array
RuntimeScalar ref = array.createReference();  // type = ARRAYREFERENCE

// Hash reference: \%hash
RuntimeScalar ref = hash.createReference();    // type = HASHREFERENCE

// Scalar reference: \$scalar
RuntimeScalar ref = scalar.createReference();  // type = REFERENCE
```

### Dereferencing Operations

```java
// Array dereference: @$ref
RuntimeArray array = scalar.arrayDeref();

// Hash dereference: %$ref
RuntimeHash hash = scalar.hashDeref();

// Scalar dereference: $$ref
RuntimeScalar value = scalar.scalarDeref();

// Element access: $ref->[0], $ref->{key}
RuntimeScalar elem = scalar.arrayDerefGet(index);
RuntimeScalar val = scalar.hashDerefGet(key);
```

## Object System and Blessing

### Blessing Mechanism

Objects in Perl are blessed references. PerlOnJava implements this using:
- `blessId`: Integer identifier for the package name
- `NameNormalizer`: Manages package name to ID mappings

### Overloading

The `Overload` and `OverloadContext` classes implement Perl's operator overloading:

```java
class OverloadContext {
    static OverloadContext prepare(int blessId) {
        // Check if package has overloaded operators
        // Cache overload methods for performance
    }
    
    RuntimeScalar tryOverload(String op, RuntimeArray args) {
        // Attempt to call overloaded operator
    }
}
```

Supported overload categories:
- Arithmetic: `+`, `-`, `*`, `/`, etc.
- Comparison: `<=>`, `cmp`, `==`, etc.
- String/numeric conversion: `""`, `0+`, `bool`
- Dereferencing: `@{}`, `%{}`, `${}`, `&{}`

## Global Variables

### GlobalVariable Class

Manages Perl's global variables and symbol table:
- Package-qualified names (e.g., `$main::var`)
- Special variables (e.g., `$_`, `@_`, `%ENV`)
- Typeglobs and aliases

### NameNormalizer

Handles variable name normalization:
- Resolves unqualified names to package-qualified
- Manages special variable shortcuts
- Handles blessing package names

## Context Handling

Perl's context system (void, scalar, list) is represented by `RuntimeContextType`:

```java
public class RuntimeContextType {
    public static final int VOID = 0;
    public static final int SCALAR = 1;
    public static final int LIST = 2;
}
```

Context affects:
- Return values from subroutines
- Operator behavior
- Array/hash evaluation

## Type Coercion

Automatic type conversion follows Perl's rules:

### String to Number
- Uses `NumberParser` for Perl-compatible parsing
- Handles special cases: "Inf", "-Inf", "NaN"
- Strips leading/trailing whitespace
- Stops at first non-numeric character

### Number to String
- Integers convert directly
- Floats use Perl-compatible formatting
- Scientific notation for very large/small numbers

### Boolean Context
- Numbers: 0 is false, others true
- Strings: "" and "0" are false, others true
- References: always true
- Undef: always false

## Performance Optimizations

1. **Type-based dispatch**: Switch statements on type field compile to efficient tableswitches
2. **Caching**: Frequently-used values are cached and reused
3. **Lazy autovivification**: Structures created only when needed
4. **Direct type access**: Type field allows quick type checking without instanceof

## Thread Safety

- `RuntimeScalarCache` uses `ConcurrentHashMap` for string caching
- Most operations are not thread-safe (matching Perl's model)
- Global variables use synchronization where necessary

## Example Usage Patterns

### Creating Variables
```java
// Scalar
RuntimeScalar scalar = new RuntimeScalar("hello");

// Array
RuntimeArray array = new RuntimeArray();
array.push(new RuntimeScalar(42));

// Hash
RuntimeHash hash = new RuntimeHash();
hash.put("key", new RuntimeScalar("value"));
```

### References and Dereferencing
```java
// Create array reference
RuntimeArray arr = new RuntimeArray();
RuntimeScalar arrayRef = arr.createReference();

// Dereference and access
RuntimeArray deref = arrayRef.arrayDeref();
RuntimeScalar elem = arrayRef.arrayDerefGet(new RuntimeScalar(0));
```

### Autovivification
```java
RuntimeScalar undef = new RuntimeScalar();  // undefined
RuntimeScalar elem = undef.hashDerefGet(new RuntimeScalar("key"));
elem.set("value");  // This autovivifies undef into a hash reference
```

This architecture provides a complete implementation of Perl's variable system in Java, maintaining Perl's flexibility while leveraging Java's performance and type system.

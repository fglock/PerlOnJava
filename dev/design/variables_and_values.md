# PerlOnJava Variable and Value System Documentation

## Overview

PerlOnJava implements Perl's dynamic type system in Java, supporting Perl's three main variable types (scalars, arrays, and hashes) along with references, type coercion, autovivification, tied variables, and object blessing. The system is designed to faithfully reproduce Perl's behavior while leveraging Java's performance characteristics.

## Core Architecture

### Type Hierarchy

```
RuntimeBase (abstract)
├── RuntimeScalar
│   ├── RuntimeGlob
│   │   ├── [contains] RuntimeScalar(RuntimeIO)
│   │   ├── [contains] RuntimeScalar(TieHandle)
│   │   └── RuntimeStashEntry
│   └── RuntimeBaseProxy (abstract - for lvalues and special variables)
│       ├── RuntimeScalarReadOnly (cacheable immutable values)
│       ├── RuntimeArrayProxyEntry (lazy array element creation)
│       ├── RuntimeHashProxyEntry (lazy hash element creation)
│       ├── RuntimeArraySizeLvalue ($#array)
│       ├── RuntimePosLvalue (pos() lvalue)
│       ├── RuntimeSubstrLvalue (substr() lvalue)
│       ├── RuntimeVecLvalue (vec() lvalue)
│       ├── ScalarSpecialVariable ($_, $/, $\, $1, etc.)
│       └── TiedVariableBase (abstract - for tied scalar variables)
│           ├── RuntimeTiedScalar (tied scalar implementation)
│           ├── RuntimeTiedArrayProxyEntry (tied array element proxy)
│           └── RuntimeTiedHashProxyEntry (tied hash element proxy)
├── PerlRange (numeric and string ranges)
├── RuntimeCode (code references and subroutines)
├── RuntimeList (list context values)
├── RuntimeArray
│   ├── [uses] AutovivificationArray (helper for lazy array creation)
│   ├── [uses] TieArray (container for tied array handlers)
│   └── [contains] ArraySpecialVariable (special arrays like @_)
└── RuntimeHash
    ├── [uses] AutovivificationHash (helper for lazy hash creation)
    ├── [uses] TieHash (container for tied hash handlers)
    ├── [uses] HashSpecialVariable (special hashes like %ENV)
    └── [extends] RuntimeStash (symbol table implementation)

Helper Classes:
├── RuntimeScalarCache (caching for common scalar values)
├── NameNormalizer (variable name and package name management)
├── GlobalVariable (global variable registry)
├── Overload (operator overloading implementation)
├── OverloadContext (overload method resolution cache)
├── NumberParser (Perl-compatible number parsing)
├── ScalarUtils (scalar formatting and manipulation)
├── InheritanceResolver (method and overload inheritance)
├── TiedVariableBase (abstract base for tied scalars)
├── AutovivificationArray (lazy array vivification)
├── AutovivificationHash (lazy hash vivification)
├── TieArray (tied array method delegation)
├── TieHash (tied hash method delegation)
└── TieHandle (tied filehandle method delegation)

Special Iterators:
├── RuntimeArrayIterator (array iteration)
├── RuntimeHashIterator (hash key-value iteration)
├── RuntimeTiedHashIterator (tied hash FIRSTKEY/NEXTKEY iteration)
└── RuntimeScalarIterator (scalar as single-element iteration)
```

### Scalar Type System

PerlOnJava uses integer constants to represent scalar types, defined in `RuntimeScalarType`:

```java
// Basic types (0-9)
INTEGER = 0        // Perl integer
DOUBLE = 1         // Perl floating point
STRING = 2         // Perl string
UNDEF = 3          // undefined value
VSTRING = 4        // version string (v5.32.0)
BOOLEAN = 5        // boolean value
GLOB = 6           // typeglob (*name)
REGEX = 7          // compiled regular expression
JAVAOBJECT = 8     // Java object wrapper
TIED_SCALAR = 9    // tied scalar variable

// Reference types (with high bit set: 0x8000)
CODE = 100 | REFERENCE_BIT          // code reference (\&sub)
REFERENCE = 101 | REFERENCE_BIT     // scalar reference (\$scalar)
ARRAYREFERENCE = 102 | REFERENCE_BIT // array reference (\@array)
HASHREFERENCE = 103 | REFERENCE_BIT  // hash reference (\%hash)
GLOBREFERENCE = 104 | REFERENCE_BIT  // glob reference (\*glob)

// Helper constant
REFERENCE_BIT = 0x8000  // High bit indicates reference type
```

The reference bit pattern allows quick identification of reference types using bitwise operations.

## Variable Implementation

### RuntimeScalar

The `RuntimeScalar` class represents Perl scalar variables with:
- **Dynamic typing**: Type can change at runtime
- **Type field**: Integer indicating current type
- **Value field**: Object holding the actual value
- **Automatic coercion**: Values convert between types as needed
- **Tied variable support**: Type TIED_SCALAR delegates operations

Key features:
```java
public class RuntimeScalar extends RuntimeBase {
    public int type;      // Current type from RuntimeScalarType
    public Object value;  // Actual value (Integer, Double, String, etc.)
    public int blessId;   // For blessed objects (inherited from RuntimeBase)
    
    // Type map for quick Java class to Perl type conversion
    private static final Map<Class<?>, Integer> typeMap;
}
```

### RuntimeArray

Implements Perl arrays with internal type tracking:
```java
public class RuntimeArray extends RuntimeBase {
    public int type;                     // PLAIN_ARRAY, AUTOVIVIFY_ARRAY, or TIED_ARRAY
    public List<RuntimeScalar> elements; // Array elements
}
```

Features:
- Negative indexing support
- Lazy autovivification through proxy objects
- Array slicing with deleteSlice()
- Push/pop/shift/unshift operations
- Tied array support

### RuntimeHash

Implements Perl hashes with internal type tracking:
```java
public class RuntimeHash extends RuntimeBase {
    public int type;                              // PLAIN_HASH, AUTOVIVIFY_HASH, or TIED_HASH
    public Map<String, RuntimeScalar> elements;   // Hash elements
    Iterator<RuntimeScalar> hashIterator;         // For each() operation
}
```

Features:
- Key-value pair storage
- Exists/delete operations with tied support
- Hash slicing
- Iterator support for `each()` with key/value alternation
- Tied hash support with FIRSTKEY/NEXTKEY iteration

## Memory Management and Caching

### RuntimeScalarCache

Implements caching for frequently-used immutable values:
- **Integer cache**: -128 to 127 (configurable)
- **Boolean cache**: true/false values
- **String cache**: Dynamic caching up to 100 characters using ConcurrentHashMap
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
3. **Type Tracking**: Arrays and hashes track their autovivification state
4. **Write Triggers**: First write operation triggers actual creation

### AutovivificationArray

Arrays use type field to track state:
```java
// In RuntimeArray
public static final int AUTOVIVIFY_ARRAY = 1;

// Vivification happens on first write operation
AutovivificationArray.vivify(runtimeArray);
```

### AutovivificationHash

Hashes follow similar pattern:
```java
// In RuntimeHash
public static final int AUTOVIVIFY_HASH = 1;

// Vivification happens on first write operation
AutovivificationHash.vivify(runtimeHash);
```

## Lvalue Operations

Lvalues represent assignable locations in Perl. PerlOnJava implements several lvalue types:

### RuntimeBaseProxy

Abstract base class for all lvalue proxies:
```java
public abstract class RuntimeBaseProxy extends RuntimeScalar {
    RuntimeScalar lvalue;  // The actual storage location
    
    abstract void vivify();  // Create storage if needed
    
    // All operations delegate through vivify() then to lvalue
    public RuntimeScalar set(RuntimeScalar value) {
        vivify();
        this.lvalue.set(value);
        this.type = lvalue.type;
        this.value = lvalue.value;
        return lvalue;
    }
}
```

### Specific Lvalue Types

1. **RuntimeArrayProxyEntry**: `$array[10]` where index might be out of bounds
2. **RuntimeHashProxyEntry**: `$hash{key}` for potentially non-existent keys
3. **RuntimeArraySizeLvalue**: `$#array` (last index of array)
4. **RuntimePosLvalue**: `pos()` function lvalue
5. **RuntimeVecLvalue**: `vec()` function lvalue
6. **RuntimeSubstrLvalue**: `substr()` function lvalue
7. **RuntimeTiedArrayProxyEntry**: Element access in tied arrays
8. **RuntimeTiedHashProxyEntry**: Element access in tied hashes

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

// Scalar dereference: $ref
RuntimeScalar value = scalar.scalarDeref();

// Glob dereference: *$ref
RuntimeGlob glob = scalar.globDeref();

// Element access: $ref->[0], $ref->{key}
RuntimeScalar elem = scalar.arrayDerefGet(index);
RuntimeScalar val = scalar.hashDerefGet(key);

// Existence/deletion: exists/delete $ref->[0], $ref->{key}
RuntimeScalar exists = scalar.arrayDerefExists(index);
RuntimeScalar deleted = scalar.hashDerefDelete(key);
```

### Non-Strict References

When "no strict refs" is in effect:
```java
// Symbolic references resolved through package symbol table
RuntimeScalar scalar = scalar.scalarDerefNonStrict(packageName);
RuntimeGlob glob = scalar.globDerefNonStrict(packageName);
```

## Object System and Blessing

### Blessing Mechanism

Objects in Perl are blessed references. PerlOnJava implements this using:
- `blessId`: Integer identifier for the package name
- `NameNormalizer`: Manages package name to ID mappings
- Blessing tracked in RuntimeBase parent class

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
        // Handle fallback mechanism
    }
}
```

Supported overload categories:
- Arithmetic: `+`, `-`, `*`, `/`, `%`, `**`, etc.
- Comparison: `<=>`, `cmp`, `==`, `eq`, etc.
- String/numeric conversion: `""`, `0+`, `bool`
- Dereferencing: `@{}`, `%{}`, `${}`, `*{}`, `&{}`
- Special: `nomethod`, `fallback`

### Overloading Implementation Details

#### Context Preparation
- `OverloadContext.prepare(blessId)` checks and caches overload state
- Looks for "((" marker in class hierarchy
- Caches results in InheritanceResolver for performance

#### Operation Types
1. **Unary Operations**: stringify, numify, boolify
   - Try primary operator
   - Try fallback chain
   - Try nomethod

2. **Binary Operations**: arithmetic, comparison
   - Try left operand's overload
   - Try right operand's overload (swapped)
   - Try nomethod on both

#### Fallback Mechanism
- `()` method controls fallback behavior
- undefined or true: try alternative conversions
- false: skip fallbacks

#### Self-Reference Prevention
All dereference operations check for self-reference to prevent infinite recursion:
```java
if (result != null && result.value.hashCode() != this.value.hashCode()) {
    return result.arrayDeref();  // Safe to recurse
}
```

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
- Maintains blessId to package name mappings

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
- Method call signatures

## Type Coercion

Automatic type conversion follows Perl's rules:

### String to Number
- Uses `NumberParser` for Perl-compatible parsing
- Handles special cases: "Inf", "-Inf", "NaN"
- Strips leading/trailing whitespace
- Stops at first non-numeric character

### Number to String
- Integers convert directly
- Floats use `ScalarUtils.formatLikePerl()` for Perl-compatible formatting
- Scientific notation for very large/small numbers

### Boolean Context
- Numbers: 0 is false, others true
- Strings: "" and "0" are false, others true
- References: always true
- Undef: always false
- Objects: can override with `bool` overload

## Performance Optimizations

1. **Type-based dispatch**: Switch statements on type field compile to efficient tableswitches
2. **Caching**: Frequently-used values are cached and reused
3. **Lazy autovivification**: Structures created only when needed
4. **Direct type access**: Type field allows quick type checking without instanceof
5. **Cached overload lookups**: Overload state cached per blessed package

## Thread Safety

- `RuntimeScalarCache` uses `ConcurrentHashMap` for string caching
- Most operations are not thread-safe (matching Perl's model)
- Global variables use synchronization where necessary
- Dynamic state save/restore uses thread-local stacks

## Dynamic State (local)

All main types implement `DynamicState` interface for Perl's `local`:

```java
public interface DynamicState {
    void dynamicSaveState();    // Save current state
    void dynamicRestoreState(); // Restore previous state
}
```

Each type maintains its own static Stack for saved states:
- RuntimeScalar: saves type, value, blessId
- RuntimeArray: saves elements list and blessId
- RuntimeHash: saves elements map and blessId

## Tied Variables

Tied variables allow Perl code to intercept all operations on a variable by delegating to handler methods.

### Scalar Ties
- **Type Constant**: TIED_SCALAR (type 9) identifies tied scalars
- **TiedVariableBase**: Abstract proxy that intercepts scalar operations
- **Operation Interception**: All operations check type and delegate to FETCH/STORE

### Array Ties
- **Type Constant**: TIED_ARRAY (type 2) identifies tied arrays
- **TieArray**: Container class with handler methods
- **Operations**: FETCH, STORE, FETCHSIZE, STORESIZE, CLEAR, PUSH, POP, SHIFT, UNSHIFT, SPLICE, EXTEND, EXISTS, DELETE

### Hash Ties
- **Type Constant**: TIED_HASH (type 2) identifies tied hashes
- **TieHash**: Container class with handler methods
- **Operations**: FETCH, STORE, EXISTS, DELETE, CLEAR, FIRSTKEY, NEXTKEY, SCALAR
- **Iteration**: Special RuntimeTiedHashIterator using FIRSTKEY/NEXTKEY

### Implementation Pattern
```java
// All operations check tied status first
switch (type) {
    case PLAIN_ARRAY -> // normal operation
    case AUTOVIVIFY_ARRAY -> // vivify then operate
    case TIED_ARRAY -> // delegate to tie handler
}
```

## Example Usage Patterns

### Creating Variables
```java
// Scalar
RuntimeScalar scalar = new RuntimeScalar("hello");

// Array
RuntimeArray array = new RuntimeArray();
RuntimeArray.push(array, new RuntimeScalar(42));

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

### Tied Variables
```java
// Tied scalar operations automatically delegate
RuntimeScalar tied = new RuntimeScalar();
tied.type = TIED_SCALAR;
tied.value = new MyTiedScalar();
tied.set(42);  // Calls MyTiedScalar.STORE(42)
int val = tied.getInt();  // Calls MyTiedScalar.FETCH().getInt()
```
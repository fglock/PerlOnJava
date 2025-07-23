# PerlOnJava Tie Implementation Documentation

## Overview

PerlOnJava implements Perl's `tie` mechanism, which allows variables to have their operations intercepted and handled by custom Perl classes. The implementation supports tied scalars, arrays, hashes, and filehandles.

## Architecture

### Type Constants

The tie mechanism uses type constants to identify tied variables:

```java
// In RuntimeScalarType
public static final int TIED_SCALAR = 9;  // Tied scalar variable

// Internal array types in RuntimeArray
public static final int PLAIN_ARRAY = 0;
public static final int AUTOVIVIFY_ARRAY = 1;
public static final int TIED_ARRAY = 2;

// Internal hash types in RuntimeHash
public static final int PLAIN_HASH = 0;
public static final int AUTOVIVIFY_HASH = 1;
public static final int TIED_HASH = 2;
```

### Implementation Strategy

1. **Scalars**: Type field set to `TIED_SCALAR`, value contains `TiedVariableBase` instance
2. **Arrays/Hashes**: Internal type field tracks tied state, use helper classes for delegation
3. **Element Access**: Proxy objects (`RuntimeTiedArrayProxyEntry`, `RuntimeTiedHashProxyEntry`) provide lazy FETCH/STORE
4. **Filehandles**: `TieHandle` wraps the tied handler object

## Scalar Ties

### TiedVariableBase

Abstract base class for all tied scalar proxies:

```java
public abstract class TiedVariableBase extends RuntimeScalar {
    abstract void vivify();  // Populate lvalue with current value
    abstract RuntimeScalar tiedStore(RuntimeScalar value);
    abstract RuntimeScalar tiedFetch();
}
```

### Implementation Pattern

All scalar operations check for `TIED_SCALAR` type:

```java
public int getInt() {
    return switch (type) {
        case INTEGER -> (int) value;
        case DOUBLE -> (int) ((double) value);
        case STRING -> NumberParser.parseNumber(this).getInt();
        // ... other cases ...
        case TIED_SCALAR -> this.tiedFetch().getInt();
        default -> Overload.numify(this).getInt();
    };
}

public RuntimeScalar set(RuntimeScalar value) {
    if (this.type == TIED_SCALAR) {
        return this.tiedStore(value);
    }
    // Normal assignment
}
```

### Tied Scalar Proxies

- **RuntimeTiedScalar**: Direct tied scalar implementation
- **RuntimeTiedArrayProxyEntry**: Element access in tied arrays (`$tied_array[0]`)
- **RuntimeTiedHashProxyEntry**: Element access in tied hashes (`$tied_hash{key}`)

## Array Ties

### TieArray Helper Class

Static methods delegate to Perl tie handler methods:

```java
// Core operations
tiedFetch(array, index)      // FETCH
tiedStore(array, index, val)  // STORE
tiedFetchSize(array)          // FETCHSIZE
tiedStoreSize(array, size)    // STORESIZE
tiedExists(array, index)      // EXISTS
tiedDelete(array, index)      // DELETE
tiedClear(array)              // CLEAR

// Stack operations
tiedPush(array, values)       // PUSH
tiedPop(array)                // POP
tiedShift(array)              // SHIFT
tiedUnshift(array, values)    // UNSHIFT

// Optional operations
tiedExtend(array, size)       // EXTEND (if exists)
tiedDestroy(array)            // DESTROY (if exists)
tiedUntie(array)              // UNTIE (if exists)
```

### Array Type Dispatch

All array operations check the internal type field:

```java
public static RuntimeScalar pop(RuntimeArray runtimeArray) {
    return switch (runtimeArray.type) {
        case PLAIN_ARRAY -> // normal pop operation
        case AUTOVIVIFY_ARRAY -> // vivify then pop
        case TIED_ARRAY -> TieArray.tiedPop(runtimeArray);
    };
}
```

### Element Access

Array element access returns a proxy for tied arrays:

```java
public RuntimeScalar get(RuntimeScalar value) {
    if (this.type == TIED_ARRAY) {
        RuntimeScalar v = new RuntimeScalar();
        v.type = TIED_SCALAR;
        v.value = new RuntimeTiedArrayProxyEntry(this, value);
        return v;
    }
    // Normal element access
}
```

## Hash Ties

### TieHash Helper Class

Static methods delegate to Perl tie handler methods:

```java
// Core operations
tiedFetch(hash, key)          // FETCH
tiedStore(hash, key, value)   // STORE
tiedExists(hash, key)         // EXISTS
tiedDelete(hash, key)         // DELETE
tiedClear(hash)               // CLEAR

// Iteration
tiedFirstKey(hash)            // FIRSTKEY
tiedNextKey(hash, lastKey)    // NEXTKEY

// Scalar context
tiedScalar(hash)              // SCALAR

// Optional operations
tiedDestroy(hash)             // DESTROY (if exists)
tiedUntie(hash)               // UNTIE (if exists)
```

### Hash Type Dispatch

All hash operations check the internal type field:

```java
public void put(String key, RuntimeScalar value) {
    switch (type) {
        case PLAIN_HASH -> elements.put(key, value);
        case AUTOVIVIFY_HASH -> // vivify then put
        case TIED_HASH -> TieHash.tiedStore(this, new RuntimeScalar(key), value);
    };
}
```

### Tied Hash Iteration

Special iterator using FIRSTKEY/NEXTKEY protocol:

```java
private class RuntimeTiedHashIterator implements Iterator<RuntimeScalar> {
    // Uses FIRSTKEY to start iteration
    // Uses NEXTKEY to continue
    // Alternates between returning keys and values
}
```

## Filehandle Ties

### TieHandle Class

Implements tied filehandles with method delegation:

```java
public class TieHandle extends RuntimeIO {
    private final RuntimeScalar self;  // The tied handler object
    private final String tiedPackage;  // Package name for method lookup
    
    // Delegated operations
    tiedPrint(handle, args)       // PRINT
    tiedPrintf(handle, args)      // PRINTF
    tiedReadline(handle, ctx)     // READLINE
    tiedGetc(handle)              // GETC
    tiedRead(handle, args)        // READ
    tiedSeek(handle, args)        // SEEK
    tiedTell(handle)              // TELL
    tiedEof(handle, args)         // EOF
    tiedClose(handle)             // CLOSE
    tiedBinmode(handle, args)     // BINMODE
    tiedFileno(handle)            // FILENO
    tiedWrite(handle, ...)        // WRITE
    tiedDestroy(handle)           // DESTROY (if exists)
    tiedUntie(handle)             // UNTIE (if exists)
}
```

## Method Resolution

### Required vs Optional Methods

Some tie methods are optional and only called if they exist:

```java
private RuntimeScalar tieCallIfExists(String methodName) {
    // Check if method exists in class hierarchy
    RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(
        methodName, className, null, 0);
    if (method == null) {
        return RuntimeScalarCache.scalarUndef;
    }
    // Method exists, call it
    return RuntimeCode.apply(method, new RuntimeArray(self), SCALAR);
}
```

### Method Lookup

The system uses `InheritanceResolver.findMethodInHierarchy()` to locate tie handler methods in the Perl class hierarchy.

## Performance Optimizations

### Type-Based Dispatch

- Switch statements compile to efficient tableswitches
- Common types (INTEGER, DOUBLE, STRING) checked first
- TIED_SCALAR case placed after common types

### Lazy Element Access

- Array/hash element access returns proxy objects
- FETCH only called when value is actually read
- STORE only called when value is actually written

### Cached Method Resolution

- `OverloadContext` caches overload state per blessed package
- Method lookups cached in `InheritanceResolver`

## Implementation Examples

### Tied Scalar Usage

```java
// When Perl code does: tie $scalar, 'MyTieClass', @args
RuntimeScalar scalar = new RuntimeScalar();
scalar.type = TIED_SCALAR;
scalar.value = new RuntimeTiedScalar(handler, "MyTieClass");

// Operations are intercepted
int value = scalar.getInt();        // Calls FETCH then getInt()
scalar.set("hello");                // Calls STORE
```

### Tied Array Usage

```java
// When Perl code does: tie @array, 'MyTieClass', @args
RuntimeArray array = new RuntimeArray();
array.type = TIED_ARRAY;
// Handler stored separately by TieOperators

// Operations are delegated
RuntimeScalar elem = array.get(0);  // Returns proxy
elem.set("value");                  // Proxy calls STORE
```

### Tied Hash Usage

```java
// When Perl code does: tie %hash, 'MyTieClass', @args
RuntimeHash hash = new RuntimeHash();
hash.type = TIED_HASH;
// Handler stored separately by TieOperators

// Iteration uses FIRSTKEY/NEXTKEY
for (RuntimeScalar item : hash) {
    // Alternates between keys and values
}
```

## Thread Safety

- Tie operations are not thread-safe (matching Perl's model)
- Each thread maintains separate tied variable state
- Dynamic state save/restore uses thread-local stacks

## Error Handling

- Invalid tie operations throw `PerlCompilerException`
- Tie handler exceptions propagate to caller
- Missing required methods cause runtime errors
- Optional methods (UNTIE, DESTROY) fail silently if missing

## Testing Considerations

### Functional Tests

1. All scalar operations with tied scalars
2. Array operations with tied arrays
3. Hash operations and iteration with tied hashes
4. Filehandle operations with tied handles
5. Nested ties (tied element of tied container)
6. Optional method presence/absence

### Performance Tests

1. Untied variable operations (no regression)
2. Tied variable operation overhead
3. Method resolution caching effectiveness
4. Memory usage with many tied variables
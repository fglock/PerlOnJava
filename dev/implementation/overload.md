# Perl Overload Implementation in PerlOnJava

This documentation describes the actual implementation of Perl's overload system in the PerlOnJava project.

## Architecture Overview

### Core Components

1. **OverloadContext** (`org.perlonjava.runtime.runtimetypes.OverloadContext`)
   - Helper class that manages overloading context for blessed objects
   - Handles method resolution and fallback mechanisms
   - Provides caching for performance optimization

2. **Overload** (`org.perlonjava.runtime.runtimetypes.Overload`)
   - Central class implementing operator overloading logic
   - Provides methods for stringification, numification, and boolification
   - Handles fallback chains and default conversions

3. **RuntimeScalar** Integration
   - Overload checks integrated into dereference operations
   - Automatic overload detection for blessed objects
   - Seamless integration with Perl semantics

## Implementation Details

### Overload Detection

The system detects overloadable objects through:
```java
// Check if object is blessed
int blessId = runtimeScalar.blessedId();
if (blessId < 0) {
    OverloadContext ctx = OverloadContext.prepare(blessId);
    // ctx will be non-null if overloading is enabled
}
```

### Method Resolution

1. **Primary Lookup**: Direct method search in the class
2. **Inheritance Chain**: Traversal through @ISA hierarchy
3. **Fallback Mechanism**: Alternative method attempts
4. **NoMethod Handler**: Last resort using `(nomethod`

### Cached Context

```java
public static OverloadContext prepare(int blessId) {
    // Check cache first
    OverloadContext cachedContext = InheritanceResolver.getCachedOverloadContext(blessId);
    if (cachedContext != null) {
        return cachedContext;
    }
    // ... resolution logic ...
}
```

## Supported Operators

### Dereference Operators

1. **Array Dereference** `@{}`
   - Operator key: `(@{}`
   - Implemented in: `RuntimeScalar.arrayDeref()`
   - Fallback: Throws exception if not overloaded

2. **Hash Dereference** `%{}`
   - Operator key: `(%{}`
   - Implemented in: `RuntimeScalar.hashDeref()`
   - Supports autovivification for undefined scalars

3. **Scalar Dereference** `${}`
   - Operator key: `(${}`
   - Implemented in: `RuntimeScalar.scalarDeref()`
   - Non-strict mode supports symbolic references

4. **Glob Dereference** `*{}`
   - Operator key: `(*{}`
   - Implemented in: `RuntimeScalar.globDeref()`
   - Non-strict mode supports symbolic references

### Conversion Operators

1. **Stringification** `""`
   - Operator key: `(""`
   - Fallback chain: `(0+` → `(bool`
   - Default: Reference stringification

2. **Numification** `0+`
   - Operator key: `(0+`
   - Fallback chain: `(""` → `(bool`
   - Default: Reference as number (hashcode)

3. **Boolification** `bool`
   - Operator key: `(bool`
   - Fallback chain: `(0+` → `(""`
   - Default: Reference truthiness

### Binary Operators

Two-argument overload support with swap handling:
```java
public static RuntimeScalar tryTwoArgumentOverload(
    RuntimeScalar arg1, RuntimeScalar arg2, 
    int blessId, int blessId2, 
    String overloadName, String methodName
)
```

## Fallback System

### Fallback Resolution

The fallback mechanism is implemented through:
```java
public RuntimeScalar tryOverloadFallback(
    RuntimeScalar runtimeScalar, 
    String... fallbackMethods
)
```

1. Check if `()` method exists
2. Execute fallback method to get configuration
3. If undefined or true, try alternative methods in sequence
4. Return first successful result

### NoMethod Handler

Special `(nomethod` handler for missing operators:
- Receives: (object, other, swap_flag, method_name)
- Last resort in method resolution
- Allows generic operator handling

## Performance Optimizations

### 1. Method Caching
- Overload contexts cached per blessId
- Avoids repeated inheritance chain traversal
- Cache stored in `InheritanceResolver`

### 2. Type Checking
- Early bailout for non-blessed objects
- Direct type checks using blessId
- Minimal overhead for non-overloaded objects

### 3. Efficient Lookups
- Symbol table lookups minimized
- Direct method references where possible
- Fast path for common operations

## Integration Points

### RuntimeScalar Methods

Each dereference method follows this pattern:
1. Check for blessed object (blessId != 0)
2. Prepare overload context
3. Try overloaded method
4. Handle self-reference prevention
5. Fall back to default behavior

### Example Implementation
```java
public RuntimeArray arrayDeref() {
    int blessId = this.blessedId();
    if (blessId != 0) {
        OverloadContext ctx = OverloadContext.prepare(blessId);
        if (ctx != null) {
            RuntimeScalar result = ctx.tryOverload("(@{}", new RuntimeArray(this));
            // Prevent infinite recursion
            if (result != null && result.value.hashCode() != this.value.hashCode()) {
                return result.arrayDeref();
            }
        }
    }
    // Default behavior
}
```

## Error Handling

### Type Errors
- Clear error messages for invalid operations
- Distinction between strict and non-strict modes
- Context-appropriate exceptions

### Recursion Prevention
- Self-reference detection using hashCode comparison
- Prevents infinite loops in overloaded methods
- Allows methods to return self for default behavior

## Usage Examples

### Basic Overloading
```perl
package MyClass;
use overload
    '""' => sub { "stringified" },
    '0+' => sub { 42 },
    '@{}' => sub { [1, 2, 3] };
```

### Fallback Configuration
```perl
use overload
    fallback => 1,  # Allow autogeneration
    '""' => 'stringify_method';
```

### NoMethod Handler
```perl
use overload
    nomethod => sub {
        my ($self, $other, $swap, $op) = @_;
        # Generic operator handling
    };
```

## Implementation Status

### Completed Features
- Basic operator overloading framework
- Dereference operators (@{}, %{}, ${}, *{})
- Conversion operators ("", 0+, bool)
- Fallback mechanism
- NoMethod support
- Performance caching
- Two-argument operator support

### Known Limitations
- Limited operator coverage compared to full Perl
- Some edge cases in complex inheritance
- Performance optimization ongoing

## Testing Considerations

### Test Coverage Areas
1. Basic overloading functionality
2. Inheritance and method resolution
3. Fallback behavior
4. Recursion prevention
5. Error conditions
6. Performance benchmarks
7. Edge cases and corner conditions
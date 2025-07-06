# PerlOnJava Tie Implementation Specification

## Overview

This document specifies the implementation of Perl's `tie` mechanism in PerlOnJava, which allows variables to have their operations intercepted and handled by custom classes.

## Architecture

### Scalar Type System

The `PlScalar` class uses a type-based dispatch system where tied scalars are handled as a distinct type:

```java
public static final int TIED = 7;  // New type constant
```

When a scalar is tied, its `type` field is set to `TIED` and its `value` field contains the tie handler object.

### Tie Handler Interface

All tie handlers must implement the `TieHandler` interface:

```java
public interface TieHandler {
    PlObject FETCH();
    void STORE(PlObject value);
    void UNTIE();
    boolean EXISTS();
    void DELETE();
    // Additional methods as needed
}
```

## Implementation Pattern

### Operation Delegation

All scalar operations must include a `TIED` case in their type switch statements. The pattern is:

1. **Read operations**: Call `FETCH()` on the tie handler, then delegate to the returned value
2. **Write operations**: Call `STORE()` on the tie handler with the new value
3. **Test operations**: May use `EXISTS()` or `FETCH()` depending on semantics

### Example Implementation

```java
public boolean getBoolean() {
    return switch (type) {
        case INTEGER -> (int) value != 0;
        case DOUBLE -> (double) value != 0.0;
        case STRING -> {
            String s = (String) value;
            yield !s.isEmpty() && !s.equals("0");
        }
        case UNDEF -> false;
        case VSTRING -> true;
        case BOOLEAN -> (boolean) value;
        case TIED -> ((TieHandler) value).FETCH().getBoolean();
        case REFERENCE, ARRAYREFERENCE, HASHREFERENCE -> Overload.boolify(this).getBoolean();
        default -> ((RuntimeScalarReference) value).getBooleanRef();
    };
}

public void set(PlObject newValue) {
    switch (type) {
        case TIED -> ((TieHandler) value).STORE(newValue);
        default -> {
            // Regular assignment logic
            this.value = newValue.value;
            this.type = newValue.type;
        }
    }
}
```

## Required Method Updates

All the following `PlScalar` methods must be updated to handle the `TIED` case:

### Read Operations
- `getString()` → `((TieHandler) value).FETCH().getString()`
- `getInt()` → `((TieHandler) value).FETCH().getInt()`
- `getDouble()` → `((TieHandler) value).FETCH().getDouble()`
- `getBoolean()` → `((TieHandler) value).FETCH().getBoolean()`
- `getBlessId()` → `((TieHandler) value).FETCH().getBlessId()`
- `getDefinedBoolean()` → `((TieHandler) value).FETCH().getDefinedBoolean()`

### Write Operations
- `set(PlObject)` → `((TieHandler) value).STORE(newValue)`
- `setInt(int)` → `((TieHandler) value).STORE(new PlInt(newValue))`
- `setDouble(double)` → `((TieHandler) value).STORE(new PlDouble(newValue))`
- `setString(String)` → `((TieHandler) value).STORE(new PlString(newValue))`
- `setBoolean(boolean)` → `((TieHandler) value).STORE(new PlBoolean(newValue))`

### Special Operations
- `length()` → `((TieHandler) value).FETCH().length()`
- `chomp()` → Fetch, modify, then store back
- `chop()` → Fetch, modify, then store back
- Auto-increment/decrement operations → Fetch, modify, then store back

## Tie/Untie Implementation

### tie() Method

```java
public void tie(TieHandler handler) {
    this.type = TIED;
    this.value = handler;
    this.blessId = 0;  // Reset blessing
}
```

### untie() Method

```java
public void untie() {
    if (this.type == TIED) {
        TieHandler handler = (TieHandler) this.value;
        handler.UNTIE();
        
        // Convert back to regular scalar with current value
        PlObject currentValue = handler.FETCH();
        this.type = currentValue.type;
        this.value = currentValue.value;
        this.blessId = currentValue.blessId;
    }
}
```

### tied() Method

```java
public TieHandler tied() {
    return (this.type == TIED) ? (TieHandler) this.value : null;
}
```

## Performance Considerations

### Design Principles

1. **Optimize for the common case**: Untied scalars should have zero performance overhead
2. **Branch prediction friendly**: The `TIED` case will be rare, so place it appropriately in switch statements
3. **Avoid virtual dispatch**: Use type-based switching rather than polymorphism for the fast path
4. **Method splitting**: Keep tied scalar logic in separate methods when complex

### Switch Statement Ordering

Place the `TIED` case after common types but before expensive operations:

```java
return switch (type) {
    case INTEGER -> /* fast path */;
    case DOUBLE -> /* fast path */;
    case STRING -> /* fast path */;
    case UNDEF -> /* fast path */;
    case BOOLEAN -> /* fast path */;
    case TIED -> /* tied path */;
    case REFERENCE -> /* expensive path */;
    // ...
};
```

## Array and Hash Tie Support

### Array Tie Interface

```java
public interface ArrayTieHandler {
    PlObject FETCH(int index);
    void STORE(int index, PlObject value);
    int FETCHSIZE();
    void STORESIZE(int size);
    boolean EXISTS(int index);
    void DELETE(int index);
    void CLEAR();
    void PUSH(PlObject... values);
    PlObject POP();
    PlObject SHIFT();
    void UNSHIFT(PlObject... values);
    void UNTIE();
}
```

### Hash Tie Interface

```java
public interface HashTieHandler {
    PlObject FETCH(String key);
    void STORE(String key, PlObject value);
    boolean EXISTS(String key);
    void DELETE(String key);
    void CLEAR();
    String FIRSTKEY();
    String NEXTKEY(String key);
    int SCALAR();
    void UNTIE();
}
```

## Error Handling

### Tie Handler Exceptions

- Tie handler method calls should propagate exceptions to the caller
- Invalid tie handler objects should throw `PlDieException`
- Attempting to tie an already tied variable should untie first, then retie

### Runtime Checks

```java
public void tie(TieHandler handler) {
    if (handler == null) {
        throw new PlDieException("Tie handler cannot be null");
    }
    if (this.type == TIED) {
        this.untie();  // Untie existing handler first
    }
    this.type = TIED;
    this.value = handler;
    this.blessId = 0;
}
```

## Testing Requirements

### Unit Tests

1. **Basic tie/untie operations**
2. **All scalar operations with tied scalars**
3. **Performance regression tests**
4. **Exception handling**
5. **Multiple tie/untie cycles**
6. **Interaction with blessing system**

### Performance Tests

1. **Benchmark untied scalar operations** (should show no regression)
2. **Benchmark tied scalar operations** (establish baseline)
3. **Memory usage tests** (tied scalars should not leak)

## Migration Notes

### Backward Compatibility

- All existing scalar operations continue to work unchanged
- No performance impact on existing code
- New tie functionality is purely additive

### Implementation Phases

1. **Phase 1**: Add `TIED` type constant and basic tie/untie methods
2. **Phase 2**: Update all scalar read operations
3. **Phase 3**: Update all scalar write operations  
4. **Phase 4**: Add array and hash tie support
5. **Phase 5**: Add comprehensive test suite

## Example Usage

```java
// Create a tie handler
TieHandler handler = new MyTieHandler();

// Tie a scalar
PlScalar scalar = new PlScalar();
scalar.tie(handler);

// Operations are now intercepted
scalar.set(new PlString("hello"));  // Calls handler.STORE()
String value = scalar.getString();  // Calls handler.FETCH().getString()

// Untie the scalar
scalar.untie();  // Back to normal scalar behavior
```


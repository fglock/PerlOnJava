# PerlOnJava Variable and Value Organization

## Table of Contents
1. [Overview](#overview)
2. [Core Architecture](#core-architecture)
3. [Type System](#type-system)
4. [Variable Hierarchy](#variable-hierarchy)
5. [Memory Management and Caching](#memory-management-and-caching)
6. [Autovivification System](#autovivification-system)
7. [Proxy Pattern and Lazy Evaluation](#proxy-pattern-and-lazy-evaluation)
8. [Global Variable Management](#global-variable-management)
9. [Operator Overloading](#operator-overloading)
10. [Specialized Types](#specialized-types)
11. [Context System](#context-system)
12. [Dynamic State Management](#dynamic-state-management)

## Overview

PerlOnJava implements Perl's dynamic type system and variable semantics in Java through a sophisticated runtime architecture. The system handles Perl's unique features including:

- Dynamic typing with automatic conversions
- Autovivification (automatic creation of data structures)
- References and dereferencing
- Global and lexical variable scoping
- Operator overloading
- Context-sensitive operations (scalar vs list context)

## Core Architecture

### Base Class Hierarchy

```
RuntimeBase (abstract)
├── RuntimeScalar
├── RuntimeArray  
├── RuntimeHash
├── RuntimeList
├── RuntimeCode
├── RuntimeIO
├── RuntimeGlob
└── RuntimeRegex
```

**RuntimeBase** serves as the foundation, providing:
- Common interfaces (`RuntimeDataProvider`, `DynamicState`, `Iterable<RuntimeScalar>`)
- Blessing support for object-oriented features
- Element counting and iteration
- Reference creation capabilities

## Type System

### RuntimeScalarType Constants

PerlOnJava uses integer constants to represent Perl's dynamic types:

```java
// Basic types (0-8)
INTEGER = 0      // int values
DOUBLE = 1       // double values  
STRING = 2       // String values
UNDEF = 3        // undefined values
VSTRING = 4      // version strings
BOOLEAN = 5      // boolean values
GLOB = 6         // typeglobs
REGEX = 7        // compiled regexes
JAVAOBJECT = 8   // Java objects

// Reference types (with high bit set)
REFERENCE_BIT = 0x8000
CODE = 9 | REFERENCE_BIT           // subroutine references
REFERENCE = 10 | REFERENCE_BIT     // scalar references
ARRAYREFERENCE = 11 | REFERENCE_BIT // array references
HASHREFERENCE = 12 | REFERENCE_BIT  // hash references
GLOBREFERENCE = 13 | REFERENCE_BIT  // glob references
```

### Type Conversions

RuntimeScalar implements Perl's automatic type conversion:

```java
public int getInt() {
    return switch (type) {
        case INTEGER -> (int) value;
        case DOUBLE -> (int) ((double) value);
        case STRING -> NumberParser.parseNumber(this).getInt();
        case UNDEF -> 0;
        case BOOLEAN -> (boolean) value ? 1 : 0;
        // ... other cases
        default -> Overload.numify(this).getInt(); // Handle overloaded objects
    };
}
```

## Variable Hierarchy

### RuntimeScalar - The Universal Container

RuntimeScalar is the core variable type that can hold any Perl value:

```java
public class RuntimeScalar extends RuntimeBase {
    public int type;        // Type from RuntimeScalarType
    public Object value;    // The actual value
    
    // Multiple constructors for different types
    public RuntimeScalar(int value) { ... }
    public RuntimeScalar(String value) { ... }
    public RuntimeScalar(RuntimeArray value) { ... }
    // ... etc
}
```

**Key Features:**
- Dynamic type changes at runtime
- Automatic conversions between types
- Reference and dereference operations
- Autovivification support

### RuntimeArray - Dynamic Lists

```java
public class RuntimeArray extends RuntimeBase {
    public List<RuntimeScalar> elements;
    
    // Perl array operations
    public static RuntimeScalar push(RuntimeArray array, RuntimeDataProvider value);
    public static RuntimeScalar pop(RuntimeArray array);
    public static RuntimeScalar shift(RuntimeArray array);
    public static RuntimeScalar unshift(RuntimeArray array, RuntimeDataProvider value);
}
```

**Features:**
- Dynamic resizing
- Negative indexing support
- Slice operations
- Lazy autovivification through proxies

### RuntimeHash - Associative Arrays

```java
public class RuntimeHash extends RuntimeBase {
    public Map<String, RuntimeScalar> elements;
    
    public RuntimeScalar get(String key);
    public RuntimeScalar exists(RuntimeScalar key);
    public RuntimeScalar delete(RuntimeScalar key);
}
```

**Features:**
- String keys with RuntimeScalar values
- Hash slicing support
- Iterator for each() operations
- Lazy key creation

## Memory Management and Caching

### RuntimeScalarCache - Performance Optimization

PerlOnJava caches frequently used values to reduce object creation:

```java
public class RuntimeScalarCache {
    // Cached common values
    public static RuntimeScalarReadOnly scalarTrue;
    public static RuntimeScalarReadOnly scalarFalse;
    public static RuntimeScalarReadOnly scalarUndef;
    public static RuntimeScalarReadOnly scalarEmptyString;
    
    // Integer cache for range -100 to 100
    static RuntimeScalarReadOnly[] scalarInt = new RuntimeScalarReadOnly[201];
    
    // Dynamic string cache with LRU eviction
    private static volatile RuntimeScalarReadOnly[] scalarString;
}
```

**Caching Strategy:**
- Small integers (-100 to 100) are pre-cached
- Common boolean and undefined values are singletons
- Strings up to 100 characters are cached with LRU eviction
- Thread-safe concurrent access

### RuntimeScalarReadOnly - Immutable Values

Cached values are immutable to prevent accidental modification:

```java
public class RuntimeScalarReadOnly extends RuntimeBaseProxy {
    final boolean b;
    final int i;
    final String s;
    final double d;
    
    @Override
    void vivify() {
        throw new RuntimeException("Modification of a read-only value attempted");
    }
}
```

## Autovivification System

Autovivification is Perl's automatic creation of data structures when accessed. PerlOnJava implements this through a sophisticated proxy system.

### How Autovivification Works

1. **Undefined Access**: When accessing `$undef_var->[0]` or `$undef_var->{key}`
2. **Proxy Creation**: Returns a special autovivification container
3. **Lazy Conversion**: On first write, converts undefined scalar to proper reference

### AutovivificationArray

```java
public class AutovivificationArray extends ArrayList<RuntimeScalar> {
    public RuntimeScalar scalarToAutovivify;
    
    public void vivify(RuntimeArray array) {
        // Convert undefined scalar to array reference
        scalarToAutovivify.value = array;
        scalarToAutovivify.type = RuntimeScalarType.ARRAYREFERENCE;
    }
}
```

### AutovivificationHash

```java
public class AutovivificationHash extends HashMap<String, RuntimeScalar> {
    public RuntimeScalar scalarToAutovivify;
    
    public void vivify(RuntimeHash hash) {
        // Convert undefined scalar to hash reference  
        scalarToAutovivify.value = hash;
        scalarToAutovivify.type = HASHREFERENCE;
    }
}
```

### Example Autovivification Flow

```perl
my $var;           # $var is undef
$var->[0] = 42;    # Autovivifies $var to array reference
```

1. `$var` starts as `UNDEF`
2. `arrayDeref()` creates `RuntimeArray` with `AutovivificationArray`
3. Assignment triggers `vivify()`, converting `$var` to `ARRAYREFERENCE`

## Proxy Pattern and Lazy Evaluation

PerlOnJava uses proxies extensively for lazy evaluation and special lvalue behavior.

### RuntimeBaseProxy - Abstract Proxy Base

```java
public abstract class RuntimeBaseProxy extends RuntimeScalar {
    RuntimeScalar lvalue;  // The actual target scalar
    
    abstract void vivify(); // Ensure target exists
    
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        vivify(); // Create target if needed
        this.lvalue.set(value);
        // Update proxy state
        this.type = lvalue.type;
        this.value = lvalue.value;
        return lvalue;
    }
}
```

### Specific Proxy Implementations

#### RuntimeArrayProxyEntry
Handles array element access with bounds checking:

```java
public class RuntimeArrayProxyEntry extends RuntimeBaseProxy {
    private final RuntimeArray parent;
    private final int key;
    
    void vivify() {
        if (lvalue == null) {
            // Extend array if needed
            if (key >= parent.elements.size()) {
                for (int i = parent.elements.size(); i <= key; i++) {
                    parent.elements.add(new RuntimeScalar());
                }
            }
            lvalue = parent.elements.get(key);
        }
    }
}
```

#### RuntimeHashProxyEntry
Handles hash key access with lazy creation:

```java
public class RuntimeHashProxyEntry extends RuntimeBaseProxy {
    private final RuntimeHash parent;
    private final String key;
    
    void vivify() {
        if (lvalue == null) {
            if (!parent.elements.containsKey(key)) {
                parent.elements.put(key, new RuntimeScalar());
            }
            lvalue = parent.elements.get(key);
        }
    }
}
```

#### RuntimeArraySizeLvalue
Implements `$#array` (last index) as an lvalue:

```java
public class RuntimeArraySizeLvalue extends RuntimeBaseProxy {
    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        RuntimeArray parent = lvalue.arrayDeref();
        int newSize = value.getInt();
        
        if (newSize > currentSize) {
            parent.set(newSize, scalarUndef); // Extend
        } else {
            // Truncate
            while (newSize < currentSize) {
                parent.elements.removeLast();
                currentSize--;
            }
        }
        return this;
    }
}
```

## Global Variable Management

### GlobalVariable - Central Registry

```java
public class GlobalVariable {
    static final Map<String, RuntimeScalar> globalVariables = new HashMap<>();
    static final Map<String, RuntimeArray> globalArrays = new HashMap<>();
    static final Map<String, RuntimeHash> globalHashes = new HashMap<>();
    static final Map<String, RuntimeScalar> globalCodeRefs = new HashMap<>();
    static final Map<String, RuntimeGlob> globalIORefs = new HashMap<>();
    
    public static RuntimeScalar getGlobalVariable(String key) {
        RuntimeScalar var = globalVariables.get(key);
        if (var == null) {
            var = createSpecialVariable(key); // Handle $1, $2, etc.
            globalVariables.put(key, var);
        }
        return var;
    }
}
```

### NameNormalizer - Variable Name Resolution

```java
public class NameNormalizer {
    public static String normalizeVariableName(String variable, String defaultPackage) {
        // Handle special variables ($_, $1, $^A, etc.)
        if (SPECIAL_VARIABLES.contains(variable)) {
            defaultPackage = "main";
        }
        
        // Handle package qualification
        if (variable.contains("::")) {
            return variable; // Already qualified
        } else {
            return defaultPackage + "::" + variable;
        }
    }
}
```

## Operator Overloading

PerlOnJava supports Perl's operator overloading through a sophisticated dispatch system.

### Overload - Main Dispatch

```java
public class Overload {
    public static RuntimeScalar stringify(RuntimeScalar runtimeScalar) {
        int blessId = runtimeScalar.blessedId();
        if (blessId != 0) {
            OverloadContext ctx = OverloadContext.prepare(blessId);
            if (ctx != null) {
                // Try primary overload method
                RuntimeScalar result = ctx.tryOverload("(\"\"", new RuntimeArray(runtimeScalar));
                if (result != null) return result;
                
                // Try fallback mechanisms
                result = ctx.tryOverloadFallback(runtimeScalar, "(0+", "(bool");
                if (result != null) return result;
                
                // Try nomethod
                result = ctx.tryOverloadNomethod(runtimeScalar, "\"\"");
                if (result != null) return result;
            }
        }
        
        // Default conversion
        return new RuntimeScalar(((RuntimeBase) runtimeScalar.value).toStringRef());
    }
}
```

### OverloadContext - Method Resolution

```java
public class OverloadContext {
    final String perlClassName;
    final RuntimeScalar methodOverloaded;
    final RuntimeScalar methodFallback;
    
    public RuntimeScalar tryOverload(String methodName, RuntimeArray args) {
        RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(
            methodName, perlClassName, null, 0);
        if (method != null) {
            return RuntimeCode.apply(method, args, SCALAR).getFirst();
        }
        return null;
    }
}
```

## Specialized Types

### RuntimeCode - Subroutine References

```java
public class RuntimeCode extends RuntimeBase {
    public MethodHandle methodHandle;
    public Object codeObject;
    public String prototype;
    
    public RuntimeList apply(RuntimeArray args, int callContext) {
        return (RuntimeList) methodHandle.invoke(codeObject, args, callContext);
    }
}
```

### RuntimeGlob - Typeglobs

Typeglobs represent all symbol table entries for a name:

```java
public class RuntimeGlob extends RuntimeScalar {
    public String globName;
    public RuntimeScalar IO;
    
    public RuntimeScalar hashDerefGet(RuntimeScalar index) {
        return switch (index.toString()) {
            case "CODE" -> GlobalVariable.getGlobalCodeRef(globName);
            case "SCALAR" -> GlobalVariable.getGlobalVariable(globName);
            case "ARRAY" -> GlobalVariable.getGlobalArray(globName).createReference();
            case "HASH" -> GlobalVariable.getGlobalHash(globName).createReference();
            case "IO" -> IO;
            default -> new RuntimeScalar();
        };
    }
}
```

### RuntimeRegex - Compiled Patterns

```java
public class RuntimeRegex implements RuntimeScalarReference {
    public Pattern pattern;
    private RegexFlags regexFlags;
    private RuntimeScalar replacement;
    
    public static RuntimeBase matchRegex(RuntimeScalar quotedRegex, 
                                       RuntimeScalar string, int ctx) {
        // Complex regex matching logic with global state management
    }
}
```

## Context System

### RuntimeContextType - Calling Contexts

```java
public class RuntimeContextType {
    public static final int VOID = 0;     // No return value expected
    public static final int SCALAR = 1;   // Single value expected  
    public static final int LIST = 2;     // List of values expected
    public static final int RUNTIME = 3;  // Context determined at runtime
}
```

Context affects how operations behave:

```java
// In RuntimeArray
public RuntimeScalar scalar() {
    return getScalarInt(elements.size()); // Returns size in scalar context
}

public RuntimeList getList() {
    return new RuntimeList(this); // Returns elements in list context
}
```

## Dynamic State Management

### DynamicState Interface

PerlOnJava implements Perl's `local` keyword through state stacking:

```java
public interface DynamicState {
    void dynamicSaveState();    // Push current state
    void dynamicRestoreState(); // Pop and restore state
}
```

### Implementation in RuntimeScalar

```java
private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();

@Override
public void dynamicSaveState() {
    RuntimeScalar currentState = new RuntimeScalar();
    currentState.type = this.type;
    currentState.value = this.value;
    currentState.blessId = this.blessId;
    dynamicStateStack.push(currentState);
    
    // Reset current state
    this.type = UNDEF;
    this.value = null;
    this.blessId = 0;
}

@Override
public void dynamicRestoreState() {
    if (!dynamicStateStack.isEmpty()) {
        RuntimeScalar previousState = dynamicStateStack.pop();
        this.type = previousState.type;
        this.value = previousState.value;
        this.blessId = previousState.blessId;
    }
}
```

## Key Design Patterns

### 1. Proxy Pattern
- Lazy evaluation of array/hash elements
- Special lvalue behavior (array size, regex position)
- Autovivification support

### 2. Strategy Pattern  
- Type-specific operations through switch statements
- Overload dispatch system
- Context-sensitive behavior

### 3. Flyweight Pattern
- Cached immutable values for performance
- Shared instances for common values

### 4. Template Method Pattern
- RuntimeBase defines common structure
- Subclasses implement specific behavior

### 5. State Pattern
- Dynamic type changes in RuntimeScalar
- Context-dependent operation results

## Performance Considerations

1. **Caching**: Frequently used values are cached to reduce object creation
2. **Lazy Evaluation**: Proxies defer expensive operations until needed
3. **Type Optimization**: Fast switch statements for common type operations
4. **Memory Management**: LRU caches prevent unbounded growth
5. **Method Handles**: Efficient subroutine dispatch through Java's invoke dynamic

This architecture successfully bridges Perl's dynamic semantics with Java's static type system, providing both correctness and performance for Perl code execution.


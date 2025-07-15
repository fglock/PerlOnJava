# Functional Interface Approach for PerlOnJava Subroutines

## Overview

This document proposes replacing the current `MethodHandle`-based approach for subroutine invocation with a functional interface approach. This change aims to improve performance, type safety, and code clarity.

## Current Implementation Issues

The current implementation uses:
- `MethodHandle` for invoking generated methods
- Reflection to find and cache method handles
- Runtime type casting from `Object` to `RuntimeList`
- Complex error handling for `InvocationTargetException`

## Proposed Solution

### 1. Define a Functional Interface

```java
package org.perlonjava.runtime;

@FunctionalInterface
public interface PerlSubroutine {
    RuntimeList apply(RuntimeArray args, int ctx) throws Exception;
}
```

### 2. Update EmitterMethodCreator

```java
public class EmitterMethodCreator implements Opcodes {
    // ... existing code ...
    
    public static Class<?> createClassWithMethod(EmitterContext ctx, Node ast, boolean useTryCatch) {
        // ... existing setup ...
        
        // Define the class to implement PerlSubroutine
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ctx.javaClassInfo.javaClassName, null, 
                 "java/lang/Object", new String[]{"org/perlonjava/runtime/PerlSubroutine"});
        
        // ... rest of the method generation remains the same ...
        // The generated apply method already has the correct signature
        
        return loadBytecode(ctx, classData);
    }
}
```

### 3. Update RuntimeCode

```java
public class RuntimeCode extends RuntimeBase implements RuntimeScalarReference {
    // Replace MethodHandle with PerlSubroutine
    public PerlSubroutine subroutine;
    public boolean isStatic;
    
    // Remove the methodHandle field and codeObject field
    // The subroutine instance will contain the closure variables
    
    public RuntimeCode(PerlSubroutine subroutine, String prototype) {
        this.subroutine = subroutine;
        this.prototype = prototype;
    }
    
    public RuntimeList apply(RuntimeArray a, int callContext) {
        if (constantValue != null) {
            return new RuntimeList(constantValue);
        }
        
        try {
            // Wait for compilation if needed
            if (this.compilerSupplier != null) {
                this.compilerSupplier.get();
            }
            
            // Direct interface call - no reflection
            return subroutine.apply(a, callContext);
            
        } catch (Exception e) {
            // Simpler error handling
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }
    
    // Update makeCodeObject to create PerlSubroutine instances
    public static RuntimeScalar makeCodeObject(Object codeObject, String prototype) throws Exception {
        // Cast to PerlSubroutine interface
        PerlSubroutine subroutine = (PerlSubroutine) codeObject;
        
        // Create RuntimeCode with the subroutine
        RuntimeScalar codeRef = new RuntimeScalar(new RuntimeCode(subroutine, prototype));
        
        // Set the __SUB__ field if it exists
        try {
            Field field = codeObject.getClass().getDeclaredField("__SUB__");
            field.set(codeObject, codeRef);
        } catch (NoSuchFieldException e) {
            // Static subroutines might not have this field
        }
        
        return codeRef;
    }
}
```

### 4. Update Static Method Registration

For built-in modules like Symbol.java:

```java
public class Symbol {
    // Static subroutines can be defined as lambdas or method references
    private static final PerlSubroutine qualifySubroutine = (args, ctx) -> {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for qualify()");
        }
        
        RuntimeScalar object = args.get(0);
        RuntimeScalar packageName = args.size() > 1 
            ? args.get(1) 
            : new RuntimeScalar("main");
            
        RuntimeScalar result = object.type != RuntimeScalarType.STRING
            ? object
            : new RuntimeScalar(NameNormalizer.normalizeVariableName(
                object.toString(), packageName.toString()));
                
        return new RuntimeList(result);
    };
    
    public static void initialize() {
        // Register using the functional interface
        getGlobalCodeRef("Symbol::qualify").set(
            new RuntimeScalar(new RuntimeCode(qualifySubroutine, "$;$"))
        );
    }
}
```

## Performance Benefits

1. **Direct invocation**: Interface calls are faster than `MethodHandle.invoke()`
2. **Better JIT optimization**: The JVM can inline interface methods more effectively
3. **No boxing/unboxing**: Return type is known at compile time
4. **Simpler exception handling**: No `InvocationTargetException` wrapping

## Benchmarking

```java
@BenchmarkMode(Mode.Throughput)
@State(Scope.Thread)
public class SubroutineBenchmark {
    private RuntimeCode methodHandleCode;
    private RuntimeCode functionalCode;
    private RuntimeArray args;
    
    @Setup
    public void setup() {
        // Setup both implementations for comparison
        args = new RuntimeArray();
        // ... initialize codes
    }
    
    @Benchmark
    public RuntimeList methodHandleInvoke() {
        return methodHandleCode.apply(args, RuntimeContextType.SCALAR);
    }
    
    @Benchmark
    public RuntimeList functionalInvoke() {
        return functionalCode.apply(args, RuntimeContextType.SCALAR);
    }
}
```

## Migration Strategy

1. **Phase 1**: Add PerlSubroutine interface while keeping MethodHandle support
2. **Phase 2**: Update EmitterMethodCreator to generate classes implementing PerlSubroutine
3. **Phase 3**: Migrate built-in modules to use functional interfaces
4. **Phase 4**: Remove MethodHandle support after all code is migrated

## Potential Issues

1. **Binary compatibility**: Existing compiled classes won't implement the interface
2. **Memory**: Each lambda/anonymous class takes memory (but so do MethodHandles)
3. **Debugging**: Stack traces might be slightly different

## Conclusion

The functional interface approach provides cleaner code with modest performance improvements. The main benefits are:
- Type safety and better IDE support
- Simpler code with less reflection
- Potential for better JVM optimizations
- More idiomatic Java code

The performance gain is expected to be most noticeable in code that makes many subroutine calls, such as functional programming constructs (map, grep, etc.).

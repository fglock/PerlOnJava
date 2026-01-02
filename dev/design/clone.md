# Strategy for Implementing Deep Cloning with Circular References

When implementing deep cloning in a Java class, especially one that may involve circular references, it's crucial to ensure that the cloning process is efficient and avoids infinite loops. Below is a strategy to implement deep cloning with circular reference handling.

## Overview

The goal is to create a deep copy of an object, meaning all nested objects are also cloned. Circular references occur when an object indirectly references itself, which can lead to infinite loops during cloning. To handle this, we use a map to track already cloned objects.

## Steps for Implementing Deep Cloning

1. **Implement Cloneable Interface:**
   Ensure that the class and any nested classes implement the `Cloneable` interface and override the `clone()` method.

2. **Use a Map to Track Clones:**
   Use a `Map<Object, Object>` to keep track of objects that have already been cloned. This map will help in resolving circular references by storing the original object as the key and its clone as the value.

3. **Modify the Clone Method:**
   Modify the `clone()` method to check the map before cloning an object. If the object is already in the map, return the existing clone instead of creating a new one.

4. **Recursive Cloning:**
   Implement a recursive `clone(Map<Object, Object> clones)` method that uses the map to manage and resolve circular references.

5. **Handle Different Types:**
   Ensure that all classes involved in potential circular references implement a similar cloning strategy.

## Example Implementation

Here's an example implementation of the `clone()` method with circular reference handling for a class `RuntimeScalar`:

```java
@Override
public RuntimeScalar clone() {
    return clone(new HashMap<>());
}

private RuntimeScalar clone(Map<Object, Object> clones) {
    if (clones.containsKey(this)) {
        return (RuntimeScalar) clones.get(this);
    }

    try {
        RuntimeScalar cloned = (RuntimeScalar) super.clone();
        clones.put(this, cloned);

        // Deep copy of mutable objects with circular reference handling
        if (this.value instanceof Cloneable) {
            if (this.value instanceof RuntimeScalar) {
                cloned.value = ((RuntimeScalar) this.value).clone(clones);
            } else if (this.value instanceof RuntimeArray) {
                cloned.value = ((RuntimeArray) this.value).clone(clones);
            } else if (this.value instanceof RuntimeHash) {
                cloned.value = ((RuntimeHash) this.value).clone(clones);
            } else if (this.value instanceof RuntimeCode) {
                cloned.value = ((RuntimeCode) this.value).clone(clones);
            } else if (this.value instanceof RuntimeRegex) {
                cloned.value = ((RuntimeRegex) this.value).clone(clones);
            }
            // Add other types as necessary
        }
        return cloned;
    } catch (CloneNotSupportedException e) {
        throw new AssertionError("Cloning not supported", e);
    }
}
```

## Key Points

- **Map for Clones:** The `clones` map is essential for tracking objects that have already been cloned, preventing re-cloning and resolving circular references.
- **Recursive Cloning:** The `clone(Map<Object, Object> clones)` method is recursive and uses the map to manage and resolve circular references.
- **Handling Different Types:** Ensure that all classes involved in potential circular references implement a similar cloning strategy.

---

## Cloning Closures (RuntimeCode)

### How Closures Store Captured Variables

In PerlOnJava, closures are implemented as instances of dynamically generated classes. When a closure captures variables from its outer scope:

1. **Instance Fields:** Each captured variable becomes a public instance field on the generated class
   - Field names match the Perl variable names (e.g., `$x`, `@arr`, `%hash`)
   - Field types: `RuntimeScalar`, `RuntimeArray`, or `RuntimeHash` based on the sigil
   - Special `__SUB__` field holds a self-reference for recursive closures

2. **Constructor:** The generated class constructor accepts all captured variables as parameters and stores them in the instance fields

3. **Execution:** When the closure's `apply()` method runs, it loads captured variable values from the instance fields into local variables

**Example generated class structure:**

```java
public class org_perlonjava_anon123 extends Object {
    // Captured variables as instance fields
    public RuntimeScalar $x;
    public RuntimeArray @arr;
    public RuntimeHash %hash;
    public RuntimeScalar __SUB__;
    
    // Constructor stores captured variables
    public org_perlonjava_anon123(RuntimeScalar $x, RuntimeArray @arr, RuntimeHash %hash) {
        this.$x = $x;
        this.@arr = @arr;
        this.%hash = %hash;
    }
    
    // apply() method accesses instance fields
    public RuntimeList apply(RuntimeArray @_, int wantarray) {
        // Closure body has access to $x, @arr, %hash via instance fields
        // ...
    }
}
```

### Why Closure Cloning is Critical

When cloning a `RuntimeCode` that represents a closure:

- **Shallow clone problem:** If we only clone the `RuntimeCode` object but not the `codeObject` instance, both the original and cloned closures would share the same captured variable instances
- **Shared mutable state:** Changes to captured variables in one closure would affect the other
- **Fork/threads requirement:** For proper fork() and threads emulation, each clone must have independent copies of captured variables

### Strategy for Cloning RuntimeCode with Closures

```java
@Override
public RuntimeCode clone(Map<Object, Object> clones) {
    if (clones.containsKey(this)) {
        return (RuntimeCode) clones.get(this);
    }
    
    try {
        RuntimeCode cloned = (RuntimeCode) super.clone();
        clones.put(this, cloned);
        
        // Clone the code object instance (the closure instance)
        if (this.codeObject != null) {
            cloned.codeObject = cloneCodeObject(this.codeObject, clones);
        }
        
        // Clone prototype if present
        if (this.prototype != null && this.prototype instanceof Cloneable) {
            cloned.prototype = ((RuntimeScalar) this.prototype).clone(clones);
        }
        
        // MethodHandle and other immutable fields are shared (correct behavior)
        // - methodHandle: references the same generated method (shared code)
        // - subroutineName, packageName: immutable strings
        
        return cloned;
    } catch (CloneNotSupportedException e) {
        throw new AssertionError("Cloning not supported", e);
    }
}

/**
 * Deep clone a closure's code object (the instance of the generated class).
 * Uses reflection to clone all captured variable fields.
 */
private Object cloneCodeObject(Object codeObject, Map<Object, Object> clones) {
    if (codeObject == null) {
        return null;
    }
    
    // Check if already cloned
    if (clones.containsKey(codeObject)) {
        return clones.get(codeObject);
    }
    
    try {
        Class<?> codeObjectClass = codeObject.getClass();
        
        // Create a new instance using the default constructor
        // Note: Generated classes don't have a no-arg constructor, so we need
        // to use the constructor with captured variables
        Constructor<?> constructor = findAppropriateConstructor(codeObjectClass);
        
        // Get all instance fields (captured variables)
        Field[] fields = codeObjectClass.getDeclaredFields();
        Object[] constructorArgs = new Object[constructor.getParameterCount()];
        
        // Clone each captured variable field
        int argIndex = 0;
        for (Field field : fields) {
            field.setAccessible(true);
            Object fieldValue = field.get(codeObject);
            
            if (fieldValue == null) {
                constructorArgs[argIndex++] = null;
                continue;
            }
            
            // Deep clone based on field type
            Object clonedValue;
            if (fieldValue instanceof RuntimeScalar) {
                clonedValue = ((RuntimeScalar) fieldValue).clone(clones);
            } else if (fieldValue instanceof RuntimeArray) {
                clonedValue = ((RuntimeArray) fieldValue).clone(clones);
            } else if (fieldValue instanceof RuntimeHash) {
                clonedValue = ((RuntimeHash) fieldValue).clone(clones);
            } else if (fieldValue instanceof RuntimeCode) {
                clonedValue = ((RuntimeCode) fieldValue).clone(clones);
            } else {
                // For other types, shallow copy (or skip if immutable)
                clonedValue = fieldValue;
            }
            
            constructorArgs[argIndex++] = clonedValue;
        }
        
        // Create new instance with cloned captured variables
        Object clonedCodeObject = constructor.newInstance(constructorArgs);
        clones.put(codeObject, clonedCodeObject);
        
        // Handle __SUB__ self-reference after instance is created
        for (Field field : fields) {
            if (field.getName().equals("__SUB__")) {
                field.setAccessible(true);
                RuntimeScalar subRef = (RuntimeScalar) field.get(clonedCodeObject);
                if (subRef != null && subRef.value instanceof RuntimeCode) {
                    // Update __SUB__ to point to the cloned RuntimeCode
                    subRef.value = clones.get(this);  // 'this' is the cloned RuntimeCode
                }
            }
        }
        
        return clonedCodeObject;
    } catch (Exception e) {
        throw new RuntimeException("Failed to clone code object", e);
    }
}

private Constructor<?> findAppropriateConstructor(Class<?> codeObjectClass) {
    // Generated classes have exactly one constructor
    Constructor<?>[] constructors = codeObjectClass.getDeclaredConstructors();
    if (constructors.length > 0) {
        constructors[0].setAccessible(true);
        return constructors[0];
    }
    throw new RuntimeException("No constructor found for code object class");
}
```

### Special Considerations for Closure Cloning

1. **Shared vs Independent:**
   - **Shared:** The generated method code (`MethodHandle`) is correctly shared between clones
   - **Independent:** The instance fields (captured variables) must be deep-cloned
   
2. **Circular References via `__SUB__`:**
   - Recursive closures have a `__SUB__` field that references the closure itself
   - Must handle this circular reference carefully in the clones map
   - Update `__SUB__` after cloning to point to the cloned `RuntimeCode`

3. **Performance:**
   - Closure cloning is expensive due to reflection and deep copying
   - Consider lazy cloning strategies for fork/threads where possible
   - Cache constructor lookups if cloning many closures of the same type

4. **Clone Modes for Multiplicity:**
   - **INDEPENDENT (fork):** Deep clone all captured variables
   - **THREAD (ithreads):** Selectively clone based on `:shared` attribute
   - **COPY_ON_WRITE:** Clone on first modification (optimization)

### Example: Cloning a Closure

```perl
# Perl code
my $counter = 0;
my $increment = sub {
    $counter++;
    return $counter;
};

# After fork or threads->create, need to clone $increment
# The cloned closure must have its own independent $counter
```

**Implementation:**

```java
// Original closure
RuntimeCode originalClosure = ...;  // $increment

// Clone for fork/threads
Map<Object, Object> clones = new HashMap<>();
RuntimeCode clonedClosure = originalClosure.clone(clones);

// Now originalClosure and clonedClosure have independent $counter variables
// Changes to $counter in one closure don't affect the other
```

### Integration with Fork/Threads

When implementing fork() or threads via multiplicity (see `multiplicity.md`):

```java
// Fork emulation
PerlRuntime childRuntime = parentRuntime.clone(CloneMode.INDEPENDENT);
// All closures in childRuntime have independent captured variables

// Threads emulation
Set<String> sharedVars = getSharedVariables();  // Variables marked :shared
PerlRuntime threadRuntime = parentRuntime.clone(CloneMode.THREAD, sharedVars);
// Closures have independent copies except for :shared variables
```

This ensures that each fork/thread gets proper closure semantics without shared mutable state causing bugs.



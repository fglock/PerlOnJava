# Dynamic Scoping in PerlOnJava

This document explains how PerlOnJava implements Perl's dynamic scoping via the `local` keyword and how the same mechanism is used for other features.

## Overview

Perl's `local` keyword provides dynamic scoping: it temporarily saves a variable's value and restores it when the current scope exits. This is different from lexical scoping (`my`), which creates a new variable visible only in the current block.

```perl
$x = "global";
sub foo {
    local $x = "local";
    bar();  # sees $x = "local"
}
sub bar {
    print $x;  # prints "local" when called from foo()
}
foo();
print $x;  # prints "global"
```

## Implementation

### Core Components

#### 1. DynamicState Interface

All values that can be dynamically scoped implement `DynamicState`:

```java
public interface DynamicState {
    void dynamicSaveState();    // Save current state
    void dynamicRestoreState(); // Restore saved state
}
```

Implementations:
- `RuntimeScalar` - scalar variables
- `RuntimeArray` - array variables  
- `RuntimeHash` - hash variables
- `RuntimeGlob` - typeglobs
- `GlobalRuntimeArray` - global array localization (`local @array`)
- `GlobalRuntimeHash` - global hash localization (`local %hash`)
- `DeferBlock` - defer block execution
- `RegexState` - regex match state ($1, $2, etc.)

#### 2. DynamicVariableManager

Manages a stack of saved states:

```java
public class DynamicVariableManager {
    private static final Deque<DynamicState> variableStack = new ArrayDeque<>();
    
    // Save current state and push onto stack (4 overloads)
    public static RuntimeBase pushLocalVariable(RuntimeBase variable)     // returns variable
    public static RuntimeScalar pushLocalVariable(RuntimeScalar variable) // returns variable
    public static RuntimeGlob pushLocalVariable(RuntimeGlob variable)    // special: returns new glob from GlobalVariable.getGlobalIO()
    public static void pushLocalVariable(DynamicState variable)          // for DeferBlock, RegexState
    
    // Each overload calls variable.dynamicSaveState() and variableStack.addLast(variable).
    // The RuntimeGlob overload has special behavior: it returns the new glob obtained
    // from GlobalVariable.getGlobalIO(), not the original variable.
    
    // Restore all states back to a saved level
    public static void popToLocalLevel(int targetLocalLevel) {
        while (variableStack.size() > targetLocalLevel) {
            DynamicState variable = variableStack.removeLast();
            variable.dynamicRestoreState();
        }
    }
    
    // Get current stack level (saved at block entry)
    public static int getLocalLevel() {
        return variableStack.size();
    }
}
```

#### 3. RuntimeScalar State Management

Each `RuntimeScalar` has its own save stack:

```java
public class RuntimeScalar implements DynamicState {
    private static final Stack<RuntimeScalar> dynamicStateStack = new Stack<>();
    
    @Override
    public void dynamicSaveState() {
        // Save a copy of current state
        RuntimeScalar copy = new RuntimeScalar();
        copy.type = this.type;
        copy.value = this.value;
        copy.blessId = this.blessId;
        dynamicStateStack.push(copy);
        // Reset to undef — this is the key `local` behavior:
        // the variable is cleared after saving
        this.type = UNDEF;
        this.value = null;
        this.blessId = 0;
    }
    
    @Override
    public void dynamicRestoreState() {
        RuntimeScalar saved = dynamicStateStack.pop();
        this.type = saved.type;
        this.value = saved.value;
        this.blessId = saved.blessId;
    }
}
```

### Code Generation

When the compiler sees `local $x`:

1. **Block Entry**: Save current local level
   ```java
   int savedLevel = DynamicVariableManager.getLocalLevel();
   ```

2. **Local Assignment**: Save and modify variable
   ```java
   DynamicVariableManager.pushLocalVariable(variable);
   variable.set(newValue);
   ```

3. **Block Exit**: Restore all local variables (in finally block)
   ```java
   DynamicVariableManager.popToLocalLevel(savedLevel);
   ```

### Detection of Local Usage

`FindDeclarationVisitor` scans AST blocks to detect if `local` is used:

```java
public static boolean containsLocalOrDefer(Node blockNode) {
    FindDeclarationVisitor visitor = new FindDeclarationVisitor();
    visitor.operatorName = "local";
    blockNode.accept(visitor);
    return visitor.containsLocalOperator || visitor.containsDefer;
}
```

This allows the compiler to skip local setup/teardown for blocks that don't need it.

## Other Uses of DynamicVariableManager

The same mechanism is used for several other features:

### 1. Defer Blocks

`defer { ... }` blocks execute code when scope exits:

```perl
{
    defer { print "cleanup\n" }
    print "work\n";
}  # prints: work, cleanup
```

Implementation:
```java
public class DeferBlock implements DynamicState {
    private final RuntimeScalar codeRef;
    private final RuntimeArray capturedArgs;  // captures enclosing subroutine's @_
    
    @Override
    public void dynamicRestoreState() {
        // Execute the defer block (static call, uses capturedArgs)
        RuntimeCode.apply(codeRef, capturedArgs, RuntimeContextType.VOID);
    }
}
```

### 2. Regex State

Match variables (`$1`, `$2`, `$&`, etc.) are saved/restored:

```java
public class RegexState implements DynamicState {
    // Saves: captureGroups, lastMatch, prematch, postmatch, etc.
}
```

This ensures regex state is properly scoped in nested matches.

### 3. Warning Scope (${^WARNING_SCOPE})

Runtime warning suppression uses local semantics:

```perl
{
    no warnings 'DateTime';  # Sets local ${^WARNING_SCOPE} = scopeId
    DateTime->new(...);      # warnif() checks ${^WARNING_SCOPE}
}  # ${^WARNING_SCOPE} restored to 0
```

The `CompilerFlagNode` emits:
```java
GlobalRuntimeScalar.makeLocal("${^WARNING_SCOPE}");
scopeVar.set(scopeId);
```

### 4. Signal Handlers

`local $SIG{__WARN__}` and `local $SIG{__DIE__}` use the same mechanism:

```perl
{
    local $SIG{__WARN__} = sub { ... };
    # warnings go to custom handler
}  # original handler restored
```

## Exception Safety

`popToLocalLevel()` is exception-safe:

```java
public static void popToLocalLevel(int targetLevel) {
    Throwable pendingException = null;
    
    while (variableStack.size() > targetLevel) {
        DynamicState variable = variableStack.removeLast();
        try {
            variable.dynamicRestoreState();
        } catch (Throwable t) {
            // Continue cleanup, remember last exception
            pendingException = t;
        }
    }
    
    // Re-throw after all cleanup
    if (pendingException != null) {
        throw pendingException;
    }
}
```

This ensures:
1. All local variables are restored even if one throws
2. Defer blocks all execute even if one throws
3. The last exception "wins" (Perl semantics)

## Performance Considerations

1. **Stack Allocation**: Uses `ArrayDeque` (no synchronization overhead)
2. **Lazy Detection**: `containsLocalOrDefer()` avoids setup for blocks without `local`
3. **Per-Variable Stacks**: Each variable type manages its own save stack

## Files

| File | Purpose |
|------|---------|
| `DynamicState.java` | Interface for saveable state |
| `DynamicVariableManager.java` | Central stack management |
| `RuntimeScalar.java` | Scalar save/restore |
| `RuntimeArray.java` | Array save/restore |
| `RuntimeHash.java` | Hash save/restore |
| `GlobalRuntimeArray.java` | Implements DynamicState for global array localization |
| `GlobalRuntimeHash.java` | Implements DynamicState for global hash localization |
| `GlobalRuntimeScalar.java` | Contains `makeLocal()`, the primary entry point for `local $scalar` |
| `DeferBlock.java` | Defer block execution |
| `RegexState.java` | Regex state save/restore |
| `Local.java` | Code generation helpers |
| `FindDeclarationVisitor.java` | Detection of local usage |

## See Also

- [lexical-pragmas.md](lexical-pragmas.md) - How warnings/strict use this mechanism
- [../design/lexical-warnings.md](../design/lexical-warnings.md) - Warning scope design

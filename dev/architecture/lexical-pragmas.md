# Lexical Pragmas: Warnings and Strict

This document explains how PerlOnJava implements lexical pragmas like `use warnings`, `no warnings`, `use strict`, and `no strict`.

## Overview

Perl's pragmas are lexically scoped—they affect only the code in the current block and nested blocks, not code in called subroutines:

```perl
{
    use strict;
    use warnings;
    # strict and warnings enabled here
    foo();  # foo() has its own pragma state
}
# strict and warnings NOT enabled here
```

This is fundamentally different from dynamic scoping (`local`)—pragmas affect compilation, not runtime.

## Architecture

### Compile-Time vs Runtime

Pragmas have two aspects:

1. **Compile-Time**: Affects how code is parsed and compiled
   - `use strict 'vars'` - undeclared variable is a compile error
   - `use warnings 'syntax'` - emit warning during compilation

2. **Runtime**: Affects behavior of running code
   - `use warnings 'uninitialized'` - warn when using undef
   - `no warnings 'DateTime'` - suppress warnif() calls

PerlOnJava tracks both using different mechanisms.

## Compile-Time Implementation

### Symbol Table Stacks

`ScopedSymbolTable` maintains stacks for each pragma type:

```java
public class ScopedSymbolTable {
    // Warning flags - one BitSet per scope level
    public final Deque<BitSet> warningFlagsStack = new ArrayDeque<>();
    
    // Feature flags - integer bitmask per scope
    public final Deque<Integer> featureFlagsStack = new ArrayDeque<>();
    
    // Strict options - integer bitmask per scope
    public final Deque<Integer> strictOptionsStack = new ArrayDeque<>();
}
```

When entering a new scope (block, subroutine, file):
```java
void enterScope() {
    // Clone current flags for new scope
    warningFlagsStack.push((BitSet) warningFlagsStack.peek().clone());
    featureFlagsStack.push(featureFlagsStack.peek());
    strictOptionsStack.push(strictOptionsStack.peek());
}
```

### Warning Categories

`WarningFlags` defines the warning category hierarchy:

```java
public class WarningFlags {
    // Hierarchy: "all" contains all categories
    private static final Map<String, String[]> warningHierarchy = new HashMap<>();
    
    static {
        warningHierarchy.put("all", new String[]{
            "closure", "deprecated", "experimental", "io", 
            "numeric", "once", "redefine", "substr", "syntax",
            "uninitialized", "void", ...
        });
        warningHierarchy.put("io", new String[]{
            "io::closed", "io::exec", "io::layer", ...
        });
        // ... more subcategories
    }
    
    // Each category maps to a bit position
    public void enableWarning(String category) {
        int bit = getCategoryBit(category);
        currentFlags.set(bit);
        // Also enable subcategories
        for (String sub : getSubcategories(category)) {
            enableWarning(sub);
        }
    }
}
```

### Strict Options

Strict has three options encoded as bits:

```java
public class StrictOptions {
    public static final int STRICT_REFS = 1;  // no symbolic refs
    public static final int STRICT_VARS = 2;  // must declare variables
    public static final int STRICT_SUBS = 4;  // no barewords as subs
}
```

### CompilerFlagNode

When pragmas change, the parser creates a `CompilerFlagNode`:

```java
public class CompilerFlagNode extends AbstractNode {
    private final BitSet warningFlags;
    private final int featureFlags;
    private final int strictOptions;
    private final int warningScopeId;  // For runtime propagation
}
```

This node is inserted into the AST to update compiler state during code generation.

## Runtime Implementation

### The Problem

Compile-time pragma tracking works for code compiled together. But what about:

```perl
# user_code.pl
{
    no warnings 'DateTime';
    DateTime->new(...);  # DateTime.pm calls warnif('DateTime', $msg)
}
```

DateTime.pm is compiled separately—it doesn't know about user_code.pl's `no warnings`.

### Solution: ${^WARNING_SCOPE}

We use a runtime mechanism with dynamic scoping:

1. **Scope Registration**: Each `no warnings 'category'` block gets a unique scope ID:
   ```java
   public static int registerScopeWarnings(Set<String> categories) {
       int scopeId = scopeIdCounter.incrementAndGet();
       scopeDisabledWarnings.put(scopeId, expandCategories(categories));
       return scopeId;
   }
   ```

2. **Local Assignment**: `CompilerFlagNode` emits:
   ```java
   // local ${^WARNING_SCOPE} = scopeId
   GlobalRuntimeScalar.makeLocal("${^WARNING_SCOPE}");
   scopeVar.set(scopeId);
   ```

3. **Runtime Check**: `warnif()` checks the scope:
   ```java
   public static RuntimeList warnIf(RuntimeArray args, int ctx) {
       int scopeId = GlobalVariable.getGlobalVariable(WARNING_SCOPE).getInt();
       if (scopeId > 0 && isWarningDisabledInScope(scopeId, category)) {
           return new RuntimeScalar().getList();  // Suppressed
       }
       // ... emit warning
   }
   ```

4. **Automatic Restore**: When scope exits, `DynamicVariableManager` restores `${^WARNING_SCOPE}` to its previous value.

### Integration Points

Runtime warning checks are needed in:

| Location | Warning Category |
|----------|-----------------|
| `RuntimeIO.java` | `syscalls` (nul in pathname) |
| `Warnings.java` | Custom categories via `warnif()` |
| `CompareOperators.java` | `uninitialized` |
| `Operator.java` | `substr`, `numeric` |

Example check:
```java
if (Warnings.warningManager.isWarningEnabled("syscalls") 
        && !WarningFlags.isWarningSuppressedAtRuntime("syscalls")) {
    WarnDie.warn(message, location);
}
```

## Custom Warning Categories

Modules can register custom categories via `warnings::register`:

```perl
package DateTime;
use warnings::register;  # Registers "DateTime" category
```

Implementation:
```java
public static void registerCategory(String category) {
    customCategories.add(category);
    // Allocate a bit for this category
    symbolTable.registerCustomWarningCategory(category);
}
```

Then `warnif()` uses the custom category:
```perl
warnings::warnif('DateTime', $message);
```

## Code Flow

### Compilation

```
use warnings 'all';
    │
    ▼
StatementParser.parseUseDeclaration()
    │
    ▼
Warnings.useWarnings()  ──► warningManager.enableWarning("all")
    │
    ▼
CompilerFlagNode created with current flags
    │
    ▼
EmitCompilerFlag.emitCompilerFlag()
    │
    ▼
Symbol table stacks updated
```

### Runtime (no warnings)

```
no warnings 'DateTime';
    │
    ▼
Warnings.noWarnings()
    │
    ├──► warningManager.disableWarning("DateTime")  [compile-time]
    │
    └──► WarningFlags.registerScopeWarnings({"DateTime"})  [runtime]
              │
              ▼
         lastScopeId = N
    │
    ▼
CompilerFlagNode(warningScopeId=N)
    │
    ▼
EmitCompilerFlag.emitWarningScopeLocal()
    │
    ▼
Bytecode: local ${^WARNING_SCOPE} = N
```

### Warning Emission

```
DateTime->new(year => 5001, ...)
    │
    ▼
DateTime::_warn() calls warnif('DateTime', $msg)
    │
    ▼
Warnings.warnIf()
    │
    ├──► Check ${^WARNING_SCOPE}
    │        scopeId = N > 0?
    │        isWarningDisabledInScope(N, 'DateTime')?
    │        YES → return (suppressed)
    │
    └──► Check compile-time flags
             warningManager.isWarningEnabled('DateTime')?
             YES → WarnDie.warn()
```

## Files

| File | Purpose |
|------|---------|
| `WarningFlags.java` | Warning category management, scope tracking |
| `Warnings.java` | `use warnings`, `no warnings`, `warnif()` |
| `Strict.java` | `use strict`, `no strict` |
| `Feature.java` | `use feature` |
| `ScopedSymbolTable.java` | Compile-time flag stacks |
| `CompilerFlagNode.java` | AST node for pragma changes |
| `EmitCompilerFlag.java` | Bytecode emission for pragmas |
| `StatementParser.java` | Parses `use`/`no` statements |
| `GlobalContext.java` | Initializes `${^WARNING_SCOPE}` |

## See Also

- [dynamic-scope.md](dynamic-scope.md) - How `local` makes runtime scoping work
- [../design/warnings-scope.md](../design/warnings-scope.md) - Detailed design for warning scope propagation

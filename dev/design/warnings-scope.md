# Lexical Warning Scope Propagation

## Problem Statement

When a user writes:
```perl
{
    no warnings 'DateTime';
    my $dt = DateTime->new(...);  # DateTime.pm calls warnings::warnif('DateTime', $msg)
}
```

The `no warnings 'DateTime'` suppression should propagate to `warnif()` calls made by DateTime.pm. Currently, this doesn't work because:

1. `no warnings` sets compile-time flags in the symbol table
2. `warnif()` is called at runtime from DateTime.pm (a different compilation unit)
3. DateTime.pm's symbol table doesn't have the caller's warning suppression

## Solution: Scope-based Warning Tracking

Use a runtime mechanism similar to how `eval STRING` stores parse state globally:

1. **Compile time**: When `no warnings 'category'` is encountered:
   - Assign a unique scope ID
   - Store the disabled categories for that scope ID in a static map
   - Set `lastScopeId` for the statement parser to read

2. **Code generation**: After processing `no warnings`, emit:
   ```perl
   local $^WARNING_SCOPE = $scopeId;
   ```

3. **Runtime**: When `warnif($category, $msg)` is called:
   - Read `$^WARNING_SCOPE` to get the current scope ID
   - Look up disabled categories for that scope ID
   - Suppress warning if category is disabled

## Implementation Plan

### Phase 1: Infrastructure (WarningFlags.java) ✓ DONE

Add scope tracking to `WarningFlags.java`:

```java
// Scope ID counter
private static final AtomicInteger scopeIdCounter = new AtomicInteger(0);

// Map: scope ID -> set of disabled categories
private static final Map<Integer, Set<String>> scopeDisabledWarnings = new HashMap<>();

// Last scope ID (read by StatementParser after noWarnings())
private static int lastScopeId = 0;

// Register a scope and return its ID
public static int registerScopeWarnings(Set<String> categories) {
    int scopeId = scopeIdCounter.incrementAndGet();
    Set<String> expanded = expandCategories(categories);
    scopeDisabledWarnings.put(scopeId, expanded);
    lastScopeId = scopeId;
    return scopeId;
}

// Check if category is disabled in scope
public static boolean isWarningDisabledInScope(int scopeId, String category) {
    Set<String> disabled = scopeDisabledWarnings.get(scopeId);
    return disabled != null && (disabled.contains(category) || disabled.contains("all"));
}

// Get/clear lastScopeId
public static int getLastScopeId() { return lastScopeId; }
public static void clearLastScopeId() { lastScopeId = 0; }
```

### Phase 2: noWarnings() Registration (Warnings.java)

Modify `noWarnings()` to register the scope:

```java
public static RuntimeList noWarnings(RuntimeArray args, int ctx) {
    Set<String> categories = new HashSet<>();
    
    if (args.size() <= 1) {
        categories.add("all");
    } else {
        for (int i = 1; i < args.size(); i++) {
            String category = args.get(i).toString();
            if (!warningExists(category)) {
                throw new PerlCompilerException("Unknown warnings category '" + category + "'");
            }
            categories.add(category);
        }
    }
    
    // Register scope for runtime checking (sets lastScopeId)
    WarningFlags.registerScopeWarnings(categories);
    
    // Also update compile-time flags (existing behavior)
    for (String category : categories) {
        warningManager.disableWarning(category);
    }
    
    return new RuntimeScalar().getList();
}
```

### Phase 3: Global Variable $^WARNING_SCOPE (GlobalContext.java)

Initialize `$^WARNING_SCOPE` to 0:

```java
// In GlobalContext initialization
GlobalVariable.getGlobalVariable("main::" + Character.toString('W' - 'A' + 1 + 128))
    .set(0);  // $^WARNING_SCOPE - encoded as special char
```

Or use a named variable:
```java
GlobalVariable.getGlobalVariable("main::^WARNING_SCOPE").set(0);
```

### Phase 4: Emit local Assignment (StatementParser.java)

After `warnings->unimport()` returns, check if a scope was registered and emit `local $^WARNING_SCOPE = scopeId`:

```java
// In parseUseDeclaration, after calling unimport:
int warningScopeId = WarningFlags.getLastScopeId();
WarningFlags.clearLastScopeId();

if (warningScopeId > 0) {
    // Create AST for: local $^WARNING_SCOPE = scopeId
    // This will be emitted as part of the block's statements
}
```

**Alternative approach**: Instead of emitting AST, have the `CompilerFlagNode` carry the scope ID and emit the local assignment during code generation.

### Phase 5: warnif() Check (Warnings.java)

Modify `warnIf()` to check `$^WARNING_SCOPE`:

```java
public static RuntimeList warnIf(RuntimeArray args, int ctx) {
    // ... existing arg parsing ...
    
    // Check runtime scope suppression first
    RuntimeScalar scopeVar = GlobalVariable.getGlobalVariable("main::^WARNING_SCOPE");
    int scopeId = scopeVar.getInt();
    if (scopeId > 0 && WarningFlags.isWarningDisabledInScope(scopeId, category)) {
        // Warning is suppressed by caller's "no warnings"
        return new RuntimeScalar().getList();
    }
    
    // Fall back to compile-time check
    if (warningManager.isWarningEnabled(category)) {
        WarnDie.warn(message, new RuntimeScalar(""));
    }
    return new RuntimeScalar().getList();
}
```

## How `local` Makes This Work

The key insight is Perl's `local` mechanism:

```perl
{
    no warnings 'DateTime';
    # At this point: local $^WARNING_SCOPE = 42
    # DynamicVariableManager saves old value (0) and sets new value (42)
    
    DateTime->new(...);
    # DateTime.pm calls warnif('DateTime', $msg)
    # warnif() reads $^WARNING_SCOPE = 42
    # Looks up scope 42 -> 'DateTime' is disabled
    # Warning suppressed!
    
}  # Block exit: DynamicVariableManager restores $^WARNING_SCOPE = 0
```

The `local` keyword in Perl (implemented via `DynamicVariableManager`) automatically:
1. Saves the current value when entering the scope
2. Restores the old value when exiting the scope (even on exceptions)

This is the same mechanism used for `local $SIG{__WARN__}`, `local $/`, etc.

## Files to Modify

| File | Changes |
|------|---------|
| `WarningFlags.java` | Add scope tracking infrastructure ✓ |
| `Warnings.java` | Call `registerScopeWarnings()` in `noWarnings()`, check scope in `warnIf()` |
| `GlobalContext.java` | Initialize `$^WARNING_SCOPE` |
| `StatementParser.java` | Emit `local $^WARNING_SCOPE = id` after `no warnings` |
| (maybe) `CompilerFlagNode.java` | Add `warningScopeId` field |
| (maybe) `EmitCompilerFlag.java` | Emit local assignment bytecode |

## Testing

Test file: `t/46warnings.t` from DateTime

```perl
use warnings;
use Test::Warnings ':all';

# Test 1: Warning emitted normally
warning_like { DateTime->_warn('test') } qr/test/;

# Test 2: Warning suppressed with no warnings
{
    no warnings 'DateTime';
    my @warnings = warnings { DateTime->_warn('test') };
    is(scalar @warnings, 0, 'warning suppressed');
}

# Test 3: Warning emitted after scope exits
warning_like { DateTime->_warn('test') } qr/test/;
```

## Progress Tracking

### Current Status: COMPLETE (2024-03-22)

All phases implemented and tested. DateTime t/46warnings.t passes 6/6.

### Completed Phases
- [x] Phase 1: Infrastructure (2024-03-22)
  - Added `scopeIdCounter`, `scopeDisabledWarnings` map
  - Added `registerScopeWarnings()`, `isWarningDisabledInScope()`
  - Added `getLastScopeId()`, `clearLastScopeId()`
  - Files: `WarningFlags.java`

- [x] Phase 2: noWarnings() Registration (2024-03-22)
  - Modified `noWarnings()` to collect categories and call `registerScopeWarnings()`
  - Files: `Warnings.java`

- [x] Phase 3: Global Variable (2024-03-22)
  - Added `${^WARNING_SCOPE}` initialization to GlobalContext
  - Added `WARNING_SCOPE` constant
  - Files: `GlobalContext.java`

- [x] Phase 4: Code Generation (2024-03-22)
  - Added `warningScopeId` field to `CompilerFlagNode`
  - Modified StatementParser to pass scope ID from `noWarnings()` to `CompilerFlagNode`
  - Modified `EmitCompilerFlag` to emit `local ${^WARNING_SCOPE} = scopeId`
  - Modified `FindDeclarationVisitor` to detect `CompilerFlagNode` with scope ID for cleanup
  - Files: `CompilerFlagNode.java`, `StatementParser.java`, `EmitCompilerFlag.java`, `FindDeclarationVisitor.java`

- [x] Phase 5: warnIf() Check (2024-03-22)
  - Modified `warnIf()` to check `${^WARNING_SCOPE}` before emitting warnings
  - Files: `Warnings.java`

### Resolved Questions
- Using `${^WARNING_SCOPE}` (caret variable syntax via `encodeSpecialVar`)
- Scope IDs are kept for entire runtime (no cleanup needed - they're small integers)
- Memory is bounded by number of unique `no warnings` statements in codebase

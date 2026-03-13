# PerlSubroutine Functional Interface Implementation Plan

## Overview

This document tracks the implementation of replacing `MethodHandle`-based subroutine invocation with a `PerlSubroutine` functional interface. This fixes MethodHandle conversion errors that occur at runtime.

## Problem Statement

The current implementation uses `MethodHandle` for invoking compiled subroutines:
```java
// Current approach - prone to signature mismatch errors
if (isStatic) {
    result = (RuntimeList) this.methodHandle.invoke(a, callContext);
} else {
    result = (RuntimeList) this.methodHandle.invoke(this.codeObject, a, callContext);
}
```

This causes errors like:
```
cannot convert MethodHandle(anon200,RuntimeArray,int)RuntimeList to (RuntimeArray,int)RuntimeList
```

The error occurs when the cached MethodHandle signature doesn't match the invocation pattern (static vs instance).

## Solution

Replace MethodHandle with a functional interface that has a fixed signature:

```java
@FunctionalInterface
public interface PerlSubroutine {
    RuntimeList apply(RuntimeArray args, int callContext) throws Exception;
}
```

Benefits:
1. **Type safety**: Fixed signature eliminates conversion errors
2. **Performance**: Direct interface calls are faster than MethodHandle.invoke()
3. **JIT optimization**: Better inlining opportunities
4. **Simplicity**: No need for separate `codeObject` field - the subroutine IS the object

## Scope

### What Changes
1. JVM-compiled subroutines (EmitterMethodCreator)
2. RuntimeCode invocation logic
3. PerlModuleBase static method registration
4. Inline method cache (callCached)

### What Doesn't Change
1. InterpretedCode - already overrides `apply()`, no MethodHandle used
2. eval STRING - uses either JVM or interpreter path, both covered
3. API signatures - `apply(RuntimeArray, int)` remains the same

## Implementation Phases

### Phase 1: Create Interface (COMPLETED)
- [x] Create `PerlSubroutine.java` functional interface
- File: `src/main/java/org/perlonjava/runtime/runtimetypes/PerlSubroutine.java`

### Phase 2: Update EmitterMethodCreator (COMPLETED)
- [x] Add `implements PerlSubroutine` to generated classes
- [x] Change `cw.visit()` to include interface in interfaces array
- File: `src/main/java/org/perlonjava/backend/jvm/EmitterMethodCreator.java`
- Line ~437: Changed from `null` to `new String[]{"org/perlonjava/runtime/runtimetypes/PerlSubroutine"}`

### Phase 3: Update RuntimeCode (COMPLETED)
- [x] Add `public PerlSubroutine subroutine;` field
- [x] Add constructor `RuntimeCode(PerlSubroutine subroutine, String prototype)`
- [x] Keep `methodHandle` and `codeObject` for backward compatibility during migration
- [x] Update `defined()` to check `subroutine != null`
- [x] Update `copy()` to copy `subroutine` field
- File: `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`

### Phase 4: Update makeCodeObject() (COMPLETED)
- [x] Cast codeObject to PerlSubroutine: `PerlSubroutine subroutine = (PerlSubroutine) codeObject;`
- [x] Create RuntimeCode with subroutine: `new RuntimeCode(subroutine, prototype)`
- File: `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- Method: `makeCodeObject()` (~line 1181)

### Phase 5: Update RuntimeCode.apply() (COMPLETED)
- [x] Prefer `subroutine.apply()` over `methodHandle.invoke()`
- [x] Keep methodHandle path as fallback for backward compatibility
- File: `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- Methods: `apply()` (~line 2019, ~line 2097)

### Phase 6: Update PerlModuleBase (SKIPPED)
- Note: Keeping methodHandle approach for PerlModuleBase to preserve caller() stack behavior
- Early attempts to change this broke export_to_level tests
- May revisit later if needed
- File: `src/main/java/org/perlonjava/runtime/perlmodule/PerlModuleBase.java`

### Phase 7: Update Inline Cache (callCached) (COMPLETED)
- [x] Change cache to check `subroutine != null || methodHandle != null`
- [x] Prefer `cachedCode.subroutine.apply()` over MethodHandle
- [x] Fall back to methodHandle when subroutine not available
- File: `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- Method: `callCached()` (~line 1237)

### Phase 8: Update SubroutineParser and InterpretedCode (COMPLETED)
- [x] Update `SubroutineParser` to set `placeholder.subroutine` for deferred compilation
- [x] Update `subExists` check to include `subroutine != null`
- [x] Make `InterpretedCode` implement `PerlSubroutine` interface
- Files: SubroutineParser.java, InterpretedCode.java

### Phase 9: Testing (COMPLETED)
- [x] Run `./gradlew test` - all tests pass
- [x] Run comp/require.t - 1743/1747 pass (previously had MethodHandle errors)
- [x] No MethodHandle conversion errors observed
- [x] Basic subroutine calls, closures, and method calls work correctly

### Phase 10: Cleanup (PARTIAL - 2024-03-13)
- [x] Remove redundant `methodHandle` lookups in SubroutineParser deferred compilation
- [ ] Remove `methodHandle` field from RuntimeCode (blocked: PerlModuleBase still uses it)
- [ ] Remove `codeObject` field (blocked: still needed for __SUB__ field access)
- [ ] Remove `methodHandleCache` (low priority - not causing issues)
- [ ] Remove `isStatic` field (blocked: PerlModuleBase uses it)

Note: Full cleanup is blocked because PerlModuleBase uses methodHandle for static Java
methods. This is intentional to preserve caller() stack behavior for built-in modules.

## File Change Summary

| File | Changes |
|------|---------|
| PerlSubroutine.java | NEW - functional interface |
| EmitterMethodCreator.java | Add interface to generated classes |
| InterpretedCode.java | Implements PerlSubroutine interface |
| RuntimeCode.java | Add subroutine field, update apply(), makeCodeObject(), callCached() |
| SubroutineParser.java | Set subroutine field, removed redundant methodHandle lookups |
| RuntimeScalar.java | Constructor disambiguation |
| GlobalVariable.java | Constructor disambiguation |

## Backward Compatibility

During migration:
1. Keep both `subroutine` and `methodHandle` fields
2. Prefer `subroutine` when available, fall back to `methodHandle`
3. This allows gradual migration and easy rollback

## Risk Assessment

- **Risk**: Medium - touches core subroutine dispatch
- **Mitigation**: Keep methodHandle as fallback, comprehensive testing
- **Rollback**: Can revert to methodHandle-only if issues found

## Testing Strategy

1. Unit tests via `./gradlew test`
2. Integration tests via perl5_t/t test suite
3. Specific focus on:
   - comp/require.t (MethodHandle error)
   - Method calls with closures
   - Inline cache behavior
   - eval STRING execution

## Progress Tracking

### Current Status: Implementation Complete (Phase 9 passed)

### Completed Phases (2024-03-13)
- [x] Phase 1: Create PerlSubroutine interface
- [x] Phase 2: Update EmitterMethodCreator - generated classes implement PerlSubroutine
- [x] Phase 3: Update RuntimeCode - added subroutine field, constructor, copy(), defined()
- [x] Phase 4: Update makeCodeObject() - casts to PerlSubroutine
- [x] Phase 5: Update RuntimeCode.apply() - prefers subroutine over methodHandle
- [x] Phase 6: PerlModuleBase - SKIPPED (preserves caller() stack behavior)
- [x] Phase 7: Update callCached() inline cache
- [x] Phase 8: Update SubroutineParser and InterpretedCode
- [x] Phase 9: Testing - all tests pass, no MethodHandle conversion errors

### Files Changed
- `src/main/java/org/perlonjava/runtime/runtimetypes/PerlSubroutine.java` (NEW)
- `src/main/java/org/perlonjava/backend/jvm/EmitterMethodCreator.java`
- `src/main/java/org/perlonjava/backend/bytecode/InterpretedCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeCode.java`
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` (constructor disambiguation)
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalVariable.java` (constructor disambiguation)
- `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java`

### Next Steps (Optional)
1. Run more extensive tests from perl5_t/t suite
2. Consider Phase 10 cleanup to remove deprecated methodHandle fields

## Related Documents
- `dev/design/functional_subroutines.md` - Original design proposal
- `AGENTS.md` - Project guidelines

# Bare Block Return Value Fix

## Problem Statement

Test 5 in `context_semantics.t` fails: `sub foo { { 99 } }` should return 99 but returns undef.

When attempting to fix this by adding `blockIsSubroutine` to the bare block handling in `EmitBlock.java`, Test2 breaks with "context() was called to retrieve an existing context" errors.

## Root Cause Analysis

### Discovery

Through debugging, we found THREE separate issues:

1. **Indirect object syntax bug** - `(release $ctx, "V")` was dropping the "V" (FIXED)
2. **Hash literal detection bug** - `{ %{$_} }` was parsed as bare block instead of hash literal (FIXED)
3. **Bare block return value** - `sub foo { { 99 } }` returns undef (FIXED)

### The Indirect Object Syntax Bug (FIXED)

Perl's indirect object syntax `release $ctx, expr` should:
1. Call `$ctx->release()`
2. Evaluate `expr`
3. Return a list containing both results

**Fix applied:** Modified `SubroutineParser.java` to only consume the object argument when detecting indirect object syntax, leaving trailing arguments for the outer list context.

### The Hash Literal Detection Bug (FIXED)

The parser's `isHashLiteral()` function in `StatementResolver.java` was incorrectly treating `{ %{$_} }` as a bare block because it didn't find a `=>` fat comma indicator.

**Fix applied:** Added `firstTokenIsSigil` check - if first token is `%` or `@` and no block indicators are found, treat as hash literal. This correctly handles patterns like `map {{ %{$_} }} @data`.

### The Bare Block Return Value Issue (FIXED)

Adding `blockIsSubroutine` to the EmitBlock.java condition was the correct approach, but it only worked after fixing the hash literal detection bug above.

**Why the fix now works:**
- `sub foo { { 99 } }` - inner `{ 99 }` is correctly identified as bare block, returns value
- `map {{ %{$_} }} @data` - inner `{ %{$_} }` is correctly identified as hash literal, not affected

## Fix Strategy

### Phase 1: Fix Indirect Object Syntax ✓ COMPLETED

**Files modified:**
- `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java` - Fixed to only consume the object, not trailing args

**Test added:**
- Added test cases to `src/test/resources/unit/indirect_object_syntax.t`

### Phase 2: Fix Hash Literal Detection ✓ COMPLETED

**Files modified:**
- `src/main/java/org/perlonjava/frontend/parser/StatementResolver.java` - Added `firstTokenIsSigil` check in `isHashLiteral()` function

### Phase 3: Fix Bare Block Return Value ✓ COMPLETED

**Files modified:**
- `src/main/java/org/perlonjava/backend/jvm/EmitBlock.java` - Added `blockIsSubroutine` to the condition at line 271

## Verification

### All Tests Pass ✓

```bash
# Bare block return value
./jperl -e 'sub foo { { 99 } } print foo(), "\n"'
# Output: 99 ✓

# Hash literal in map (Test2 pattern)
./jperl -e 'my @data = ({a=>1}, {b=>2}); my @result = map {{ %{$_} }} @data; print scalar(@result), " refs\n"'
# Output: 2 refs ✓

# Test2 based tests
./jperl src/test/resources/unit/transliterate.t
# All tests pass without "context() was called" errors ✓

# Context semantics unit test
./jperl src/test/resources/unit/context_semantics.t
# All 12 tests pass ✓
```

## Current State - ALL FIXED ✓

- **Branch:** `feature/cpan-client-phase11`
- **Indirect object syntax:** ✓ FIXED
- **Hash literal detection:** ✓ FIXED
- **File-level bare blocks:** ✓ FIXED
- **SCALAR/LIST context bare blocks:** ✓ FIXED
- **Subroutine bare blocks:** ✓ FIXED

## Files Modified

1. `src/main/java/org/perlonjava/frontend/parser/Parser.java` - Added `isFileLevelBlock` annotation
2. `src/main/java/org/perlonjava/backend/jvm/EmitBlock.java` - Handle file-level and subroutine bare blocks
3. `src/main/java/org/perlonjava/backend/jvm/EmitStatement.java` - Register spilling for SCALAR/LIST bare blocks
4. `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java` - Fixed indirect object syntax
5. `src/main/java/org/perlonjava/frontend/parser/StatementResolver.java` - Fixed hash literal detection
6. `src/test/resources/unit/indirect_object_syntax.t` - Added tests for comma-separated args
7. `src/test/resources/unit/bare_block_return.t` - Comprehensive test file
8. `src/test/resources/unit/context_semantics.t` - Removed TODO markers (all tests pass)

## Completed Steps

1. ~~Create unit test for indirect object syntax~~ ✓ DONE
2. ~~Find and fix the parser bug for `method OBJECT, ARGS`~~ ✓ DONE
3. ~~Fix hash literal detection for `{ %{$_} }` patterns~~ ✓ DONE
4. ~~Add `blockIsSubroutine` to EmitBlock.java condition~~ ✓ DONE
5. ~~Update context_semantics.t to remove TODO markers~~ ✓ DONE

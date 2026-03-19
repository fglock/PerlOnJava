# Bare Block Return Value Fix

## Problem Statement

Test 5 in `context_semantics.t` fails: `sub foo { { 99 } }` should return 99 but returns undef.

When attempting to fix this by adding `blockIsSubroutine` to the bare block handling in `EmitBlock.java`, Test2 breaks with "context() was called to retrieve an existing context" errors.

## Root Cause Analysis

### Discovery

Through debugging, we found TWO separate issues:

1. **Indirect object syntax bug** - `(release $ctx, "V")` was dropping the "V" (FIXED)
2. **Bare block return value** - `sub foo { { 99 } }` returns undef (STILL BLOCKED)

### The Indirect Object Syntax Bug (FIXED)

Perl's indirect object syntax `release $ctx, expr` should:
1. Call `$ctx->release()`
2. Evaluate `expr`
3. Return a list containing both results

**Fix applied:** Modified `SubroutineParser.java` to only consume the object argument when detecting indirect object syntax, leaving trailing arguments for the outer list context.

### The Bare Block Return Value Issue

Adding `blockIsSubroutine` to the EmitBlock.java condition breaks Test2 because it affects ALL bare blocks inside subroutines, not just the specific `sub { { 99 } }` pattern.

**Affected Test2 code:**
- `Test2/Event.pm:145` - `[map {{ %{$_} }} @{$self->{+AMNESTY}}]`
- `Test2/Event/V2.pm:70` - Similar pattern

These patterns use `{{ ... }}` where the outer `{}` is a bare block that returns the inner hash ref. When visited in SCALAR context instead of RUNTIME context, it affects Test2's context depth tracking.

**Why the simple fix doesn't work:**

The `blockIsSubroutine` annotation is set on the outer subroutine block, but the condition affects the INNER For3Node (bare block). This means:
- `sub foo { { 99 } }` - the inner `{ 99 }` gets SCALAR context (desired)
- `map { { hash } } @list` - the inner `{ hash }` ALSO gets SCALAR context (breaks Test2)

## Fix Strategy

### Phase 1: Fix Indirect Object Syntax ✓ COMPLETED

**Files modified:**
- `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java` - Fixed to only consume the object, not trailing args

**Test added:**
- Added test cases to `src/test/resources/unit/indirect_object_syntax.t`

### Phase 2: Fix Bare Block Return Value (NEEDS DIFFERENT APPROACH)

The simple approach of adding `blockIsSubroutine` to the condition doesn't work. A different approach is needed:

**Option A: Mark the specific For3Node**
Set an annotation on the bare block itself (the For3Node) when it's the direct last element of a subroutine that needs to return a value. This would require modifying SubroutineParser or EmitterMethodCreator.

**Option B: Check for specific patterns**
Instead of just checking `blockIsSubroutine`, also verify that:
- The bare block is the ONLY element in the block
- Or the bare block doesn't contain a hash/array constructor as its value

**Option C: Fix at a different level**
Handle this in EmitSubroutine when generating the return value, rather than in EmitBlock.

## Verification

### Indirect Object Syntax (PASSES)
```perl
package Ctx;
sub new { bless {}, shift }
sub release { return "R" }

package main;
my $ctx = Ctx->new;
my @result = (release $ctx, "V");
# Result: R V  (two elements) ✓
```

### Bare Block Return Value (STILL FAILS)
```bash
./jperl -e 'sub foo { { 99 } } print foo(), "\n"'
# Expected: 99
# Actual: (empty/undef)
```

### Test2 (MUST NOT BREAK)
```bash
./jperl src/test/resources/unit/transliterate.t
# Must pass without "context() was called" errors
```

## Current State

- **Branch:** `feature/cpan-client-phase11`
- **Indirect object syntax:** ✓ FIXED
- **File-level bare blocks:** ✓ FIXED (tests 12-20 in bare_block_return.t pass)
- **SCALAR/LIST context bare blocks:** ✓ FIXED (tests 3-11 pass)
- **Subroutine bare blocks:** BLOCKED - needs different approach

## Files Modified

1. `src/main/java/org/perlonjava/frontend/parser/Parser.java` - Added `isFileLevelBlock` annotation
2. `src/main/java/org/perlonjava/backend/jvm/EmitBlock.java` - Handle file-level bare blocks
3. `src/main/java/org/perlonjava/backend/jvm/EmitStatement.java` - Register spilling for SCALAR/LIST bare blocks
4. `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java` - Fixed indirect object syntax
5. `src/test/resources/unit/indirect_object_syntax.t` - Added tests for comma-separated args
6. `src/test/resources/unit/bare_block_return.t` - Comprehensive test file

## Next Steps

1. ~~Create unit test for indirect object syntax~~ ✓ DONE
2. ~~Find and fix the parser bug for `method OBJECT, ARGS`~~ ✓ DONE
3. Investigate Option A, B, or C for the bare block return value fix
4. Update context_semantics.t test 5 once fixed

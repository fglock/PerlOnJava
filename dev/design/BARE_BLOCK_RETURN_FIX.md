# Bare Block Return Value Fix

## Problem Statement

Test 5 in `context_semantics.t` fails: `sub foo { { 99 } }` should return 99 but returns undef.

When attempting to fix this by adding `blockIsSubroutine` to the bare block handling in `EmitBlock.java`, Test2 breaks with "context() was called to retrieve an existing context" errors.

## Root Cause Analysis

### Discovery

Through debugging, we found TWO issues:

1. **Bare block return value** - The original issue where `sub foo { { 99 } }` returns undef
2. **Indirect object syntax bug** - A separate bug that causes Test2 to break

### The Indirect Object Syntax Bug

Perl's indirect object syntax `release $ctx, expr` should:
1. Call `$ctx->release()`
2. Evaluate `expr`
3. Return a list containing both results

**Perl behavior:**
```perl
my @result = (release $ctx, "V");
# Result: R V  (two elements)
```

**PerlOnJava behavior:**
```perl
my @result = (release $ctx, "V");
# Result: R  (only one element - "V" is lost!)
```

This bug causes Test2's context handling to fail because:
- `release $ctx, $self->cmp_ok(...)` should return the result of `cmp_ok`
- But PerlOnJava drops the second value
- This breaks Test2's expectation of what gets returned

## Fix Strategy

### Phase 1: Fix Indirect Object Syntax (MUST DO FIRST)

The indirect object syntax `method OBJECT, ARGS` is parsed incorrectly. When followed by a comma and additional expressions, the additional expressions should be included in the result list.

**Files to investigate:**
- `src/main/java/org/perlonjava/frontend/parser/` - Parser handling of indirect object calls
- Specifically look for how `method OBJECT LIST` is parsed

**Test case:**
```perl
package Ctx;
sub new { bless {}, shift }
sub release { return "R" }

package main;
my $ctx = Ctx->new;
my @result = (release $ctx, "V");
print "@result\n";  # Should print: R V
```

### Phase 2: Fix Bare Block Return Value

Once the indirect object syntax is fixed, re-enable the `blockIsSubroutine` check in `EmitBlock.java`:

```java
} else if (emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME
        && (node.getBooleanAnnotation("isFileLevelBlock") || node.getBooleanAnnotation("blockIsSubroutine"))
        && element instanceof For3Node for3
        && for3.isSimpleBlock
        && for3.labelName == null) {
    element.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
}
```

## Verification

**IMPORTANT: All tests must pass in BOTH JVM backend and interpreter mode.**

### JVM Backend Tests
1. Run `./jperl -e 'sub foo { { 99 } } print foo(), "\n"'` - should print 99
2. Run `make` - all unit tests should pass
3. Run `./jperl src/test/resources/unit/context_semantics.t` - test 5 should pass
4. Run `./jperl src/test/resources/unit/transliterate.t` - should pass (no Test2 errors)

### Interpreter Mode Tests
1. Run with interpreter fallback enabled to verify bytecode interpreter also works
2. Use `JPERL_SHOW_FALLBACK=1` to detect when interpreter is used
3. For interpreter changes, test with both backends:
   ```bash
   ./jperl -e 'code'           # JVM backend
   ./jperl --int -e 'code'     # Interpreter (if available)
   ```

### Indirect Object Syntax Test
```perl
# This must work identically in both backends:
package Ctx;
sub new { bless {}, shift }
sub release { return "R" }

package main;
my $ctx = Ctx->new;
my @result = (release $ctx, "V");
print "@result\n";  # Must print: R V
```

## Current State

- **Branch:** `feature/cpan-client-phase11`
- **File-level bare blocks:** FIXED (tests 12-20 in bare_block_return.t pass)
- **SCALAR/LIST context bare blocks:** FIXED (tests 3-11 pass)
- **Subroutine bare blocks:** BLOCKED by indirect object syntax bug

## Files Modified (so far)

1. `src/main/java/org/perlonjava/frontend/parser/Parser.java` - Added `isFileLevelBlock` annotation
2. `src/main/java/org/perlonjava/backend/jvm/EmitBlock.java` - Handle file-level bare blocks
3. `src/main/java/org/perlonjava/backend/jvm/EmitStatement.java` - Register spilling for SCALAR/LIST bare blocks
4. `src/test/resources/unit/bare_block_return.t` - Comprehensive test file

## Next Steps

1. Create unit test for indirect object syntax
2. Find and fix the parser bug for `method OBJECT, ARGS` 
3. Re-enable `blockIsSubroutine` in EmitBlock.java
4. Verify all tests pass
5. Update context_semantics.t to remove TODO from test 5

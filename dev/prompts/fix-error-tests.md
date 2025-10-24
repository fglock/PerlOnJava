# Fix Tests with ! 0/0 ok Error Status

## Overview
123 tests fail with `! 0/0 ok` status, meaning they have compilation or early runtime errors.

## Error Categories

### 1. Missing XS Modules (~20-30 tests)
**Error:** `Can't locate XS/APItest.pm in @INC`

**Examples:**
- bigmem/stack.t
- bigmem/stack_over.t

**Root Cause:** Tests require C extension modules (XS) not available in PerlOnJava

**Solution:** Expected failures - mark as skipped or document as known limitation

**Priority:** Low - XS modules require native code integration

---

### 2. ASM Bytecode Generation Errors (~10-20 tests)
**Error:** `java.lang.NegativeArraySizeException: -1` in `org.objectweb.asm.Frame.merge`

**Examples:**
- io/pipe.t
- io/eintr_print.t

**Root Cause:** Complex code patterns cause ASM library to fail during bytecode generation

**Stack Trace:**
```
at org.objectweb.asm.Frame.merge(Frame.java:1233)
at org.objectweb.asm.MethodWriter.computeAllFrames(MethodWriter.java:1612)
at org.perlonjava.codegen.EmitterMethodCreator.getBytecode(EmitterMethodCreator.java:284)
```

**Related Commits:**
- 8ad3dda0 - Disable automatic large block refactoring to prevent ASM errors
- f0e44d34 - Revert "Disable automatic large block refactoring..."

**Solution Options:**
1. Improve control flow detection to avoid problematic patterns
2. Use COMPUTE_MAXS instead of COMPUTE_FRAMES in ASM
3. Break up complex methods more aggressively
4. Add try-catch around ASM generation with fallback

**Priority:** High - Compiler crash affecting multiple tests

---

### 3. "Reference found where even-sized list expected" (~30-40 tests)
**Error:** `Reference found where even-sized list expected`

**Examples:**
- class/destruct.t - "Bail out! DestructionNotify does not work"
- op/aassign.t - "Experimental aliasing via reference not enabled"
- op/bop.t

**Root Cause:** Parser/compiler doesn't handle certain reference contexts correctly

**Related Features:**
- Aliasing via reference (experimental feature)
- Hash/list context detection
- Reference flattening

**Solution:** Improve context detection in assignment operations

**Priority:** Medium - Parser/compiler improvement needed

---

### 4. Bareword Errors (~10-20 tests)
**Error:** `Bareword "MCTest::Base::" not allowed while "strict subs" in use`

**Examples:**
- mro/method_caching.t
- mro/method_caching_utf8.t

**Root Cause:** Package name parsing in specific contexts

**Stack Trace:**
```
at org.perlonjava.codegen.EmitLiteral.emitIdentifier at EmitLiteral.java line 481
```

**Solution:** Fix bareword handling for package names ending with `::`

**Priority:** Medium - Parser issue

---

### 5. Missing Features (~30-40 tests)
**Examples:**
- class/destruct.t - "DestructionNotify does not work"
- Various tests with unimplemented Perl features

**Root Cause:** Features not yet implemented in PerlOnJava

**Solution:** Document as known limitations, implement incrementally

**Priority:** Low - Known limitations

---

## Recommended Approach

### Phase 1: Quick Wins (Low-hanging fruit)
1. Document XS module tests as expected failures
2. Fix bareword package name parsing (EmitLiteral.java)
3. Add better error messages for unimplemented features

### Phase 2: Medium Priority
1. Improve "Reference found" error handling
2. Better context detection in assignments
3. Add experimental feature flags

### Phase 3: High Priority (Compiler Stability)
1. Fix ASM NegativeArraySizeException
   - Options: COMPUTE_MAXS, better control flow, method splitting
2. Add regression tests for fixed issues

## Testing Strategy

```bash
# Test specific error category
cd t
for test in io/pipe.t io/eintr_print.t; do
    echo "=== $test ==="
    timeout 5 ../jperl $test 2>&1 | head -10
done

# Count errors by category
grep "! 0/0 ok" logs/test_20251024_124400 | wc -l  # Total: 123
```

## Success Metrics

- **Quick wins:** Fix 20-30 tests (XS + bareword)
- **Medium term:** Fix 30-40 tests (reference errors)
- **Long term:** Fix 10-20 tests (ASM errors)
- **Target:** Reduce ! 0/0 errors from 123 to <50

## Notes

- ASM errors are the most critical (compiler crashes)
- XS module tests are expected failures
- Many tests fail early, so fixing compilation issues will reveal more runtime issues
- Use `--parse` flag to debug parser issues
- Use `JPERL_LARGECODE=refactor` for large code blocks

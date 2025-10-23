# Plan to Fix 0/0 Tests (Tests That Don't Run)

**Date:** 2025-10-22  
**Total Affected:** 121 tests that show `0/0` (tests planned but never executed)  
**Source:** logs/test_20251022_163500

## Problem

Tests showing `0/0 ok` in the logs indicate that the test file failed to load or compile, preventing any tests from running. These are **critical blockers** because they represent complete test file failures, not just individual test failures.

## Error Categories (Prioritized by Impact)

### Category 1: Parser Errors (14 tests) - **HIGH PRIORITY**
These are parser bugs that prevent code compilation. Fixing these will unblock test execution.

**Tests Affected:**
```
op/const-optree.t      - Expected ( but got '
op/defer.t             - Expected } but got ;
op/lvref.t             - Expected { but got ;
op/method.t            - Unexpected infix: Pack
op/overload_integer.t  - Unexpected infix: '
op/ref.t               - Unexpected infix: "
op/signatures.t        - Unexpected runtime error
op/smartmatch.t        - Unexpected infix: ~~
op/taint.t             - Expected } but got '
op/tie_fetch_count.t   - Unexpected infix: ~~
re/overload.t          - Unexpected infix: qr
re/pat_re_eval.t       - Unexpected infix: '
re/rxcode.t            - Expected } but got EOF
uni/class.t            - Expected { but got }
```

**Root Causes:**
1. **`~~` smartmatch operator** - Not implemented (2 tests)
2. **`overload` with bareword constants** - Parser doesn't handle `use overload 'qr' => ...`
3. **String parsing edge cases** - Quotes and delimiters in specific contexts
4. **Context-sensitive parsing** - `method` keyword, indirect object syntax
5. **EOF handling** - Unexpected EOF in rxcode.t

**Fix Strategy:**
- Add `~~` operator support to lexer and parser (Category 1a)
- Fix `overload` pragma bareword handling (Category 1b)
- Fix string/quote parsing edge cases (Category 1c)
- Investigate `defer` block syntax issues

**Estimated Effort:** 6-8 hours  
**Impact:** Unblocks 14 test files

---

### Category 2: Missing Test File (10 tests) - **NOT A QUICK WIN** ❌
All 10 tests fail because they can't find `lib/unicore/TestProp.pl`.

**Tests Affected:**
```
re/uniprops01.t through re/uniprops10.t
```

**Error:**
```
Could not run lib/unicore/TestProp.pl: No such file or directory
```

**Status: ATTEMPTED 2025-10-22** ✅ File imported, ❌ Tests still blocked

**What We Did:**
1. Generated `TestProp.pl` using `cd perl5/lib/unicore && perl mktables -maketest`
2. Added to sync config and imported (12MB file!)
3. Tested: File found successfully ✅

**New Error Found:**
```
Method too large: org/perlonjava/anon51.apply method exceeds 64KB limit
```

**Root Cause:**
TestProp.pl contains very large subroutines that exceed JVM's 64KB method bytecode limit.
This is **Category 9: Bytecode Generation Failed** - not a file missing issue!

**Fix Strategy:**
~~Copy file (DONE)~~ → Now need to fix bytecode generation:
1. Implement automatic method splitting for large subs
2. Break down large methods into smaller helper methods
3. Or use bytecode optimization to reduce size

**Estimated Effort:** ❌ NOT 30 minutes - now 6-8 hours (Category 9 complexity)  
**Impact:** File imported but tests still blocked by JVM limits  
**Status:** Moved to Category 9 - Bytecode Generation Failed

**Lesson Learned:** "Quick win" revealed deeper architectural issue!

---

### Category 3: Bareword Not Allowed (7 tests) - **MEDIUM PRIORITY**
Tests fail with "Bareword X not allowed while 'strict subs' in use" for package names.

**Tests Affected:**
```
io/socketpair.t          - PF_UNIX
mro/method_caching.t     - MCTest::Base::
mro/method_caching_utf8.t - MC텟ᵀ::Bࡎᶓ::
mro/package_aliases.t    - New::
mro/package_aliases_utf8.t - Ｎeẁ::
op/for-many.t            - dummy
run/todo.t               - defer
```

**Root Cause:**
The parser is treating package-qualified barewords (`Pkg::`) as barewords subject to strict subs, when they should be valid package name literals.

**Example:**
```perl
# Should work but fails:
my $ref = \%MCTest::Base::;  # Reference to package symbol table
```

**Fix Strategy:**
1. Parser should recognize `Package::` pattern as valid even under strict subs
2. Special handling for package symbol table references `\%Pkg::`
3. May need to handle UTF-8 package names specially

**Estimated Effort:** 3-4 hours  
**Impact:** Unblocks 7 test files

---

### Category 4: Missing `./test.pl` or `t/test.pl` (8 tests) - **MEDIUM PRIORITY**
Tests fail to load the common test library from relative paths.

**Tests Affected:**
```
op/chdir.t               - Can't locate ./test.pl
porting/args_assert.t    - Can't locate ./test.pl
porting/checkcase.t      - Can't locate ./test.pl
porting/copyright.t      - Can't locate ./test.pl
porting/FindExt.t        - Can't locate ./test.pl
porting/extrefs.t        - Can't locate ./test.pl
porting/corelist.t       - Can't locate ./t/test.pl
porting/diag.t           - Can't locate ./t/test.pl
```

**Root Cause:**
`require "./test.pl"` doesn't work because:
1. Current directory `.` may not be in `@INC`
2. `chdir` changes working directory, breaking relative paths
3. Tests use `do "./test.pl"` or `require "./test.pl"`

**Fix Strategy:**
1. Ensure `.` is in `@INC` when needed
2. Fix `chdir` to not break test discovery
3. May need to handle `do "./file"` specially
4. Check if `$FindBin::Bin` or similar is set correctly

**Estimated Effort:** 3-4 hours  
**Impact:** Unblocks 8 test files (mostly porting tests)

---

### Category 5: Variable Masking Warnings (6 tests) - **LOW PRIORITY**
Tests fail because warnings are treated as errors, or tests themselves fail due to warning behavior.

**Tests Affected:**
```
op/array.t      - "my" variable @bee masks earlier
op/eval.t       - "our" variable $x masks earlier
op/rt119311.t   - "my" variable $o masks earlier (3x)
op/universal.t  - "my" variable $err masks earlier
op/write.t      - "my" variable $fox masks earlier
win32/fs.t      - "my" variable $fh masks earlier
```

**Root Cause:**
Our warning system is detecting variable redeclarations that mask earlier ones, but:
1. May be too strict (Perl allows some cases)
2. May not respect scope properly
3. May not handle our/my interaction correctly

**Fix Strategy:**
1. Review warning logic in `DeclareVariable.java` or similar
2. Ensure we only warn when truly problematic
3. Check scope boundaries for masking detection
4. May need to suppress warnings in specific contexts

**Estimated Effort:** 2-3 hours  
**Impact:** Unblocks 6 test files (but tests may pass after warning fix)

---

### Category 6: Argument Count Errors (5 tests) - **MEDIUM PRIORITY**
Prototype validation is too strict or incorrect.

**Tests Affected:**
```
op/getppid.t   - Not enough arguments for pipe
op/sort.t      - Not enough arguments for Backwards_stacked
op/sub_lval.t  - Not enough arguments for Internals::SvREADONLY
op/substr.t    - Too many arguments for substr
win32/stat.t   - Not enough arguments for pipe
```

**Root Cause:**
Prototype checking in `PrototypeArgs.java` is:
1. Too strict (counting optional args incorrectly)
2. Not handling special cases (like pipe's FILEHANDLE pairs)
3. Possibly not checking actual vs expected correctly

**Examples:**
```perl
pipe($r, $w) or die;  # Should take 2 args but we think it needs more?
substr($str, 1, 1, o=>);  # "o=>" is syntax error, not too many args
```

**Fix Strategy:**
1. Review `PrototypeArgs.java` validation logic
2. Check `pipe` prototype - should be `(**)` (two filehandles)
3. Fix substr case (may be parser issue, not prototype issue)
4. Ensure optional arguments (`$`, `@`, etc.) handled correctly

**Estimated Effort:** 3-4 hours  
**Impact:** Unblocks 5 test files

---

### Category 7: Syntax Errors (4 tests) - **HIGH PRIORITY**
Parser rejects valid Perl syntax.

**Tests Affected:**
```
op/goto.t        - syntax error at { goto };
op/gv.t          - syntax error at use constant x =>
op/magic-27839.t - syntax error at %+;
op/state.t       - syntax error at $spam) {
```

**Root Causes:**
1. **`{ goto }`** - Empty target for goto (should be syntax error in Perl too?)
2. **`use constant x =>`** - Bareword constant definition
3. **`%+`** - Special regex capture hash not recognized as valid
4. **Context parsing issues** - Statement boundaries, punctuators

**Fix Strategy:**
1. Check if `{ goto }` is actually valid Perl (may be intentional error test)
2. Fix `use constant` bareword handling (related to Category 3)
3. Add `%+` and `%-` as special variables in lexer
4. Review parser state machine for context issues

**Estimated Effort:** 4-5 hours  
**Impact:** Unblocks 4 test files

---

### Category 8: Missing Core Modules (7 categories, ~25 tests) - **VARIED PRIORITY**

**8a. Missing TestInit.pm (4 tests) - MEDIUM**
```
porting/globvar.t, makerel.t, readme.t, utils.t
```
Fix: Implement or stub TestInit.pm

**8b. Missing B.pm (3 tests) - LOW**
```
op/svflags.t, perf/opcount.t, perf/optree.t
```
Fix: Implement B.pm (Perl bytecode inspection) or mark as TODO

**8c. Missing threads (4 tests) - LOW**
```
op/signame_canonical.t, study.t, re/pat_psycho.t, test_pl/examples.t
```
Fix: Implement threads stub or skip these tests

**8d. Missing NEXT.pm (2 tests) - LOW**
```
mro/next_NEXT.t, mro/next_NEXT_utf8.t
```
Fix: Implement NEXT.pm (method resolution)

**8e. Missing XS/APItest.pm (2 tests) - LOW**
```
bigmem/stack.t, stack_over.t
```
Fix: Skip or stub (XS module for C API testing)

**8f. Missing Maintainers.pm, ExtUtils/Manifest.pm, etc. (10 tests) - LOW**
Fix: Stub porting-specific modules

**Estimated Effort:** 8-12 hours total  
**Impact:** Unblocks ~25 test files (but many are porting tests)

---

### Category 9: Bytecode Generation Failed (12 tests total) - **HIGH PRIORITY**
ASM bytecode generation fails with code -1 or "Method too large" error.

**Tests Affected:**
```
io/eintr_print.t        - ASM bytecode generation failed: -1
io/pipe.t               - ASM bytecode generation failed: -1
re/uniprops01.t         - Method too large (moved from Category 2!)
re/uniprops02.t         - Method too large (moved from Category 2!)
re/uniprops03.t         - Method too large (moved from Category 2!)
re/uniprops04.t         - Method too large (moved from Category 2!)
re/uniprops05.t         - Method too large (moved from Category 2!)
re/uniprops06.t         - Method too large (moved from Category 2!)
re/uniprops07.t         - Method too large (moved from Category 2!)
re/uniprops08.t         - Method too large (moved from Category 2!)
re/uniprops09.t         - Method too large (moved from Category 2!)
re/uniprops10.t         - Method too large (moved from Category 2!)
```

**Errors:**
```
ASM bytecode generation failed: -1
Consider simplifying the subroutine or breaking it into smaller parts.

-- OR --

Method too large: org/perlonjava/anon51.apply exceeds 64KB limit
```

**Root Cause:**
Method bytecode size exceeds JVM limits (64KB per method) or complexity exceeds JVM stack limits.

**Discovery (2025-10-22):**
What looked like a "quick win" (copying TestProp.pl) revealed that the 12MB generated file 
contains massive subroutines that hit JVM bytecode limits. This is a fundamental architectural 
issue affecting any large Perl code.

**Fix Strategy:**
1. Implement automatic method splitting for large subs
   - Detect when method size approaches 64KB
   - Break into multiple helper methods automatically
   - Chain helper methods together
2. Improve bytecode efficiency to reduce size
   - Optimize instruction selection
   - Reduce redundant operations
3. Check for pathological cases (infinite loops in generation)

**Implementation Approach:**
```java
// In EmitterContext.java or similar
if (methodBytecodeSize > 60000) {  // Approaching 64KB limit
    splitMethodIntoChunks(currentMethod);
}
```

**Estimated Effort:** 8-12 hours (increased from 6-8 due to complexity)  
**Impact:** Unblocks 12 test files (was 2, now includes 10 uniprops tests)
**Importance:** CRITICAL - Affects any large Perl program, not just tests!

---

### Category 10: Missing Operators (3 tests) - **MEDIUM PRIORITY**

**10a. Missing `lock` operator (1 test)**
```
op/lock.t - Operator "lock" doesn't have a defined JVM descriptor
```
Fix: Implement or stub `lock` for threading

**10b. Missing `sysseek` operator (1 test)**
```
op/sysio.t - Operator "sysseek" doesn't have a defined JVM descriptor
```
Fix: Implement `sysseek` in RuntimeIO.java

**10c. Missing `binmode` method (1 test)**
```
class/utf8.t - Can't locate method "binmode" via package "STDOUT"
```
Fix: Implement binmode on filehandle objects

**Estimated Effort:** 3-4 hours  
**Impact:** Unblocks 3 test files

---

### Category 11: Regex Compilation Errors (3 tests) - **MEDIUM PRIORITY**

**Tests Affected:**
```
op/numconvert.t      - Illegal/unsupported escape sequence
re/anyof.t           - Illegal repetition
re/regexp_unicode_prop.t - Invalid Unicode property: IsOverflow
```

**Fix Strategy:**
1. Add missing regex escape sequences
2. Fix repetition quantifier parsing
3. Implement missing Unicode properties or stub them

**Estimated Effort:** 4-5 hours  
**Impact:** Unblocks 3 test files

---

### Category 12: Loop Control Errors (3 tests) - **MEDIUM PRIORITY**

**Tests Affected:**
```
op/loopctl.t - Can't "last" outside a loop block
op/sselect.t - Can't "last" outside a loop block  
op/try.t     - Can't "last" outside a loop block
```

**Root Cause:**
Parser is rejecting `last LABEL` where the label is valid but outside current immediate loop.

**Fix Strategy:**
1. Improve label tracking across blocks
2. Allow `last LABEL` to target enclosing labeled blocks
3. Handle `try/catch` blocks as potential targets

**Estimated Effort:** 3-4 hours  
**Impact:** Unblocks 3 test files

---

### Category 13: Missing CORE Keywords (2 tests) - **LOW PRIORITY**

**Tests Affected:**
```
op/coreamp.t - CORE::given is not a keyword
op/switch.t  - CORE::given is not a keyword
```

**Root Cause:**
`given` keyword not implemented (switch/when/given feature).

**Fix Strategy:**
1. Implement `given`/`when` syntax (complex!)
2. Or stub CORE::given for testing purposes
3. May need full smartmatch (`~~`) implementation

**Estimated Effort:** 8-10 hours (if full implementation)  
**Impact:** Unblocks 2 test files

---

### Category 14: Miscellaneous (11 tests) - **VARIED PRIORITY**

**Includes:**
- `op/aassign.t`, `op/each.t` - Experimental aliasing not enabled
- `op/groups.t`, `op/incfilter.t` - Can't load Java modules
- `op/goto-sub.t` - goto sub {} syntax
- `op/leaky-magic.t` - VERSION check
- `op/lex.t`, `op/local.t` - delete with dynamic patterns
- `porting/header_parser.t` - Here-doc indentation
- Various other edge cases

**Estimated Effort:** 10-15 hours  
**Impact:** Unblocks 11 test files

---

## Execution Plan (Prioritized)

### Phase 1: Quick Wins (6 tests, ~3 hours) - ❌ TestProp.pl was NOT a quick win!
1. ~~**Copy lib/unicore/TestProp.pl**~~ → ❌ Moved to Category 9 (bytecode limits)
2. **Fix variable masking warnings** → +6 tests (2-3 hours)

**Expected:** 6 tests unblocked (not 16 - TestProp.pl hit JVM limits!)

---

### Phase 2: Parser Fixes (26 tests, ~12 hours)
3. **Add `~~` smartmatch operator** → +2 tests (2 hours)
4. **Fix bareword package names** → +7 tests (3-4 hours)
5. **Fix `overload` bareword handling** → +2 tests (2 hours)
6. **Fix string/quote parsing edge cases** → +5 tests (3 hours)
7. **Add `%+` special variable** → +1 test (1 hour)

**Expected:** 17 tests unblocked

---

### Phase 3: Path & Require Fixes (8 tests, ~4 hours)
8. **Fix `require "./test.pl"` paths** → +8 tests (3-4 hours)

**Expected:** 8 tests unblocked

---

### Phase 4: Operators & I/O (8 tests, ~8 hours)
9. **Implement missing operators** (lock, sysseek, binmode) → +3 tests (3-4 hours)
10. **Fix argument count validation** → +5 tests (3-4 hours)

**Expected:** 8 tests unblocked

---

### Phase 5: Advanced Features (6 tests, ~8 hours)
11. **Fix loop control & labels** → +3 tests (3-4 hours)
12. **Fix regex compilation errors** → +3 tests (4-5 hours)

**Expected:** 6 tests unblocked

---

### Phase 6: Complex Issues (Optional, ~30 hours)
13. **Fix bytecode generation limits** → +12 tests (8-12 hours) ← Grew from 2 to 12!
    - Includes io/eintr_print.t, io/pipe.t
    - Plus all 10 re/uniprops*.t tests
    - CRITICAL: Affects any large Perl code
14. **Implement `given/when`** → +2 tests (8-10 hours)
15. **Stub missing modules** → +10 tests (8-12 hours)
16. **Miscellaneous fixes** → +11 tests (10-15 hours)

**Expected:** 35 tests unblocked (was 25, now includes 10 uniprops tests)

---

## Summary

**Total Tests:** 121 tests that don't run (0/0)

**Phases 1-2 (Quick & Parser):** 23 tests (~15 hours) ← Reduced! TestProp not quick  
**Phases 3-4 (Paths & Ops):** 16 tests (~12 hours)  
**Phase 5 (Advanced):** 6 tests (~8 hours)  
**Phase 6 (Complex):** 35 tests (~30 hours) ← Increased! 10 uniprops moved here  

**Realistic Target:** Phases 1-5 = 45 tests (~35 hours, ~1 week)  
**Stretch Goal:** All phases = 80 tests (~65 hours, ~2 weeks)

**Key Discovery (2025-10-22):**
TestProp.pl "quick win" revealed JVM bytecode limit issue affecting 12 tests total.
This is a CRITICAL architectural issue for any large Perl code.

---

## Risk Assessment

**Low Risk (Do First):**
- Category 2: Copy test file
- Category 5: Warning adjustments
- Category 10: Missing operators

**Medium Risk:**
- Category 3: Bareword package names
- Category 4: Path handling
- Category 6: Argument validation
- Category 11: Regex errors
- Category 12: Loop control

**High Risk (Do Last or Skip):**
- Category 1: Parser errors (complex edge cases)
- Category 8: Bytecode generation limits
- Category 13: `given/when` implementation
- Category 14: Missing core modules (especially threads, B.pm)

---

## Testing Strategy

After each category fix:
```bash
# Run affected tests
for test in $AFFECTED_TESTS; do
    ./jperl t/$test
done

# Compare results
./dev/tools/compare_test_logs.pl logs/before.log logs/after.log

# Check for regressions
make test
```

---

## Notes

- Many of these are **parser bugs** that affect test compilation, not runtime behavior
- Fixing parser issues may unlock other tests beyond the 121
- Some tests (porting/*) are less critical for core functionality
- **Priority**: Fix parser errors first, they have cascading benefits
- **Quick win**: Copy TestProp.pl file for immediate +10 tests

**DO NOT EXECUTE YET** - This is analysis and planning only.


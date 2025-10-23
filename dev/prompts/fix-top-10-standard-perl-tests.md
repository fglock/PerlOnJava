# Plan to Fix Top 10 Incomplete Standard Perl Tests

**Total Blocked Tests: 1,138 tests across 10 files**

## Issue Categories and Action Plan

### Category 1: `s///r` Operator Issues (158 tests blocked)
**Affected Files:**
- re/subst.t (79 tests blocked after test 202)
- re/subst_wamp.t (79 tests blocked after test 202)

**Root Cause:**
The `s///r` (non-destructive substitution) operator has multiple issues:
1. **`!~` operator error** - `s///r` should work with `!~` but currently gives error
2. **COW (Copy-On-Write) constant handling** - Doesn't work with constant strings
3. **Uninitialized warning** - Missing warning when used on undef
4. **Void context warning** - Missing warning when result unused
5. **String squashing** - Doesn't properly handle end-of-string replacements

**Example:**
```perl
# Should work but fails:
"test" !~ s/t/x/r;           # Error: s///r with !~
my $result = "abc" =~ s/b/x/r;  # $result should be "axc"
```

**Fix Strategy:**
1. Check `EmitOperator.java` - how is `s///r` compiled?
2. The `/r` modifier should return modified copy, not modify original
3. Need to handle `!~` operator with `s///r` (currently may be rejected)
4. Add proper warnings for void context and uninitialized values
5. Fix string squashing at end of string

**Implementation:**
- File: `src/main/java/org/perlonjava/operators/RegexOperators.java`
- Look for `replaceRegex` method with `/r` modifier handling
- Ensure it returns a new string, not modifying the original
- Add context checking for void context warnings

**Priority:** HIGH - Blocks 158 tests in 2 files

---

### Category 2: `local` with Declared References (246 tests blocked)
**Affected Files:**
- op/decl-refs.t (246 tests blocked after test 156)

**Root Cause:**
Tests 109-400 fail because `local \$x` isn't implemented:
```perl
use feature 'declared_refs';
my $x = 5;
local \$x = \$y;  # Should localize the reference itself
```

**Problem:**
- `local` with declared references (`local \$x`, `local \@x`, `local \%x`) not implemented
- Need to generate proper "feature disabled" errors when feature not enabled
- Need proper "experimental" warnings when feature is enabled
- The difference: `my \$x` creates an alias, `local \$x` localizes it

**Fix Strategy:**
1. We already implemented `my \$x` (commit 7065c8db)
2. Need to add `local` support for declared references
3. Check `ParseStatement.java` for `local` keyword handling
4. Add declared reference detection in `local` context
5. Implement localization of reference variables (more complex than my)

**Implementation:**
```java
// In StatementParser.java or similar
case "local":
    // Check if next token is backslash (declared ref)
    if (isBackslashDeclaredRef()) {
        return parseLocalDeclaredReference();
    }
    // ... existing local handling
```

**Estimated Complexity:** HIGH - Requires understanding of Perl's local() scope mechanism

**Priority:** HIGH - Blocks 246 tests in 1 file

---

### Category 3: Prototype Reference Creation from Lists (128 tests blocked)
**Affected Files:**
- comp/proto.t (128 tests blocked after test 88)

**Root Cause:**
Error: `TODO - create reference of list not implemented`

This is the same issue we identified in the Pod tests (fix-top-10-pod-tests.md Category 1).

**Problem:**
```perl
sub foo (\@) { ... }
foo(@array);      # Works
foo(1, 2, 3);     # Fails - need to create array ref from list
```

**Fix Strategy:**
See `dev/prompts/fix-top-10-pod-tests.md` Category 1 for detailed fix.
This was likely broken in commit b1fb416e which we reverted.

**⚠️ CRITICAL: This is what broke hash.t today (26,931 test regression)!**

**Need to re-implement properly with EXTREME caution:**
1. In `EmitOperator.java`, handle `\@` and `\%` prototypes
2. When argument is a list (not an array), convert to array/hash reference
3. **MANDATORY SAFEGUARDS:**
   - DO NOT modify existing reference handling - only add list→ref conversion
   - Test with `./jperl t/op/hash.t` after EVERY change
   - Use simplest possible bytecode generation (avoid complex stack manipulation)
   - Verify with `javap -c` that bytecode is clean
   - If ANY VerifyError appears, revert immediately
   - Test incrementally: first handle constant lists, then variables, then expressions
4. **Incremental approach:**
   - Step 1: Handle `sub foo(\@) { }; foo(1,2,3)` - constant list only
   - Step 2: Run full test suite, check for regressions
   - Step 3: Handle variable lists
   - Step 4: Run full test suite again
   - Step 5: Handle complex expressions

**Alternative safer approach:**
Instead of complex bytecode for prototype conversion, consider:
```java
// Instead of inline bytecode generation:
// Generate a helper method call:
RuntimeArray.createArrayRefFromList(arg1, arg2, arg3)
// This avoids complex stack manipulation that causes VerifyError
```

**Priority:** HIGH - Blocks 128 tests, affects both Pod and standard tests
**Risk Level:** ⚠️ EXTREME - Previously caused 26,931 test regression

---

### Category 4: Postfix Dereference (118 tests blocked)
**Affected Files:**
- op/postfixderef.t (118 tests blocked after test 10)

**Root Cause:**
Postfix dereferencing not implemented:
```perl
# Postfix dereference (Perl 5.20+)
$arrayref->@*;     # Same as @$arrayref
$hashref->%*;      # Same as %$hashref
$scalarref->$*;    # Same as $$scalarref
$arrayref->@[0,2]; # Array slice
$hashref->%{qw(a b)}; # Hash slice
```

**Fix Strategy:**
1. Check if parser recognizes `->@*`, `->%*`, `->$*` tokens
2. Parser likely needs to handle these as special operators
3. Transform to traditional dereferencing in AST or emitter
4. May need to add new operator types to `OperatorNode`

**Investigation:**
```bash
./jperl --parse -e '$x->@*'
# Check if it parses correctly
```

**Implementation:**
- File: `ParseInfix.java` or `ParsePrimary.java`
- Look for `->` operator handling
- Add cases for `@*`, `%*`, `$*` after `->`
- Transform to equivalent traditional syntax

**Priority:** HIGH - Blocks 118 tests, modern Perl feature

---

### Category 5: Alpha Assertions in Regex (110 tests blocked)
**Affected Files:**
- re/alpha_assertions.t (110 tests blocked after test 2210)

**Root Cause:**
Regex control verbs not implemented:
- `(*pla:)` - Positive lookahead assertion (new syntax)
- `(*plb:)` - Positive lookbehind assertion (new syntax)
- `(*nla:)` - Negative lookahead
- `(*nlb:)` - Negative lookbehind

**Example:**
```perl
# Old syntax (works):
/(?=pattern)/     # Positive lookahead
/(?<=pattern)/    # Positive lookbehind

# New alpha syntax (not implemented):
/(*pla:pattern)/  # Same as (?=pattern)
/(*plb:pattern)/  # Same as (?<=pattern)
```

**Fix Strategy:**
1. These are just alternative syntax for existing features
2. Transform `(*pla:)` → `(?=)` during regex compilation
3. Transform `(*plb:)` → `(?<=)` during regex compilation
4. File: `RegexParser.java` or `StringParser.java`

**Implementation:**
```java
// In regex pattern preprocessing:
pattern = pattern.replace("(*pla:", "(?=");
pattern = pattern.replace("(*plb:", "(?<=");
pattern = pattern.replace("(*nla:", "(?!");
pattern = pattern.replace("(*nlb:", "(?<!");
```

**Note:** This is a simplification - may need proper parsing for nested cases.

**Priority:** MEDIUM - Blocks 110 tests, but syntax sugar (not new functionality)

---

### Category 6: Parser Error Messages (96 tests blocked)
**Affected Files:**
- comp/parser.t (96 tests blocked after test 96)

**Root Cause:**
Tests expect specific error message format: `Expected (?^:syntax error)`

**Problem:**
Parser error messages don't match Perl's format exactly. Tests use pattern matching:
```perl
like($@, qr/^syntax error/, 'error message format');
```

**Fix Strategy:**
1. These tests validate error messages, not functionality
2. Check what error messages we produce vs. what Perl produces
3. Update error formatting in `PerlCompilerException`
4. May need to adjust multiple error messages throughout parser

**Investigation:**
```perl
# Test case from parser.t around line 59-100
eval "syntax error here";
print $@;  # Check our error vs Perl's error
```

**Priority:** MEDIUM - Tests mostly working (96/192 pass), just error format issues

---

### Category 7: IO::Scalar (95 tests blocked)
**Affected Files:**
- io/scalar.t (95 tests blocked after test 33)

**Root Cause:**
1. **"Modification of a read-only value attempted"** - Writing to read-only scalar
2. **"FETCH only called once"** - Tied scalar not being fetched correctly

**Problem:**
```perl
# Should allow writing to scalar ref
open my $fh, '>', \my $str;
print $fh "data";  # Currently fails if $str is somehow readonly

# Tied scalars should call FETCH multiple times
tie my $x, 'SomeTie';
# Each access should call FETCH
```

**Fix Strategy:**
1. Check `RuntimeIO.java` - how are scalar filehandles implemented?
2. Ensure we can write to scalar references properly
3. Check tied scalar implementation for proper FETCH calls
4. May need to check `RuntimeScalar.java` for read-only flag handling

**Priority:** MEDIUM - Blocks 95 tests, IO edge cases

---

### Category 8: Bless Issues (94 tests blocked)
**Affected Files:**
- op/bless.t (94 tests blocked after test 24)

**Root Cause:**
Various `bless` edge cases not working correctly.

**Investigation Needed:**
```bash
./jperl t/op/bless.t 2>&1 | grep -A 5 "Failed test 13"
# Check what specific bless operation fails
```

**Likely Issues:**
- Blessing into packages with special characters
- Blessing references to special types
- Unblessing or re-blessing objects
- `ref()` not returning correct blessed type

**Priority:** MEDIUM - Blocks 94 tests, OO functionality

---

### Category 9: Magic Variables (94 tests blocked)
**Affected Files:**
- op/magic.t (94 tests blocked after test 114)

**Root Cause:**
Multiple magic variable issues:
1. **`%@` not available** - Error hash for exceptions
2. **Wide character warnings** - Not warning when assigning wide chars to `$0` or similar

**Problems:**
```perl
# Not working:
eval { die "error" };
print $@{message};  # %@ not implemented

# Missing warning:
$0 = "\x{263a}";  # Should warn about wide character
```

**Fix Strategy:**
1. Check `SpecialVariables.java` for `%@` implementation
2. Add wide character warnings for magic variables like `$0`, `$!`, etc.
3. May need to add validation in `RuntimeScalar.java` for magic var assignment

**Priority:** MEDIUM - Blocks 94 tests, but specialized features

---

## Execution Order (Priority)

### Phase 1: Quick Wins (386 tests)
1. **Fix `s///r` operator** (Category 1) → +158 tests
   - Estimated effort: 3-4 hours
   - Clear fix path, regex operators
   
2. **Fix alpha assertion syntax** (Category 5) → +110 tests
   - Estimated effort: 2-3 hours
   - Simple pattern transformation

3. **Fix postfix dereference** (Category 4) → +118 tests
   - Estimated effort: 4-6 hours
   - Parser extension for modern syntax

### Phase 2: Core Features (246 tests)
4. **Implement `local \$x`** (Category 2) → +246 tests
   - Estimated effort: 6-8 hours
   - Complex scope management

### Phase 2.5: ⚠️ HIGH RISK - Attempt Only After Phases 1-3 Complete (128 tests)
5. **Re-implement prototype list refs** (Category 3) → +128 tests
   - ⚠️ **WARNING: This broke hash.t today (-26,931 tests)!**
   - Estimated effort: 8-12 hours (WITH mandatory incremental testing)
   - **Must use new helper method approach, not inline bytecode**
   - Test `hash.t` after EVERY single change
   - Already analyzed in Pod tests plan
   - **Consider skipping if risk too high**

### Phase 3: Edge Cases & Polish (378 tests)
6. **Fix parser error messages** (Category 6) → +96 tests
   - Estimated effort: 3-4 hours
   - Error message formatting

7. **Fix IO::Scalar issues** (Category 7) → +95 tests
   - Estimated effort: 4-5 hours
   - Tied scalars and read-only handling

8. **Fix bless edge cases** (Category 8) → +94 tests
   - Estimated effort: 3-4 hours
   - OO edge cases

9. **Fix magic variables** (Category 9) → +94 tests
   - Estimated effort: 3-4 hours
   - Special variable handling

---

## Expected Results

**After Phase 1 (Safe wins):** ~386 tests unblocked (34% of blocked tests)
**After Phase 2 (Core):** ~632 tests unblocked (56% of blocked tests)  
**After Phase 3 (Polish):** ~1,010 tests unblocked (89% of blocked tests)
**After Phase 2.5 (⚠️ HIGH RISK):** ~1,138 tests unblocked (100% - if successful!)

**Safe recovery (Phases 1-3):** 1,010 tests from 9 files (89%)
**Total with risky Category 3:** 1,138 tests from 10 files (100%)

---

## Risk Assessment

**EXTREME Risk (Do Last!):**
- Category 3 (Prototype refs): ⚠️ **CAUSED 26,931 TEST REGRESSION TODAY!**
  - Commit b1fb416e broke hash.t with JVM VerifyError
  - Requires new architecture: helper methods instead of inline bytecode
  - **Recommendation: Skip in initial phases, tackle after all else works**

**High Risk:**
- Category 2 (`local \$x`): Complex scope and reference handling
- Category 4 (Postfix deref): May require significant parser changes

**Medium Risk:**
- Category 1 (`s///r`): Regex operator modifications
- Category 7 (IO::Scalar): Tied variable interactions
- Category 8 (Bless): OO system edge cases

**Low Risk:**
- Category 5 (Alpha assertions): Syntax transformation only
- Category 6 (Parser errors): Cosmetic message changes
- Category 9 (Magic vars): Localized special variable fixes

---

## Cross-References

**Related Plans:**
- `fix-top-10-pod-tests.md` - Category 3 overlaps with Pod Category 1
- `implement-declared-references.md` - Category 2 extends this work

**Related Files:**
- `dev/design/SUBLANGUAGE_PARSER_ARCHITECTURE.md` - Regex parsing
- `src/main/java/org/perlonjava/operators/RegexOperators.java` - Regex ops
- `src/main/java/org/perlonjava/parser/ParseStatement.java` - `local` keyword

---

## Testing Strategy

After each fix:
1. Run the affected test file: `./jperl t/path/to/test.t`
2. Compare before/after: `./dev/tools/compare_test_logs.pl old.log new.log`
3. Run unit tests: `make test`
4. Check for regressions in related tests

---

## Lessons Learned from Today's hash.t Incident (2025-10-22)

**What Happened:**
- Commit `b1fb416e` implemented "prototype-based reference type conversion (\%, \@)"
- Used complex inline bytecode generation in `EmitOperator.java`
- Caused JVM `VerifyError: Bad local variable type` and `StackMapTable` errors
- Result: **26,931 tests lost** in `t/op/hash.t`
- Required `git bisect` and emergency revert

**Root Causes:**
1. **Complex stack manipulation** - Inline bytecode for prototype conversion
2. **Insufficient testing** - No incremental verification with `hash.t`
3. **No bytecode validation** - Didn't verify with `javap -c`
4. **Too ambitious** - Tried to handle all cases at once

**Prevention Strategy for Category 3:**
1. ✅ **Use helper methods** instead of inline bytecode
2. ✅ **Test hash.t after every change** (our canary test)
3. ✅ **Incremental implementation** (constants → variables → expressions)
4. ✅ **Bytecode verification** with `javap -c` before commit
5. ✅ **Immediate revert** if ANY VerifyError appears
6. ✅ **Do it last** - after all other fixes prove stable

**Key Insight:**
Prototype reference conversion is deceptively complex. The feature LOOKS simple ("just create a ref from a list") but involves:
- Call-site argument transformation
- Stack manipulation during method invocation
- Type checking and validation
- Bytecode generation that JVM verifier can understand

**Recommended Approach:**
If Category 3 is attempted, use this architecture:
```java
// BAD: Inline complex bytecode (what b1fb416e did)
// [complex stack manipulation that causes VerifyError]

// GOOD: Simple helper method call
mv.visitMethodInsn(INVOKESTATIC, 
    "org/perlonjava/runtime/RuntimeArray",
    "createRefFromList",
    "([Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeArray;",
    false);
```

---

## Notes

- Phase 1 targets quick wins with clear fix paths
- Phase 2 addresses core functionality that may take longer
- Phase 3 polishes edge cases and error handling
- Phase 2.5 is OPTIONAL and HIGH RISK - only attempt if feeling brave
- Some fixes may unlock additional tests beyond the direct count
- Category 3 shares root cause with Pod tests - fix once, benefit twice (if we dare!)

**DO NOT EXECUTE YET** - This is the analysis and plan only.


# Fix Plan for re/subst.t (135 failures)

**Date:** 2025-10-09  
**Target:** 135 failures in `re/subst.t` + likely 270 more in related tests

---

## Root Causes Identified

### Issue 1: Backslash Escaping in Replacement Strings ❌

**Problem:** `s/x/\$x/` produces `\$x` instead of `$x`

**Current Behavior:**
```perl
# Perl:
$_ = "x"; s/x/\$x/;  # Result: "$x"

# PerlOnJava:
$_ = "x"; s/x/\$x/;  # Result: "\$x"  ❌
```

**AST Analysis:**
```
OperatorNode: replaceRegex
  ListNode:
    StringNode: 'x'           # Pattern
    StringNode: '\$x'         # Replacement - backslash preserved!
    StringNode: empty         # Modifiers
```

**Root Cause:**
- Parse time: `\$x` is stored as literal `StringNode: '\$x'` (correct for AST)
- Runtime: `RuntimeRegex.replaceRegex()` line 534 uses `Matcher.quoteReplacement(replacementStr)`
- `Matcher.quoteReplacement()` escapes ALL special characters, preventing backslash interpretation

**The Problem:**
Perl processes backslash escapes in replacement strings at parse time:
- `\$` → `$` (escaped dollar sign)
- `\\` → `\` (escaped backslash)
- `\n` → newline
- But the string is stored with backslashes in the AST

Then at runtime, Java's `Matcher.quoteReplacement()` treats the string as completely literal, so `\$x` stays as `\$x`.

**Solution Options:**

**Option A: Process escapes at parse time** (RECOMMENDED)
- Modify `StringDoubleQuoted` or `StringSingleQuoted` to process `\$` → `$` when `parseEscapes=true`
- Store the processed string in the AST
- Remove `Matcher.quoteReplacement()` call in RuntimeRegex

**Option B: Process escapes at runtime**
- Keep AST as-is with `\$x`
- In `RuntimeRegex.replaceRegex()`, manually process escape sequences before calling `appendReplacement()`
- Don't use `Matcher.quoteReplacement()`

**Recommended: Option A** - Cleaner separation, escape processing happens once at parse time

---

### Issue 2: Empty Pattern Reuse Not Working ❌

**Problem:** `s///` should reuse the previous pattern but doesn't

**Current Behavior:**
```perl
# Perl:
$_ = 'ABACADA';
/a/i && s///gi;  # Result: "BCD" (reuses /a/i pattern)

# PerlOnJava:
$_ = 'ABACADA';
/a/i && s///gi;  # Result: "ABACADA" (no substitution) ❌
```

**AST Analysis:**
```
BinaryOperatorNode: &&
  OperatorNode: matchRegex
    ListNode:
      StringNode: 'a'
      StringNode: 'i'
  OperatorNode: replaceRegex
    ListNode:
      StringNode: empty    # Empty pattern!
      StringNode: empty    # Empty replacement
      StringNode: 'gi'
```

**Root Cause:**
- When pattern is empty, should use `${^LAST_SUCCESSFUL_PATTERN}`
- This is not implemented in `RuntimeRegex.replaceRegex()`

**Solution:**
In `RuntimeRegex.replaceRegex()`:
```java
public static RuntimeBase replaceRegex(...) {
    RuntimeRegex regex = resolveRegex(quotedRegex);
    
    // NEW: Handle empty pattern - reuse last successful pattern
    if (regex.patternString == null || regex.patternString.isEmpty()) {
        if (lastSuccessfulPattern != null) {
            regex = lastSuccessfulPattern;
        } else {
            // Error: No previous pattern
            throw new PerlCompilerException("No previous regular expression");
        }
    }
    
    // ... rest of method
}
```

---

### Issue 3: Warning/Error Capture Not Working ❌

**Problem:** Warnings and errors from substitution operations not being captured

**Tests Affected:**
- Test 11: `s///r` with `!~` operator should give error
- Test 13: Uninitialized value warning
- Test 14: Void context warning

**Current Behavior:**
```perl
# Test 11: Should throw error
eval '$a !~ s/david/is great/r';
# Perl: $@ = "Using !~ with s///r doesn't make sense"
# PerlOnJava: $@ = ""  ❌

# Test 13: Should warn
use warnings;
$a = undef;
$b = $a =~ s/left/right/r;
# Perl: Warning "Use of uninitialized value"
# PerlOnJava: No warning  ❌

# Test 14: Should warn
use warnings;
eval 's/david/sucks/r; 1';
# Perl: Warning "Useless use of non-destructive substitution"
# PerlOnJava: No warning  ❌
```

**Root Causes:**

**3a. Missing validation for `!~` with `s///r`:**
- Need to check at parse time or compile time
- Location: Likely in `OperatorParser` or `EmitOperator`
- Should throw: "Using !~ with s///r doesn't make sense"

**3b. Uninitialized value warning not generated:**
- `RuntimeRegex.replaceRegex()` doesn't check if input is undef
- Should call `WarnDie.warn()` when operating on undef

**3c. Void context warning not generated:**
- Need to detect when `s///r` is used in void context
- Should warn: "Useless use of non-destructive substitution"
- Location: Compile-time check in emitter

**Solutions:**

**For 3a (s///r with !~):**
```java
// In OperatorParser or wherever !~ is handled
if (operator.equals("!~") && isNonDestructiveSubstitution(rightSide)) {
    throw new PerlCompilerException("Using !~ with s///r doesn't make sense");
}
```

**For 3b (Uninitialized warning):**
```java
// In RuntimeRegex.replaceRegex(), add at start:
if (string.type == RuntimeScalarType.UNDEF) {
    WarnDie.warn(new RuntimeScalar("Use of uninitialized value in substitution (s///)"),
                 RuntimeScalarCache.scalarEmptyString);
}
```

**For 3c (Void context warning):**
```java
// In EmitOperator or wherever s///r is emitted
if (ctx == RuntimeContextType.VOID && regex.hasNonDestructiveFlag()) {
    WarnDie.warn(new RuntimeScalar("Useless use of non-destructive substitution"),
                 RuntimeScalarCache.scalarEmptyString);
}
```

---

## Implementation Priority

### Phase 1: High-Impact Fixes (2-3 hours)

1. **Fix backslash escaping** (Issue 1)
   - Modify escape processing in string parsing
   - Remove `Matcher.quoteReplacement()` usage
   - **Impact:** ~30-40 tests

2. **Fix empty pattern reuse** (Issue 2)
   - Add check for empty pattern in `replaceRegex()`
   - Reuse `lastSuccessfulPattern`
   - **Impact:** ~10-15 tests

**Subtotal:** ~40-55 tests

### Phase 2: Warning/Error Fixes (1-2 hours)

3. **Add `!~` with `s///r` validation** (Issue 3a)
   - Parse-time or compile-time check
   - **Impact:** 1 test

4. **Add uninitialized warning** (Issue 3b)
   - Runtime check in `replaceRegex()`
   - **Impact:** 1 test

5. **Add void context warning** (Issue 3c)
   - Compile-time check in emitter
   - **Impact:** 1 test

**Subtotal:** ~3 tests

### Total Expected Impact

- **Direct:** ~43-58 tests in `re/subst.t`
- **Cascade:** Likely fixes `re/substT.t` and `re/subst_wamp.t` (+270 tests)
- **Total Potential:** ~313-328 tests

---

## Testing Strategy

### Minimal Test Cases

```perl
# Test 1: Backslash escaping
my $x = 'foo';
$_ = "x";
s/x/\$x/;
# Expected: "$x"

# Test 2: Mixed escaping
s/x/\$x $x/;
# Expected: "$x foo"

# Test 3: Empty pattern reuse
$_ = 'ABACADA';
/a/i && s///gi;
# Expected: "BCD"

# Test 4: !~ with s///r
eval '$a !~ s/x/y/r';
# Expected: Error "Using !~ with s///r doesn't make sense"

# Test 5: Uninitialized warning
use warnings;
my $a = undef;
my $b = $a =~ s/x/y/r;
# Expected: Warning "Use of uninitialized value"

# Test 6: Void context warning
use warnings;
eval 's/x/y/r; 1';
# Expected: Warning "Useless use of non-destructive substitution"
```

### Verification Commands

```bash
# Test individual issues
./jperl test_subst_issues.pl

# Test full suite
./jperl t/re/subst.t 2>&1 | grep -E "(^ok|^not ok)" | tail -20

# Count improvements
./jperl t/re/subst.t 2>&1 | grep -c "^ok"
./jperl t/re/subst.t 2>&1 | grep -c "^not ok"

# Test related files
./jperl t/re/substT.t 2>&1 | grep -c "^ok"
./jperl t/re/subst_wamp.t 2>&1 | grep -c "^ok"
```

---

## Files to Modify

### Primary Files

1. **RuntimeRegex.java** - Fix empty pattern reuse, add warnings
2. **StringDoubleQuoted.java** or **StringSegmentParser.java** - Fix backslash escape processing
3. **OperatorParser.java** or **EmitOperator.java** - Add validation for !~ with s///r

### Test Files

- `test_subst_issues.pl` - Minimal reproduction cases
- `test_subst_simple.pl` - Individual test cases

---

## Success Criteria

- ✅ `s/x/\$x/` produces `$x` not `\$x`
- ✅ `s/x/\$x $x/` produces `$x foo` not `\$x foo`
- ✅ Empty pattern `s///` reuses previous pattern
- ✅ `!~` with `s///r` throws error
- ✅ Uninitialized value warning generated
- ✅ Void context warning generated
- ✅ `re/subst.t` passes 180+ tests (from 146)
- ✅ `re/substT.t` and `re/subst_wamp.t` improve significantly

---

## Next Steps

1. Start with Issue 1 (backslash escaping) - highest impact
2. Implement Issue 2 (empty pattern) - quick win
3. Add warning/error checks (Issue 3) - completeness
4. Test cascade effect on related test files
5. Document findings and update analysis

---

## Notes

- The backslash escaping issue is the most critical - affects many tests
- Empty pattern reuse is a quick fix with good impact
- Warning/error checks are important for Perl compatibility
- Success here likely cascades to 2 other test files
- Total potential: 300+ tests with 3-5 hours of focused work

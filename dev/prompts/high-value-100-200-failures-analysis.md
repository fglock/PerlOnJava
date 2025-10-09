# High-Value Test Analysis: 100-200 Failures

**Date:** 2025-10-09  
**Log:** logs/test_20251008_145600  
**Strategy:** Target high-value incomplete tests with systematic issues

## Executive Summary

Analyzed 17 test files with 100-200 failures. Identified **5 high-priority targets** with clear, fixable root causes that could unlock 500+ tests with focused effort.

---

## 🎯 TOP PRIORITY TARGETS

### 1. **`re/subst.t`** - 135 failures (146/281, 52% pass rate) ⭐⭐⭐

**Status:** INCOMPLETE - Test stops at line 281

**Root Causes Identified:**
1. **Warning detection failures** (4 tests)
   - Tests 11, 13, 14: Warning messages not captured
   - Missing: `!~` with `s///r` error, uninitialized warnings, void context warnings
   
2. **Variable interpolation in replacement** (2 tests)
   - Tests 22, 24: `$x` not interpolated in replacement string
   - Getting literal `$x` instead of variable value
   
3. **Squashing behavior** (1 test)
   - Test 90: Character squashing at end of string

**Expected Impact:** +135 tests  
**Estimated Effort:** 2-3 hours  
**ROI:** High - systematic warning and interpolation issues

**Quick Wins:**
- Fix warning capture mechanism for substitution operations
- Fix variable interpolation in replacement strings
- These patterns likely affect `re/substT.t` and `re/subst_wamp.t` too (270 more tests!)

---

### 2. **`comp/proto.t`** - 147 failures (69/216, 32% pass rate) ⭐⭐⭐

**Status:** BLOCKED at line 323

**Blocking Error:**
```
TODO - create reference of list not implemented
```

**Root Cause:** Missing implementation for creating references to lists

**Test Pattern:**
```perl
sub foo(\&) { ... }  # Expects reference to code
# Fails when trying to create reference of list
```

**Expected Impact:** +147 tests  
**Estimated Effort:** 3-4 hours (needs reference implementation)  
**ROI:** High - unblocks prototype testing

**Dependencies:**
- Requires implementing reference-to-list creation
- May need parser changes for `\&` prototype handling

---

### 3. **`op/postfixderef.t`** - 121 failures (7/128, 5% pass rate) ⭐⭐

**Status:** BLOCKED at line 61

**Blocking Error:**
```
Can't use value as an ARRAY reference
```

**Root Cause:** Postfix dereference syntax not fully implemented

**Test Pattern:**
```perl
$ref->@*   # Postfix array dereference
$ref->%*   # Postfix hash dereference
$ref->&*   # Postfix code dereference
```

**Expected Impact:** +121 tests  
**Estimated Effort:** 4-6 hours (parser + runtime changes)  
**ROI:** Medium-High - modern Perl syntax feature

**Implementation Needed:**
- Parser support for `->@*`, `->%*`, `->&*` syntax
- Runtime dereference operations
- Context-aware behavior

---

### 4. **`op/caller.t`** - 106 failures (6/112, 5% pass rate) ⭐⭐

**Status:** BLOCKED at line 33

**Blocking Error:**
```
Not a CODE reference
```

**Root Causes Identified:**
1. **Caller information missing** (tests 1, 4-5, 8, 10, 12-13)
   - `caller()` returns undef for filename, subroutine name
   - Missing: eval context detection, anonymous sub names
   
2. **#line directive not respected** (tests 4-5)
   - Virtual filenames not working: expects "virtually/op/caller.t"
   - Line numbers not updated: expects 12345, got 374

3. **hasargs flag incorrect** (test 13)
   - Not detecting when subroutine called with arguments

**Expected Impact:** +106 tests  
**Estimated Effort:** 3-5 hours  
**ROI:** High - core debugging feature

**Quick Wins:**
- Fix caller() to return proper context information
- Ensure #line directive updates caller info
- Fix hasargs detection

---

### 5. **`op/bless.t`** - 101 failures (17/118, 14% pass rate) ⭐

**Status:** BLOCKED at line 42

**Blocking Error:**
```
Modification of a read-only value attempted
```

**Root Causes Identified:**
1. **GLOB blessing issues** (tests 13, 16)
   - ref() returns "GLOB" instead of blessed class name
   - Stringification incorrect

2. **CODE blessing issues** (tests 18-20)
   - Blessed CODE refs not matching expected format
   - Pattern match failures

3. **REF blessing issues** (tests 23-24)
   - ref() returns "SCALAR" instead of "REF" for references
   - Stringification returns 0 instead of blessed format

**Expected Impact:** +101 tests  
**Estimated Effort:** 2-3 hours  
**ROI:** Medium - blessing edge cases

**Implementation Needed:**
- Fix ref() to return blessed class name for non-standard types
- Fix stringification for blessed GLOBs, CODEs, REFs
- Allow blessing of read-only values

---

## 📊 MEDIUM PRIORITY TARGETS

### 6. **`op/magic.t`** - 147 failures (61/208, 29% pass rate)

**Key Issues:**
- Test 61: File handle operations
- Tests 75-77: `@``, `@&`, `@'` array variables not working
- Tests 80-81: `$;` and `$"` special variables
- Tests 82, 93-94: `./perl` executable path issues (test infrastructure)
- Test 85: `%@` hash variable

**Estimated Effort:** 4-6 hours (many different magic variables)  
**ROI:** Medium - scattered issues

---

### 7. **`re/pat_rt_report.t`** - 142 failures (2372/2514, 94% pass rate)

**Status:** High pass rate - likely specific edge cases  
**Estimated Effort:** 5-8 hours (many individual bug reports)  
**ROI:** Low-Medium - high pass rate suggests edge cases

---

### 8. **`op/packagev.t`** - 115 failures (192/307, 63% pass rate)

**Focus:** Package version handling  
**Estimated Effort:** 3-4 hours  
**ROI:** Medium

---

### 9. **`io/scalar.t`** - 108 failures (20/128, 16% pass rate)

**Blocking Error:**
```
Unsupported file mode: +<&
```

**Root Cause:** File mode parsing incomplete  
**Estimated Effort:** 2-3 hours  
**ROI:** Medium - I/O operations

---

### 10. **`op/utf8decode.t`** - 186 failures (527/713, 74% pass rate)

**Status:** High pass rate - likely systematic UTF-8 edge cases  
**Estimated Effort:** 4-6 hours  
**ROI:** Medium - UTF-8 handling

---

## 🚀 RECOMMENDED ACTION PLAN

### Phase 1: Quick Wins (4-6 hours total)
1. **`re/subst.t`** (2-3 hours)
   - Fix warning capture for substitution
   - Fix variable interpolation in replacements
   - **Bonus:** Likely fixes `re/substT.t` and `re/subst_wamp.t` too (+270 tests)

2. **`op/bless.t`** (2-3 hours)
   - Fix ref() for blessed non-standard types
   - Fix stringification
   - Allow blessing read-only values

**Expected Impact:** +236 tests minimum, possibly +506 with bonus

### Phase 2: High-Value Blockers (6-10 hours total)
3. **`op/caller.t`** (3-5 hours)
   - Fix caller() context information
   - Respect #line directives
   - Fix hasargs detection

4. **`comp/proto.t`** (3-4 hours)
   - Implement reference-to-list creation
   - Fix `\&` prototype handling

**Expected Impact:** +253 tests

### Phase 3: Modern Syntax (4-6 hours)
5. **`op/postfixderef.t`** (4-6 hours)
   - Implement postfix dereference syntax
   - Parser and runtime changes

**Expected Impact:** +121 tests

---

## 📈 TOTAL POTENTIAL IMPACT

**Conservative Estimate:** +610 tests (5 targets)  
**With Bonus (subst family):** +880 tests  
**Total Effort:** 14-22 hours  
**ROI:** 28-63 tests per hour

---

## 🔍 PATTERN ANALYSIS

### Common Issues Across Tests:
1. **Warning capture failures** - Multiple tests
2. **Reference implementation gaps** - `comp/proto.t`, `op/postfixderef.t`
3. **Blessing edge cases** - `op/bless.t`
4. **Caller context information** - `op/caller.t`
5. **Variable interpolation** - `re/subst.t`

### Systematic Fixes Needed:
- Warning/error message capture mechanism
- Reference creation for non-standard types
- Caller() implementation improvements
- Blessing for all reference types
- Variable interpolation in regex replacements

---

## 💡 STRATEGIC RECOMMENDATIONS

**Start with `re/subst.t`:**
- Clear, focused issues
- High ROI potential (3x multiplier with related tests)
- Likely to reveal systematic warning capture issues

**Follow with `op/bless.t`:**
- Well-defined issues
- Moderate complexity
- Important for OOP functionality

**Then tackle `op/caller.t`:**
- Core debugging feature
- Affects many other tests
- Important for stack trace accuracy

**Save `comp/proto.t` and `op/postfixderef.t` for later:**
- More complex implementations
- Require parser changes
- Good for dedicated sessions

---

## 📝 NEXT STEPS

1. **Choose target:** Start with `re/subst.t`
2. **Create minimal test case:** Extract failing patterns
3. **Identify root cause:** Use `--parse` and `--disassemble`
4. **Implement fix:** Focus on systematic issues
5. **Verify:** Test all three subst files
6. **Document:** Update this analysis with findings

---

## 🎯 SUCCESS METRICS

**Exceptional ROI:** 50+ tests/hour  
**Good ROI:** 20-50 tests/hour  
**Acceptable ROI:** 10-20 tests/hour

**Target for this batch:** 28-63 tests/hour (excellent range)

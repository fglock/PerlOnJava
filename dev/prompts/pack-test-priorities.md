# Pack.t Remaining Test Priorities

## Current Status
- **Passing**: 14,374 / 14,724 (97.6%)
- **Failing**: 350 tests
- **Target**: 98%+ (14,500+ passing)

## High Priority Clusters (Quick Wins)

### 1. C0/W Mode Switching Issues (16 tests) ⭐⭐⭐
**Pattern**: "pack a5 C0 W returns expected value"
**Count**: 8 pairs = 16 tests
**Impact**: Medium-High

**Issue**: Mode switching between character/byte mode with W format
**Files**: Tests around C0/U0 mode changes
**Complexity**: Medium - mode tracking issue
**Expected ROI**: 16 tests with single fix

**Test patterns:**
- `pack a5 C0 W` 
- `pack A5 C0 W`
- `pack Z5 C0 W`
- `pack U0U C0 W`
- `pack a0 a5 C0 W` (with a0 prefix)

### 2. UTF-8 Positioning with Groups (12 tests) ⭐⭐⭐
**Pattern**: "utf8 offset is relative to inner group"
**Count**: ~12 tests
**Impact**: High

**Issue**: @ positioning and . (dot) format within groups in UTF-8 mode
**Complexity**: Medium-High - requires group position tracking
**Expected ROI**: 12 tests

**Test patterns:**
- "utf8 offset is relative to inner group" (2)
- "utf8 negative offset relative to inner group" (2)
- "utf8 . relative to group, shorten/keep/extend" (6)
- "U0 mode utf8 offset is relative to inner group" (2)

### 3. Slash Construct Validation (10+ tests) ⭐⭐
**Pattern**: "'/'" error messages
**Count**: ~10 tests
**Impact**: Medium

**Issue**: Validation of slash construct syntax
**Lines**: Around 800-860
**Complexity**: Low - validation logic
**Expected ROI**: 10 tests with validation fixes

**Error patterns:**
- "'/'' must follow a numeric type in pack"
- "'/'' must be followed by a string type"
- NoSuchElementException errors

### 4. Dot (.) Format Positioning (8+ tests) ⭐⭐
**Pattern**: ". relative to group"
**Count**: ~8 tests
**Impact**: Medium

**Issue**: Dot format for absolute positioning
**Complexity**: Medium - position management
**Expected ROI**: 8 tests

**Test patterns:**
- ". relative to group, shorten" (2)
- ". relative to group, keep" (2)  
- ". relative to group, extend" (2)
- "utf8 . based shrink properly updates group starts" (1)

## Medium Priority Issues

### 5. x[template] Bracket Syntax (2 tests) ⭐
**Pattern**: "skipping x[ W3  x1]"
**Count**: 2 tests
**Impact**: Low-Medium

**Issue**: x format with bracketed template count
**Complexity**: Medium - template parsing
**Expected ROI**: 2 tests

### 6. A* Unicode Whitespace (2 tests) ⭐
**Pattern**: "upgraded strings A* removes"
**Count**: 2 tests
**Impact**: Low

**Issue**: A* format should remove all Unicode whitespace
**Complexity**: Low - character classification
**Expected ROI**: 2 tests

### 7. Z0 Format (2 tests) ⭐
**Pattern**: "Z0 format on"
**Count**: 2 tests
**Impact**: Low

**Issue**: Z0 (null-terminated with 0 length) edge case
**Complexity**: Low
**Expected ROI**: 2 tests

### 8. Pointer Formats (4 tests) ⭐
**Pattern**: "unpack p/P upgraded/downgraded"
**Count**: 4 tests
**Impact**: Low

**Issue**: p/P pointer formats with string upgrades
**Complexity**: Medium-High
**Expected ROI**: 4 tests

## Low Priority / Complex Issues

### 9. Large Integer Precision (1 test)
**Pattern**: "unpack pack q 9223372036854775807"
**Count**: 1 test
**Impact**: Low

**Issue**: 64-bit integer precision (32-bit emulation issue)
**Complexity**: High - fundamental limitation
**Expected ROI**: 1 test (may not be fixable)

### 10. Uuencode Issues (2 tests)
**Pattern**: "Warn about too wide uuencode"
**Count**: 2 tests
**Impact**: Low

**Issue**: Uuencode width validation
**Complexity**: Low-Medium
**Expected ROI**: 2 tests

### 11. Math::BigInt Syntax (1 test)
**Pattern**: Test 24 - pack with Math::BigInt
**Count**: 1 test
**Impact**: Low

**Issue**: Parser doesn't support `Package::->method()` syntax
**Complexity**: High - parser change
**Expected ROI**: 1 test
**Note**: Documented in TODO-parser-fixes.md

### 12. BER Compression Edge Cases (2 tests)
**Pattern**: Test 33, 196 - BER precision
**Count**: 2 tests
**Impact**: Low

**Issue**: BER compression of very large numbers
**Complexity**: Medium
**Expected ROI**: 2 tests

### 13. Uuencode/Compression (2 tests)
**Pattern**: Tests 446, 449 - uuencode output
**Count**: 2 tests
**Impact**: Low

**Issue**: Uuencode format implementation
**Complexity**: Medium-High
**Expected ROI**: 2 tests

### 14. Miscellaneous (remaining ~280 tests)
Various edge cases, complex interactions, etc.

## Recommended Attack Plan

### Phase 1: Quick Wins (Target: +40 tests, 1-2 hours)
1. **C0/W mode switching** (+16 tests) - 30 min
2. **Slash construct validation** (+10 tests) - 30 min
3. **A* Unicode whitespace** (+2 tests) - 15 min
4. **Z0 format** (+2 tests) - 15 min
5. **Dot format basics** (+8 tests) - 30 min

### Phase 2: Medium Effort (Target: +20 tests, 2-3 hours)
1. **UTF-8 group positioning** (+12 tests) - 1.5 hours
2. **x[template] syntax** (+2 tests) - 30 min
3. **Pointer formats** (+4 tests) - 1 hour
4. **Uuencode validation** (+2 tests) - 30 min

### Phase 3: Complex Issues (Target: +10 tests, 3+ hours)
1. **BER edge cases** (+2 tests)
2. **Remaining dot/@ positioning** (+8 tests)
3. **Other edge cases**

## Success Metrics

- **Phase 1 complete**: 14,414 passing (97.9%)
- **Phase 2 complete**: 14,434 passing (98.0%) ✨
- **Phase 3 complete**: 14,444+ passing (98.1%+)

## Notes

- Focus on systematic issues that affect multiple tests
- Validation fixes are usually quick wins
- Position tracking issues are more complex
- Some tests may be blocked by fundamental limitations (32-bit emulation)

## Next Steps

1. Start with C0/W mode switching (highest ROI)
2. Move to slash construct validation (quick win)
3. Tackle UTF-8 positioning (high impact)
4. Continue with medium priority items

Target: Reach 98% pass rate (14,500+ tests) in next session!

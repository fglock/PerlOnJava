# Pack/Unpack Master Guide

**Status**: Active Development  
**Current Pass Rate**: 97.6% (14,374 / 14,724 tests)  
**Last Updated**: 2025-10-08

---

## Table of Contents

1. [Current Status](#current-status)
2. [Architecture Overview](#architecture-overview)
3. [Recent Major Achievements](#recent-major-achievements)
4. [Remaining Test Priorities](#remaining-test-priorities)
5. [Known Issues & Limitations](#known-issues--limitations)
6. [Implementation Notes](#implementation-notes)
7. [Testing Strategy](#testing-strategy)

---

## Current Status

### Test Results
- **Total Tests**: 14,724
- **Passing**: 14,374 (97.6%)
- **Failing**: 350 (2.4%)
- **Files at 100%**: Multiple (see achievements)

### Recent Progress
- **Session 2025-10-08**: +74 tests (14,300 → 14,374)
- **Extended Total**: +442 tests from baseline (15,672)
- **Major Wins**: Group subroutine refactoring (+24), W format fix (+143)

---

## Architecture Overview

### Pack/Unpack Symmetry

Both pack and unpack now use the **subroutine approach** for groups:

```java
// Pack groups call pack recursively
RuntimeScalar groupResult = packFunction.pack(groupArgs);

// Unpack groups call unpack recursively  
RuntimeList groupResult = unpackFunction.unpack(template, state, startsWithU, modeStack);
```

### Key Components

1. **GroupEndiannessHelper** - Shared logic for applying group-level endianness
2. **PackGroupHandler** - Handles pack group processing with recursive calls
3. **UnpackGroupProcessor** - Handles unpack group processing with recursive calls
4. **UnpackState** - Tracks position and mode during unpacking
5. **PackBuffer** - Manages output during packing

### Format Handlers

Both pack and unpack use handler pattern:
- `FormatHandler` interface
- Separate handlers for each format (numeric, string, control, etc.)
- Modular and extensible design

---

## Recent Major Achievements

### 1. Unpack Group Subroutine Refactoring (+24 tests) 🎯

**Problem**: Unpack used custom `processGroupContent` parser (~300 lines of duplicate logic)

**Solution**: 
- Created `UnpackFunction` interface for recursive calls
- Split `unpack()` into public entry point and `unpackInternal()`
- Groups now recursively call `unpackInternal()` with shared state
- Position-based infinite loop detection

**Impact**:
- ✅ Group endianness works correctly (e.g., `(I2)>`)
- ✅ No more infinite loops with `(SL)*` patterns
- ✅ Eliminated duplicate parsing logic
- ✅ Pack and unpack architecturally symmetric

**Files Modified**:
- `Unpack.java` - Added `unpackInternal()` method
- `UnpackGroupProcessor.java` - Refactored to use recursive calls

### 2. Pack W Format Fix (+143 tests) 🎯🎯

**Problem**: W format was UTF-8 encoding values instead of writing raw bytes

**Solution**: Write code point values as raw bytes, not UTF-8 encoded
```java
if (codePoint <= 0xFF) {
    output.write(codePoint);  // Single byte
} else {
    output.writeCharacter(codePoint);  // Multi-byte character code
}
```

**Impact**: Largest single fix in recent sessions

### 3. Pack Slash Construct Multi-Value Fix (+173 tests) 🎯🎯

**Problem**: Value index not propagated correctly in slash constructs

**Solution**: Return both position and valueIndex from `handleSlashConstruct()`
```java
public static GroupResult handleSlashConstruct(...) {
    // ... process values, updating valueIndex ...
    return new GroupResult(endPos, valueIndex);
}
```

### 4. Group Endianness Propagation (+6 tests)

**Problem**: Group-level endianness not applied to formats inside groups

**Solution**: Created `GroupEndiannessHelper.applyGroupEndianness()`
- Recursively applies endianness to nested groups
- Handles `!` modifier positioning correctly
- Shared between pack and unpack

### 5. Other Significant Fixes

- **Unpack W format inversion** (+23 tests) - Fixed character/byte mode logic
- **Unpack x!/X! alignment** (+21 tests) - Implemented boundary alignment
- **Regex \x{...} nested quantifiers** (+50 tests) - Fixed escape sequence handling
- **Pack/Unpack checksum** (+113 tests) - Precision and float handling

---

## Remaining Test Priorities

### High Priority Clusters (Quick Wins)

#### 1. C0/W Mode Switching (16 tests) ⭐⭐⭐
**Estimated Time**: 30 minutes  
**Complexity**: Medium

**Issue**: Mode switching between character/byte mode with W format

**Test Patterns**:
- `pack a5 C0 W returns expected value`
- `pack A5 C0 W returns expected value`
- `pack Z5 C0 W returns expected value`
- `pack U0U C0 W returns expected value`

**Approach**: Debug mode tracking in W format handler

#### 2. UTF-8 Positioning with Groups (12 tests) ⭐⭐⭐
**Estimated Time**: 1.5 hours  
**Complexity**: Medium-High

**Issue**: @ positioning and . (dot) format within groups in UTF-8 mode

**Test Patterns**:
- "utf8 offset is relative to inner group"
- "utf8 negative offset relative to inner group"
- "utf8 . relative to group, shorten/keep/extend"

**Approach**: Implement group-relative position tracking

#### 3. Slash Construct Validation (10 tests) ⭐⭐
**Estimated Time**: 30 minutes  
**Complexity**: Low

**Issue**: Validation of slash construct syntax

**Error Patterns**:
- "'/'' must follow a numeric type in pack"
- "'/'' must be followed by a string type"

**Approach**: Add validation checks in slash construct parser

#### 4. Dot (.) Format Positioning (8 tests) ⭐⭐
**Estimated Time**: 30 minutes  
**Complexity**: Medium

**Issue**: Dot format for absolute positioning within groups

**Approach**: Implement position management for dot format

### Medium Priority Issues

- **x[template] bracket syntax** (2 tests) - Template-based count
- **A* Unicode whitespace** (2 tests) - Character classification
- **Z0 format** (2 tests) - Edge case handling
- **Pointer formats** (4 tests) - p/P with string upgrades

### Low Priority / Complex

- **Large integer precision** (1 test) - 32-bit emulation limitation
- **Uuencode issues** (2 tests) - Width validation
- **Math::BigInt syntax** (1 test) - Parser limitation (documented separately)
- **BER compression edge cases** (2 tests) - Very large numbers

### Attack Plan

**Phase 1: Quick Wins** (Target: +40 tests, 1-2 hours)
1. C0/W mode switching (+16)
2. Slash construct validation (+10)
3. A* Unicode whitespace (+2)
4. Z0 format (+2)
5. Dot format basics (+8)

**Phase 2: Medium Effort** (Target: +20 tests, 2-3 hours)
1. UTF-8 group positioning (+12)
2. x[template] syntax (+2)
3. Pointer formats (+4)
4. Uuencode validation (+2)

**Phase 3: Complex Issues** (Target: +10 tests, 3+ hours)
1. BER edge cases (+2)
2. Remaining positioning (+8)

**Goal**: Reach 98% pass rate (14,500+ tests)

---

## Known Issues & Limitations

### 1. 32-Bit Emulation

PerlOnJava emulates 32-bit Perl (ivsize=4, nv_preserves_uv_bits=32):
- Values > 2^32 stored as doubles
- Precision loss for values > 2^53
- Q format checksums may be off by 1

**Impact**: Some tests with large integers will fail
**Mitigation**: Tests use Config{nv_preserves_uv_bits} to skip or adjust expectations

### 2. Parser Limitations

**Package::->method() syntax not supported**:
```perl
Math::BigInt::->new(5000000000)  # Fails
Math::BigInt->new(5000000000)     # Works
```

**Impact**: Test 24 in pack.t
**Workaround**: Use standard `->` syntax
**Status**: Documented in TODO-parser-fixes.md

### 3. Slash Construct Groups

Slash constructs with groups (e.g., `n/(...)`) still use old `processGroupContent`:
- Located in `UnpackHelper.processSlashConstruct()`
- Low priority - less common usage
- Would need UnpackFunction parameter

### 4. JPERL_UNIMPLEMENTED Environment Variable

Setting `JPERL_UNIMPLEMENTED=warn` can cause tests to FAIL that normally PASS:
- Always test WITHOUT this flag for accurate baseline
- Test runner selectively sets it for specific tests only

---

## Implementation Notes

### Group Endianness Application

The `GroupEndiannessHelper.applyGroupEndianness()` method:
1. Iterates through group content
2. For each format that supports endianness (sSiIlLqQjJfFdDpP):
   - Checks for existing `!` modifier
   - Adds endianness after `!` if present
   - Handles repeat counts (digits, *, [...])
3. Recursively processes nested groups

**Example**:
```
Input:  "I!4"  with endian '>'
Output: "I!>4"
```

### Infinite Loop Detection

For `*` (infinite) repeat groups:
```java
int positionBefore = state.getPosition();
RuntimeList groupResult = unpackFunction.unpack(...);
int positionAfter = state.getPosition();

if (positionAfter == positionBefore) {
    break;  // No progress - prevent infinite loop
}
```

### Mode Stack Management

Both pack and unpack use mode stacks for C0/U0 handling:
```java
modeStack.push(state.isCharacterMode());
// ... process group ...
boolean savedMode = modeStack.pop();
if (savedMode) {
    state.switchToCharacterMode();
} else {
    state.switchToByteMode();
}
```

### Slash Construct Pattern

Pack slash constructs:
```
N/a*  → Pack count (N), then string (a*) with that count
```

Implementation:
1. Pack the count format
2. Count how many items to pack
3. Pack the items
4. Write count value at the beginning

---

## Testing Strategy

### Development Workflow

1. **Quick builds**: `make` (incremental)
2. **Clean builds**: `make dev` (forces recompilation)
3. **Unit tests**: `make test` (ALWAYS run before commit)
4. **Integration test**: `./jperl t/op/pack.t`

### Test Isolation

For debugging specific failures:
```bash
# Extract failing test
sed -n 'START,ENDp' t/op/pack.t > test_case.pl

# Test in isolation
./jperl test_case.pl

# Compare with Perl
perl test_case.pl
```

### Regression Prevention

**CRITICAL**: Always run `make test` before committing!
- Unit tests catch regressions that integration tests miss
- Recent incidents: Character/byte mode fix broke unit tests

### Test Counting

```bash
# Count passing/failing
./jperl t/op/pack.t 2>&1 | grep -c "^ok "
./jperl t/op/pack.t 2>&1 | grep -c "^not ok"

# Analyze failure patterns
./jperl t/op/pack.t 2>&1 | grep "^not ok" | sed 's/not ok [0-9]* - //' | sort | uniq -c | sort -rn
```

---

## Historical Context

### Major Milestones

1. **Sublanguage Parser Architecture** - Comprehensive validation and error messages
2. **Regex Nested Quantifier Fix** - 559 tests (sprintf.t 100% pass rate)
3. **Pack/Unpack Checksum** - 113 tests (precision handling)
4. **Transliteration Validation** - 64 tests (ambiguous ranges)
5. **Pack W Format** - 143 tests (raw byte writing)
6. **Pack Slash Construct** - 173 tests (value index propagation)
7. **Unpack Group Subroutine** - 24 tests (architectural symmetry)

### Success Patterns

- **Target systematic issues** for exponential impact
- **Single fixes** can yield 50-500+ test improvements
- **Validation/error messages** are quick wins
- **Deep understanding** of edge cases pays off
- **Incremental approach** with testing at each step

### ROI Metrics

- **Exceptional sessions**: 100+ tests/hour
- **Good sessions**: 50+ tests/hour
- **Average sessions**: 20-40 tests/hour

---

## Quick Reference

### File Locations

**Pack**:
- `src/main/java/org/perlonjava/operators/Pack.java`
- `src/main/java/org/perlonjava/operators/pack/PackGroupHandler.java`
- `src/main/java/org/perlonjava/operators/pack/PackHelper.java`
- `src/main/java/org/perlonjava/operators/pack/NumericPackHandler.java`

**Unpack**:
- `src/main/java/org/perlonjava/operators/Unpack.java`
- `src/main/java/org/perlonjava/operators/unpack/UnpackGroupProcessor.java`
- `src/main/java/org/perlonjava/operators/unpack/UnpackHelper.java`
- `src/main/java/org/perlonjava/operators/UnpackState.java`

**Shared**:
- `src/main/java/org/perlonjava/operators/pack/GroupEndiannessHelper.java`
- `src/main/java/org/perlonjava/operators/pack/PackHelper.java`

**Tests**:
- `t/op/pack.t` - Main test file (14,724 tests)

### Common Commands

```bash
# Development
make dev                    # Clean build
make test                   # Unit tests
./jperl t/op/pack.t        # Integration test

# Analysis
./jperl t/op/pack.t 2>&1 | grep "^not ok" | head -20
./jperl t/op/pack.t 2>&1 | grep "^not ok" | sed 's/not ok [0-9]* - //' | sort | uniq -c | sort -rn

# Debugging
echo 'pack/unpack code' | ./jperl
./jperl --parse -e 'code'  # Check AST
```

---

## Next Session Goals

1. **Immediate**: C0/W mode switching (+16 tests, 30 min)
2. **Quick win**: Slash construct validation (+10 tests, 30 min)
3. **High impact**: UTF-8 group positioning (+12 tests, 1.5 hours)
4. **Target**: Reach 14,414 passing (97.9%)
5. **Stretch**: Reach 14,500 passing (98.0%) ✨

---

## Appendix: Key Commits

- `49fb6785` - Major: Unpack group subroutine refactoring (+24 tests)
- `6edaf51e` - Fix: Pack group endianness propagation (+6 tests)
- `b386a58d` - Refactor: Extract GroupEndiannessHelper
- `9a684ff3` - Fix: Pack W format raw byte writing (+143 tests)
- `389f5f5e` - Fix: Pack slash construct value handling (+173 tests)

---

**This is a living document. Update after each major session.**

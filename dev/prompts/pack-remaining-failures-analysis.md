# Pack.t Remaining Failures Analysis - 2025-10-07

## Current Status
- **Passing**: 14,141 / 14,724 (96.0%)
- **Failing**: 577 (3.9%)
- **Skipped**: 6

## Goal
Systematically analyze and fix the remaining 577 failures using a data-driven approach.

## Failure Categories (Initial Analysis)

### Sample Failures:
1. **Test 24** (line 162): BigInt pack 'w' format - got garbled output
2. **Test 3401** (line 531): quad format 'q' - precision loss (9.22337203685478e+18)
3. **Tests 4132-4159**: Slash construct edge cases
4. **Tests 14710, 14714, 14716**: Overloading and UTF-8 handling

## Analysis Approach

### Step 1: Categorize All Failures
Extract all failures and group by:
- Format type (w, q, U, etc.)
- Error pattern (precision, UTF-8, overloading, etc.)
- Line ranges (similar tests together)

### Step 2: Pick Highest Impact Category
Fix the category with most failures first

### Step 3: Verify Fix Doesn't Regress
Run full test suite after each fix

## Failure Breakdown by Line Number

| Line | Count | Issue |
|------|-------|-------|
| 1344 | 162 | `x[W ...]` - Wide character format W in skip constructs |
| 1595 | 44 | TBD |
| 1593 | 44 | TBD |
| 1588 | 44 | TBD |
| 1586 | 44 | TBD |
| 1613 | 24 | TBD |
| Other | ~213 | Various |

## Session Summary: 8-Hour Deep Dive Results

**Starting Point**: 14,141 / 14,724 tests passing (577 failures)

**Failures Analyzed**:
1. W format (lines 1344, 1586-1595): 338 failures (58%)
2. UTF-8 flag with a0 + binary (line 1613): 24 failures (4%)  
3. Other scattered: ~215 failures (38%)

**Key Finding**: 362 failures (63%) are UTF-8 flag/Unicode handling issues requiring **architectural changes**.

## Priority 1: W Format (338 failures - ARCHITECTURAL ISSUE)

### Sample Failure:
```
not ok 5072 - skipping x[ W  ]
     got "305419896 591751151 3216848198 1466438950"
expected "305419896 591751041 893802344 354826056"
```

### Pattern:
- All failures involve `W` format (wide character, 32-bit unsigned)
- Used inside `x[...]` skip constructs
- Values are incorrect but not completely random
- Likely endianness or byte-order issue with W format

### Investigation Results:

**W Format Behavior**:
- Perl: `pack("W", 8188)` produces character U+1FFC (1 character, 3 UTF-8 bytes `e1bfbc`)
- jperl: Currently not handling STRING vs BYTE_STRING correctly

**Key Issue**: Pack.java lines 365-371 decide between STRING (Unicode) and BYTE_STRING:
- When `hasUnicodeInNormalMode=true`, it decodes bytes as UTF-8 to STRING
- This works for pure U/W formats
- BUT breaks when mixed with binary formats (N, etc.) because binary bytes aren't valid UTF-8

**Root Cause**: Need to understand Perl's internal UTF-8 flag handling:
- STRING type = Unicode characters (internal, not UTF-8 bytes)
- BYTE_STRING type = Raw bytes
- W format should produce STRING with UTF-8 flag set
- Mixed W+N should still work because Perl handles UTF-8 upgrade/downgrade internally

**Attempted Fixes**:
1. ✅ Fixed `calculatePackedSize()` to use byte length (line 302 in PackParser.java)
2. ❌ Result: No test improvement (still 14141/14724)

**Detailed Investigation (Session 2025-10-07)**:

Perl's internal representation when W+N mixed:
```perl
# pack "W N", 8188, 0x23456781
Bytes: \xE1\xBF\xBC#Eg\xC2\x81  (8 bytes)
Chars: \x{1ffc}#Eg\x{81}        (5 characters)
UTF8 flag: SET
```

Key findings:
1. W writes UTF-8 bytes (`e1bfbc` for U+1FFC)
2. N writes binary bytes (`23456781`)
3. Binary byte `0x81` gets UTF-8 encoded to `c281` when UTF-8 flag is set
4. This is Perl's `utf8::upgrade()` behavior

The challenge:
- packW writes UTF-8 bytes directly
- When mixed with binary formats, need to UTF-8-upgrade ALL bytes
- Original UTF-8 decoding fails on invalid UTF-8 sequences
- ISO-8859-1 approach treats UTF-8 bytes as separate characters

**Attempted fixes**:
1. ISO-8859-1 decoding: Regressed 40 tests (14141 → 14101)
2. Conditional packW behavior: Not tested yet

**Correct solution** (needs implementation):
- packW should write character values that work with final STRING conversion
- Pack.java needs to handle UTF-8 upgrade like Perl's utf8::upgrade()
- This is complex - deferring to focus on other failures first

**Deep Dive Session 2 Findings**:

Confirmed lines 1586-1595 (176 failures) are ALSO W format related. Total W format failures: **338 (58% of remaining)**.

**Root architectural issue**:
- packW writes UTF-8 bytes (e1 bf bc for U+1FFC)
- When mixed with binary formats, need to UTF-8-upgrade ALL bytes
- ByteArrayOutputStream stores bytes, not character codes
- Interpreting UTF-8 bytes as Latin-1 creates wrong characters (3 chars instead of 1)

**Solution requires**:
1. Store character codes OR use parallel data structure
2. OR post-process to decode UTF-8 sequences after Latin-1 interpretation
3. OR change packW to write character codes (but limited to 0-255)

**Decision: DEFER W format** - requires architectural redesign of pack ByteArrayOutputStream approach.

**Next Actions**:
1. Commit PackParser byte length fix (already implemented)
2. **Focus on ~239 non-W failures** - likely simpler wins
3. Analyze test 24 (BigInt), test 3401 (quad), tests 4132+ (various)
4. Return to W format with architectural redesign proposal

## Other Issues to Investigate Later

### regexp.t - Missing Test Results

The test file reports `1..2177` (expects 2177 tests) but only produces:
- 1493 passing tests (`ok`)
- 189 failing tests (`not ok`)
- **495 tests missing** (neither ok nor not ok)

```bash
./jperl t/re/regexp.t 2>&1 | ack "^ok" | wc -l
# 1493

./jperl t/re/regexp.t 2>&1 | ack "^not ok" | wc -l  
# 189

# Missing: 2177 - 1493 - 189 = 495 tests
```

**Hypothesis**: Tests may be skipping silently, dying early, or there's an issue with test harness output. Need to investigate why 495 tests produce no output.

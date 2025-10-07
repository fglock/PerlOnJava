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

## Priority 1: Fix W Format in Skip Constructs (162 failures)

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

**Next Actions**:
1. Study Perl's UTF-8 flag behavior in detail
2. Review how RuntimeScalar handles STRING vs BYTE_STRING conversions
3. May need to implement proper UTF-8 upgrade/downgrade in jperl
4. Consider if W format should NOT trigger UTF-8 decoding when mixed with binary formats

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

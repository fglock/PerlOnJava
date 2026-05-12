# Sub::HandlesVia UTF-8 Fix

**Status**: In Progress - Testing reveals deeper corruption issue requiring investigation

## Problem

When Sub::HandlesVia generates accessor delegation code, orphaned UTF-8 lead bytes (0xC0-0xDF, 0xE0-0xEF, 0xF0-0xF7) appear in generated Perl code, causing syntax errors:

```
Global symbol "@shv_tmp\x{c2}" requires explicit package name
Unrecognized character \x{c2}; at /Users/fglock/.perlonjava/lib/Eval/TypeTiny.pm line 8
syntax error at set_option=Hash:set line 5, near "\"Wrong number "
```

## Root Cause Analysis

The corruption stems from UTF-8/Latin-1 encoding mismatch:

1. UTF-8 files may be decoded as Latin-1, leaving multi-byte sequences as orphaned high bytes
2. When regex substitutions occur, these misencoded bytes can persist or become orphaned (lead byte without continuation)
3. The orphaned bytes end up in generated Perl code evaluated at runtime, breaking parsing

## Solution (branch: `fix/sub-handlesvia-utf8`, PR #717)

Three complementary fixes:

### 1. Extended UTF-8 Lead Byte Repair (RuntimeRegex.java:1380-1439)
**Commit 7e487f2f5** - Handles all UTF-8 lead byte types:
- 0xC0-0xDF = 2-byte sequences (need 1 continuation)
- 0xE0-0xEF = 3-byte sequences (need 2 continuations)  
- 0xF0-0xF7 = 4-byte sequences (need 3 continuations)

**Implementation**: Scans for orphaned lead bytes and removes them while preserving valid multi-byte sequences.

### 2. Standard Perl 5 File Encoding (Reverted)

Previously added UTF-8 preference in FileUtils, but this deviates from standard Perl 5 behavior where files without `use utf8` are treated as Latin-1. **Reverted in commit 12222348b** to maintain compatibility. File encoding detection now follows Perl 5 standard: non-ASCII bytes that aren't valid UTF-8 are treated as Latin-1.

### 3. UTF-8 Detection Improvements (commit 919138037)
Refined logic to better identify orphaned byte patterns.

## Test Results (in progress)

Running `./jcpan -t Sub::HandlesVia` (all test suites):
- **t/00begin.t**: ✓ ok
- **t/01basic.t**: ✓ ok  
- **t/02moo/*.t**: Mixed (trait_* tests failing with corruption)
- **t/04moose/*.t**: Blocked by corruption in generated code
- **t/05moose_nativetypes/*.t**: Blocked by corruption

**Issue found**: Despite repair logic improvements, orphaned `\x{c2}` bytes still appear in generated accessor code. This suggests either:
1. Repair isn't being triggered (detection logic may not catch all corruption patterns)
2. Corruption happens in a different code path not covered by the regex substitution repair
3. The source corruption is occurring earlier in file reading/parsing

## Next Steps

1. **Investigate corruption source**: Determine if issue is in regex substitution path or elsewhere in code generation
2. **Trace code paths**: Check all places where generated code is constructed (not just s/// substitutions)
3. **Verify encoding detection**: Confirm FileUtils UTF-8 preference is working correctly
4. **Consider deeper fix**: May need to normalize all strings early in code generation pipeline

## Related Commits

- `7e487f2f5`: Handle all UTF-8 lead byte types (2/3/4-byte) - Current
- `db417e2e8`: Conservative repair + prefer UTF-8 encoding
- `919138037`: Improve orphaned byte detection logic  
- `23ff02e57`: Original repair (too aggressive)

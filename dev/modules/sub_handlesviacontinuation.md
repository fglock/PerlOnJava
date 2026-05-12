# Sub::HandlesVia UTF-8 Fix

**Status**: Testing new approach - Eval-time UTF-8 repair

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
2. When Perl code (like Sub::HandlesVia::CodeGenerator) does string concatenation to generate methods, if the source strings contain multi-byte UTF-8 sequences being interpreted as Latin-1, the corruption persists
3. The orphaned bytes end up in generated Perl code that is eval'd at runtime, breaking parsing

## Solution (branch: `fix/sub-handlesvia-utf8`)

### Previous Attempts:
1. **Extended UTF-8 Lead Byte Repair** - Only in regex substitutions (limited scope)
2. **UTF-8 File Encoding Preference** - Reverted (non-standard Perl behavior)

### New Approach - Eval-time Repair (commit d7f725e27):

Apply UTF-8 corruption repair in **EvalStringHandler** before the Lexer processes eval'd code. This catches corruption from ALL code generation paths, not just regex substitutions.

**Changes**:
- Made `RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted()` public and static
- Added repair call in EvalStringHandler before parsing (Step 1b)
- Repair runs on ALL eval STRING code, catching Sub::HandlesVia generated methods early

**Why this works**:
- Sub::HandlesVia generates code via Perl string concatenation → eval
- Corruption happens during concatenation (not captured by regex repair)
- Now repair happens before the corrupted code reaches the Lexer
- Orphaned lead bytes are removed, allowing valid parsing

## Test Results (in progress)

Running `./jcpan -t Sub::HandlesVia` with new eval-time repair...

## Related Commits

- `d7f725e27`: Apply UTF-8 repair in eval STRING handler - Current  
- `d50c2387b`: Document revert of UTF-8 file encoding preference
- `12222348b`: Revert non-standard UTF-8 preference
- `7e487f2f5`: Extended UTF-8 lead byte repair (regex-only)


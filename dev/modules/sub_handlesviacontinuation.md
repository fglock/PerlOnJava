# Sub::HandlesVia UTF-8 Fix

**Status**: Solution implemented - Eval-time UTF-8 repair applied to both interpreter and JVM compilation paths

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

### Implementation - Eval-time Corruption Repair

Apply UTF-8 corruption repair in BOTH eval paths:
1. **EvalStringHandler** (interpreter path)  - commit d7f725e27
2. **RuntimeCode.evalStringHelper** (JVM compilation path) - commit a436b95ed

**Why eval-time repair?**
- Sub::HandlesVia generates code via Perl string concatenation, not regex substitutions
- Previous regex-only repair missed this code generation path
- By repairing ALL eval'd code before the Lexer processes it, we catch corruption from all sources

**Changes**:
- Made `RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted()` public and static
- Added repair call in EvalStringHandler before parsing (Step 1b)
- Added repair call in RuntimeCode.evalStringHelper before Lexer/Parser
- Orphaned lead bytes are removed, allowing valid parsing

## Test Results

Testing with renewed build of PerlOnJava with UTF-8 repairs applied to both eval paths.

## Technical Details

The corruption repair function:
- Scans for orphaned UTF-8 lead bytes (0xC0-0xDF, 0xE0-0xEF, 0xF0-0xF7)
- Verifies proper continuation byte sequences (0x80-0xBF)
- Removes orphaned lead bytes while preserving valid multi-byte sequences
- Keeps ASCII and properly-formed UTF-8 intact

## Related Commits

- `a436b95ed`: Apply UTF-8 repair in RuntimeCode.evalStringHelper - JVM path
- `d7f725e27`: Apply UTF-8 repair in EvalStringHandler - Interpreter path
- `d50c2387b`: Document revert of UTF-8 file encoding preference
- `12222348b`: Revert non-standard UTF-8 preference (keep Perl 5 standard)
- `7e487f2f5`: Extended UTF-8 lead byte repair (regex-only, earlier approach)



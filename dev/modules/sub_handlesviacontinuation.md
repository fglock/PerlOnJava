# Sub::HandlesVia UTF-8 Fix

**Status**: ✅ RESOLVED - All tests passing

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

## Solution

### Implementation - Eval-time Corruption Repair with Bug Fix

Apply UTF-8 corruption repair in BOTH eval paths with corrected control flow logic:

1. **EvalStringHandler** (interpreter path) - commit d7f725e27
2. **RuntimeCode.evalStringHelper** (JVM compilation path) - commit a436b95ed
3. **RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted** - Fixed control flow bug - commit 3e5e9d6ac

**Why eval-time repair?**
- Sub::HandlesVia generates code via Perl string concatenation, not regex substitutions
- Previous regex-only repair missed this code generation path
- By repairing ALL eval'd code before the Lexer processes it, we catch corruption from all sources

**Bug Fix (commit 3e5e9d6ac)**
- The repair function had a control flow issue where orphaned lead bytes were being skipped but subsequent characters were still appended due to the else-if structure
- This caused duplication/modification of regular characters (e.g., "of" becoming "oaof")
- Fixed by removing the empty else block and adding explicit comments clarifying the skip behavior

**Changes**:
- Made `RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted()` public and static
- Added repair call in EvalStringHandler before parsing
- Added repair call in RuntimeCode.evalStringHelper before Lexer/Parser
- Fixed control flow logic to properly skip orphaned bytes without side effects
- Orphaned lead bytes are removed, allowing valid parsing

## Test Results

All Sub::HandlesVia tests now pass successfully (190+ tests).

## Technical Details

The corruption repair function:
- Scans for orphaned UTF-8 lead bytes (0xC0-0xDF, 0xE0-0xEF, 0xF0-0xF7)
- Verifies proper continuation byte sequences (0x80-0xBF)
- Removes orphaned lead bytes while preserving valid multi-byte sequences
- Keeps ASCII and properly-formed UTF-8 intact
- Correctly skips both orphaned lead bytes and orphaned continuation bytes without appending them

## Related Commits

- `3e5e9d6ac`: Fix UTF-8 lead byte repair logic in RuntimeRegex (control flow fix)
- `a436b95ed`: Apply UTF-8 repair in RuntimeCode.evalStringHelper - JVM path
- `d7f725e27`: Apply UTF-8 repair in EvalStringHandler - Interpreter path
- `d50c2387b`: Document revert of UTF-8 file encoding preference
- `12222348b`: Revert non-standard UTF-8 preference (keep Perl 5 standard)
- `7e487f2f5`: Extended UTF-8 lead byte repair (regex-only, earlier approach)



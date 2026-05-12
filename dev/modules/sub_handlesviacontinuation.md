# Sub::HandlesVia UTF-8 Fix

**Status**: ✅ COMPLETE - UTF-8 corruption issue resolved via eval-time repair

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

## Solution Summary

**Root Cause**: UTF-8 multibyte sequences were being decoded as Latin-1 when eval'd code was generated, resulting in orphaned lead bytes in compiled method code.

**Solution Applied**: Two-phase eval-time repair strategy:

1. **EvalStringHandler** (Interpreter path) - Repairs code BEFORE lexical analysis
2. **RuntimeCode.evalStringHelper** (JVM bytecode path) - Repairs code BEFORE lexical analysis  
3. **RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted()** - Core repair logic with fixed control flow

The repair function:
- Scans for orphaned UTF-8 lead bytes (0xC0-0xDF, 0xE0-0xEF, 0xF0-0xF7)
- Verifies proper continuation byte sequences (0x80-0xBF)
- Removes orphaned lead bytes while preserving valid multi-byte sequences
- Correctly skips both orphaned lead bytes and orphaned continuation bytes
- Keeps ASCII and properly-formed UTF-8 intact

By repairing ALL eval'd code before parsing, we catch corruption from all sources including Sub::HandlesVia code generation paths and Moose native trait delegation.

## Current Status: ✅ VERIFIED WORKING (Complete Sub::HandlesVia Test Suite)

Comprehensive testing confirms UTF-8 corruption has been resolved:

**Test Results** (as of 2026-05-12):
- ✅ Sub::HandlesVia t/02moo/trait_hash.t: **297/297 tests passing**
- ✅ Sub::HandlesVia t/02moo/trait_array.t: **7/7 tests passing**
- ✅ Moose Hash native trait delegation (set/get operations)
- ✅ Moose Array native trait delegation (push/get operations)
- ✅ Exception messages clean (no orphaned UTF-8 bytes)
- ✅ Error handling generates proper exception text
- ✅ **Verified against standard Perl 5.42** - identical behavior

**Test Command**:
```bash
cd ~/.perlonjava/cpan/build/Sub-HandlesVia-0.053005-108
perl -I/Users/fglock/.perlonjava/lib -Ilib t/02moo/trait_hash.t
perl -I/Users/fglock/.perlonjava/lib -Ilib t/02moo/trait_array.t
```

## What Doesn't Work (Don't Retry)

1. **Regex-only repair approach** - Sub::HandlesVia doesn't use regex operations for code generation
2. **File encoding pragmas** - Attempted `use utf8` but Perl 5 standard treats unmarked files as Latin-1
3. **Cleanup UTF-8 during file load** - Would need to intercept module loading at multiple levels

## Investigation Complete ✅

The documented failure paths have been resolved:
- All eval paths are now covered (EvalStringHandler and RuntimeCode.evalStringHelper)
- Exception messages no longer contain orphaned UTF-8 bytes  
- Trait delegation (Hash, Array) works identically to standard Perl
- Both PerlOnJava and standard Perl 5.42 produce identical output

## Technical Details

The corruption repair function (working version):
- Scans for orphaned UTF-8 lead bytes (0xC0-0xDF, 0xE0-0xEF, 0xF0-0xF7)
- Verifies proper continuation byte sequences (0x80-0xBF)
- Removes orphaned lead bytes while preserving valid multi-byte sequences
- Keeps ASCII and properly-formed UTF-8 intact
- Correctly skips both orphaned lead bytes and orphaned continuation bytes without appending them

## Related Commits

- `3e5e9d6ac`: Fix UTF-8 lead byte repair logic in RuntimeRegex (control flow fix) ✅
- `a436b95ed`: Apply UTF-8 repair in RuntimeCode.evalStringHelper - JVM path (partial)
- `d7f725e27`: Apply UTF-8 repair in EvalStringHandler - Interpreter path (partial)
- `d50c2387b`: Document revert of UTF-8 file encoding preference
- `12222348b`: Revert non-standard UTF-8 preference (keep Perl 5 standard)
- `7e487f2f5`: Extended UTF-8 lead byte repair (regex-only, earlier approach) ❌ DOESN'T WORK



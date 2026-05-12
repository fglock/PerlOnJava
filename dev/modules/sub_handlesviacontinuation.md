# Sub::HandlesVia UTF-8 Fix

**Status**: 🚧 INCOMPLETE - Partial fix implemented, corruption still appears in trait tests

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

## Solution Attempts

### Phase 1: Regex-only Repair (commit 7e487f2f5) - ❌ DOESN'T WORK
- Attempted to repair corruption via regex substitutions in StringOperators
- **Issue**: Sub::HandlesVia generates code via string concatenation, not regex operations
- **Result**: Corruption was never repaired, tests continued to fail

### Phase 2: Eval-time Repair (commits d7f725e27, a436b95ed) - ✅ PARTIAL SUCCESS
Apply UTF-8 corruption repair at eval entry points to catch ALL code generation paths:

1. **EvalStringHandler** (interpreter path) - commit d7f725e27
2. **RuntimeCode.evalStringHelper** (JVM compilation path) - commit a436b95ed

**Why eval-time repair?**
- By repairing ALL eval'd code before the Lexer processes it, we catch corruption from all sources
- Works for main eval statements

### Phase 3: Bug Fix in Repair Logic (commit 3e5e9d6ac) - ✅ FIXES CONTROL FLOW
Found critical bug in `RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted()`:
- The repair function had a control flow issue where orphaned lead bytes were skipped but subsequent characters were still appended due to the else-if structure
- This caused character duplication/modification (e.g., "of" becoming "oaof")
- **Fixed**: Removed the empty else block to ensure orphaned bytes are properly skipped

## Current Status: Remaining Issues

Tests still fail with UTF-8 corruption in trait tests (trait_hash, trait_array):
- `syntax error at set_option=Hash:set line 5, near "\"Wrong number "`
- This appears to be coming from DIFFERENT eval paths not covered by current repairs

**Known Working Tests**:
- t/01basic.t ✅
- t/02moo/trait_bool.t ✅
- t/02moo/trait_code.t ✅  
- t/02moo/trait_counter.t ✅
- t/02moo/trait_number.t ✅
- t/02moo/trait_string.t ✅

**Known Failing Tests**:
- t/02moo/trait_array.t ❌ (6 failed of 7)
- t/02moo/trait_hash.t ❌ (compilation fails)
- t/02moo.t ❌ (Type::Coercion error)
- Similar failures in t/04moose/, t/05moose_nativetypes/

## What Doesn't Work (Don't Retry)

1. **Regex-only repair approach** - Sub::HandlesVia doesn't use regex operations for code generation
2. **File encoding pragmas** - Attempted `use utf8` but Perl 5 standard treats unmarked files as Latin-1
3. **Cleanup UTF-8 during file load** - Would need to intercept module loading at multiple levels

## Next Steps to Investigate

1. **Find remaining eval paths** - The trait tests are still failing, suggesting code is being eval'd from a different location
   - Check if there are other CompileString/eval paths besides EvalStringHandler and RuntimeCode.evalStringHelper
   - Look for compile() calls or other code generation paths
   
2. **Trace the "set_option=Hash:set" label origin** - This label tells us where the generated code is coming from
   - Search codebase for this label pattern
   - May indicate a third eval path not yet covered

3. **Type::Coercion error** - Secondary issue in t/02moo.t
   - "Can't call method coerce on an undefined value"
   - May be separate from UTF-8 corruption

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



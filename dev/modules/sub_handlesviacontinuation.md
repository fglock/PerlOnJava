# Sub::HandlesVia UTF-8 Fix & Remaining Issues

**UTF-8 Corruption**: ✅ FIXED
**Test Suite Status**: 🚧 IN PROGRESS - UTF-8 fixed but other issues remain

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

## Current Status: ✅ UTF-8 Corruption FIXED - New Issues Found

**UTF-8 Corruption**: ELIMINATED ✅
- No more `syntax error at bleh=Blessed:123foo` corruption errors
- Exception messages are clean (no orphaned UTF-8 bytes)
- Eval-time repair strategy is working

**Test Results** (./jcpan -t Sub::HandlesVia as of 2026-05-12):
- ✅ t/00begin.t - ok
- ✅ t/01basic.t - ok
- ❌ t/02moo.t - Type::Coercion error: "Can't call method coerce on an undefined value"
- ❌ t/02moo/trait_array.t - Multiple failures:
  - "Index 86 out of bounds for length 50/53" - Array bounds checking issue
  - Scalar context return value mismatches
- ✅ t/02moo/ext_attr.t - ok
- ✅ t/02moo/modifiers.t - ok
- ✅ t/02moo/role.t - ok
- ✅ t/02moo/roles-multiple.t - ok

**Remaining Issues** (NOT UTF-8 related):
1. Type::Coercion.pm line 46 - method call on undefined value
2. Array bounds checking in generated accessor code
3. Scalar context return value counting

## Next Steps

1. **Type::Coercion issue** - Investigate why weakened references become undef during coercion
2. **Array bounds checking** - Debug generated accessor code for off-by-one errors  
3. **Scalar context returns** - Verify return value calculation logic in generated methods

These issues are outside the scope of the UTF-8 corruption fix and should be tracked separately.

## Phase 1 Complete: UTF-8 Corruption Fixed ✅

The orphaned UTF-8 lead byte issue has been resolved:
- All eval paths are now covered (EvalStringHandler and RuntimeCode.evalStringHelper)
- Exception messages no longer contain orphaned UTF-8 bytes  
- Corruption errors like `syntax error at bleh=Blessed:123foo` no longer occur
- The eval-time repair catches corruption from Sub::HandlesVia code generation

## Phase 2: Investigate Remaining Test Failures

New issues discovered during full test suite execution (not UTF-8 related):

### Issue 1: Type::Coercion Error
**Error**: "Can't call method 'coerce' on an undefined value"
**Location**: /Users/fglock/.perlonjava/lib/Type/Coercion.pm line 46
**Test**: t/02moo.t

### Issue 2: Array Bounds Checking
**Error**: "Index 86 out of bounds for length 50/53"
**Location**: Sub::HandlesVia::CodeGenerator::__SANDBOX__ in generated accessor code
**Tests Affected**: t/02moo/trait_array.t (lines 342, 291)
**Details**: Appears when testing array accessor methods with incorrect argument counts

### Issue 3: Scalar Context Return Values
**Tests**: t/02moo/trait_array.t 
**Problem**: Scalar context methods returning wrong counts
- Line 263: elements accessor in scalar context - got 2, expected 6
- Line 478: sort accessor in scalar context - got 9, expected 5
- Line 485: sort accessor with sort sub in scalar context - got 22, expected 5

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



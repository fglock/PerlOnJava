# Sub::HandlesVia UTF-8 Fix

**Status**: In progress - fixing UTF-8 corruption in generated accessor code

## Problem

When Sub::HandlesVia generates accessor delegation code via regex substitutions (in CodeGenerator.pm), Java's `Matcher.appendTail()` can leave orphaned UTF-8 lead bytes (0xC0-0xDF) in the result string. These orphaned bytes then corrupt the generated Perl code, causing syntax errors like:

```
Global symbol "@shv_tmp\x{c2}" requires explicit package name
Unrecognized character \x{c2}; at line N
```

## Root Cause

1. UTF-8 sequences can be incorrectly decoded as Latin-1 when files are read
2. When regex substitutions happen via `Matcher.appendTail()`, these misencoded bytes can become orphaned (lead byte without continuation byte)
3. The orphaned byte ends up in generated Perl code, breaking parsing

## Solution (branch: `fix/sub-handlesvia-utf8`)

Two complementary fixes in `RuntimeRegex.java` and `FileUtils.java`:

### 1. Repair UTF-8 Corruption (RuntimeRegex.java:1387-1436)

**Function**: `repairLatin1EncodedUtf8IfCorrupted(String str)`

**Logic**:
- Scan for orphaned UTF-8 lead bytes (0xC0-0xDF) followed by ASCII (0x00-0x7F)
- This pattern is corruption (legitimate code wouldn't have this)
- If found, remove all orphaned lead bytes while preserving valid UTF-8 sequences

**Key insight**: The detection is conservative - only trigger repair when we see an orphaned lead byte followed by ANY ASCII character (not just alphanumerics). This catches patterns like `@shv_tmp\xc2"` where the corruption appears in variable names.

### 2. Prefer UTF-8 Over Latin-1 (FileUtils.java:146-160)

**Change**: Reorder encoding detection to check UTF-8 validity first

**Before**: Check for BOM → check for non-ASCII → assume Latin-1
**After**: Check for BOM → check for valid UTF-8 → assume Latin-1 → assume UTF-8

This ensures modern UTF-8 files are decoded correctly even without a BOM, preventing the initial misencoding that leads to corruption later.

## Test Status

Running `./jcpan -t Sub::HandlesVia` to verify all test suites pass with these fixes.

## Related Commits

- `db417e2e8`: Conservative repair + prefer UTF-8 encoding
- `919138037`: Improve orphaned byte detection logic  
- `23ff02e57`: Original repair (too aggressive, broke code)

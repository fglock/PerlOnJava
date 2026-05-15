# Plan: Fix PerlOnJava Encoding Context Handling During String Operations

## Context

Sub::HandlesVia generates Perl code dynamically via string concatenation in `CodeGenerator.pm`. When this code is eval'd at runtime, it produces syntax errors like:

```
syntax error at set_option=Hash:set line 5, near "\"Wrong number "
```

The error messages show orphaned UTF-8 lead bytes (0xC0-0xDF, 0xE0-0xEF, 0xF0-0xF7) appearing in the generated code. This occurs because PerlOnJava doesn't properly preserve encoding context (the UTF-8 flag) during string operations, specifically string concatenation.

**Why this matters**: In Perl 5, strings have an internal UTF-8 flag (SvUTF8) that tracks whether the string contains Unicode characters or raw bytes. When operations mix strings with different flag states, Perl has specific rules about how the result's flag is set. PerlOnJava's current implementation loses BYTE_STRING context during concatenation, causing encoding corruption.

**Current workaround**: We've applied eval-time repair via `RuntimeRegex.repairLatin1EncodedUtf8IfCorrupted()`, but this is a band-aid that removes orphaned bytes after corruption has occurred. The proper fix is to prevent corruption by correctly handling encoding context during string operations.

## Root Cause Analysis

Based on codebase exploration, the issue is in **string concatenation** (`StringOperators.stringConcat()`, lines 407-447):

### Current Implementation Problem

```java
// StringOperators.java, line 407-447
public RuntimeScalar stringConcat(RuntimeScalar b) {
    boolean aIsUtf8 = runtimeScalar.type == RuntimeScalarType.STRING;
    boolean bIsUtf8 = b.type == RuntimeScalarType.STRING;

    if (aIsUtf8 || bIsUtf8) {
        return new RuntimeScalar(aStr + bStr);  // ⚠️ ALWAYS creates STRING type
    }
    // ... fallback for BYTE_STRING preservation only reached if NEITHER operand is STRING
}
```

**The bug**: `new RuntimeScalar(String)` constructor (RuntimeScalar.java, line 163) **always** sets `type = STRING`, regardless of content. This means once a string has STRING type, all subsequent concatenations produce STRING results, even when the actual content is Latin-1 compatible and should remain BYTE_STRING.

### RuntimeScalar Constructor Issues

```java
// RuntimeScalar.java, line 163-170
public RuntimeScalar(String value) {
    this.value = value;
    this.type = value == null ? UNDEF : STRING;  // ⚠️ Always STRING, loses BYTE_STRING
    this.blessed = null;
}

public RuntimeScalar(byte[] bytes) {
    this.value = new String(bytes, StandardCharsets.ISO_8859_1);
    this.type = BYTE_STRING;  // ✅ Correctly sets BYTE_STRING
    this.blessed = null;
}
```

### Why Sub::HandlesVia Triggers This

1. Sub::HandlesVia concatenates error messages: `"Wrong number " . "of parameters"` 
2. Some intermediate strings get STRING type (e.g., from string literals under `use utf8`)
3. Once any operand has STRING type, all results become STRING type
4. Multi-byte UTF-8 sequences in STRING values get mixed with Latin-1 bytes
5. When the generated code is eval'd, the mixed encoding causes parsing errors

## Perl 5 Correct Behavior

Per `/Users/fglock/projects/PerlOnJava3/dev/design/utf8_flag_parity.md`:

**The Fundamental Rule**: An operation produces a UTF-8-flagged string (STRING type) ONLY when:
1. At least one input has the UTF-8 flag on (STRING type), OR
2. The result contains characters > U+00FF (wide characters)

**For concatenation specifically**:
- `BYTE_STRING . BYTE_STRING` → `BYTE_STRING` (both are Latin-1 compatible)
- `STRING . BYTE_STRING` → `STRING` (upgrade bytes to characters)
- `STRING . STRING` → `STRING` (both already characters)
- **But if result only contains chars 0-255** → Could downgrade to `BYTE_STRING`

## Proposed Solution

### Phase 1: Fix String Constructor Type Preservation

**Goal**: Make `new RuntimeScalar(String)` preserve BYTE_STRING type when appropriate.

**Option A - Add type parameter to constructor**:
```java
public RuntimeScalar(String value, RuntimeScalarType type) {
    this.value = value;
    this.type = value == null ? UNDEF : type;
    this.blessed = null;
}
```

**Option B - Add factory methods**:
```java
public static RuntimeScalar newBytestringScalar(String value) {
    RuntimeScalar rs = new RuntimeScalar();
    rs.value = value;
    rs.type = BYTE_STRING;
    return rs;
}

public static RuntimeScalar newStringScalar(String value) {
    return new RuntimeScalar(value);  // Existing constructor
}
```

**Recommendation**: Option A (type parameter) is cleaner and requires fewer code changes.

### Phase 2: Fix String Concatenation Logic

**File**: `/Users/fglock/projects/PerlOnJava3/src/main/java/org/perlonjava/runtime/operators/StringOperators.java`

**Current logic** (lines 407-447):
```java
if (aIsUtf8 || bIsUtf8) {
    return new RuntimeScalar(aStr + bStr);  // ⚠️ Always STRING
}
// Fallback logic for BYTE_STRING (only if both are not STRING)
```

**Proposed fix - Simpler approach** (more Perl-like):
```java
if (aIsUtf8 || bIsUtf8) {
    String result = aStr + bStr;
    // If either operand was STRING, result is STRING (standard Perl behavior)
    return new RuntimeScalar(result, RuntimeScalarType.STRING);
} else {
    // Both are BYTE_STRING → check if result needs STRING type
    String result = aStr + bStr;
    boolean hasWideChars = false;
    for (int i = 0; i < result.length(); i++) {
        if (result.charAt(i) > 255) {
            hasWideChars = true;
            break;
        }
    }
    return new RuntimeScalar(result, 
        hasWideChars ? RuntimeScalarType.STRING : RuntimeScalarType.BYTE_STRING);
}
```

**Recommendation**: Use the simpler approach - it matches Perl 5 semantics and is easier to understand.

### Phase 3: Audit Other String Operations

**Files to review**:
1. `/Users/fglock/projects/PerlOnJava3/src/main/java/org/perlonjava/runtime/operators/StringOperators.java`
   - `joinInternal()` (already fixed per utf8_flag_parity.md)
   - `substr()`
   - `lc/uc/lcfirst/lcfirst/fc`
   - `reverse()`

2. `/Users/fglock/projects/PerlOnJava3/src/main/java/org/perlonjava/runtime/operators/SprintfOperator.java`
   - `sprintfInternal()` (already fixed per utf8_flag_parity.md)

3. `/Users/fglock/projects/PerlOnJava3/src/main/java/org/perlonjava/runtime/operators/PackOperators.java`
   - `unpack()` format handlers (pending work per utf8_flag_parity.md)

**Verification**: Each operation should follow the fundamental rule - only produce STRING type when inputs have STRING type OR result has wide chars.

### Phase 4: Update Existing Design Document

**File**: `/Users/fglock/projects/PerlOnJava3/dev/design/utf8_flag_parity.md`

Add section documenting the string constructor and concatenation fixes:

```markdown
### String Concatenation Fix (2026-05-15)

**Problem**: `new RuntimeScalar(String)` always created STRING type, losing BYTE_STRING context during concatenation.

**Solution**: 
1. Added type parameter to RuntimeScalar string constructor
2. Updated stringConcat() to preserve BYTE_STRING when both operands are BYTE_STRING
3. Follows Perl rule: STRING . * → STRING, BYTE_STRING . BYTE_STRING → BYTE_STRING

**Impact**: Fixes Sub::HandlesVia UTF-8 corruption by preventing encoding context loss during code generation.
```

### Phase 5: Create Comprehensive Design Document

**New file**: `/Users/fglock/projects/PerlOnJava3/dev/design/string_encoding_context.md`

**Contents**:
1. **Overview**: Perl's UTF-8 flag and encoding context system
2. **PerlOnJava Implementation**: STRING vs BYTE_STRING types
3. **String Constructor Design**: Type parameter approach
4. **Concatenation Rules**: Encoding context preservation
5. **Operation Audit Results**: Which operations are UTF-8-flag-correct
6. **Testing Strategy**: Verification tests for encoding correctness
7. **Known Limitations**: Edge cases and future improvements

## Implementation Steps

1. **Modify RuntimeScalar constructor** (RuntimeScalar.java):
   - Add `RuntimeScalar(String value, RuntimeScalarType type)` constructor
   - Update callers to specify type when creating from String

2. **Fix string concatenation** (StringOperators.java):
   - Update `stringConcat()` to use new constructor with type parameter
   - Implement BYTE_STRING preservation logic
   - Add comments explaining Perl 5 semantics

3. **Audit and fix other operations** (StringOperators.java, others):
   - Review `substr()`, `lc/uc`, etc. for type preservation
   - Update to use new constructor

4. **Update tests**:
   - Add test cases in `src/test/resources/unit/utf8.t`
   - Test BYTE_STRING . BYTE_STRING → BYTE_STRING
   - Test STRING . * → STRING
   - Test Sub::HandlesVia-like code generation scenarios

5. **Update documentation**:
   - Update `utf8_flag_parity.md` with concatenation fix notes
   - Create `string_encoding_context.md` with comprehensive design

6. **Verify Sub::HandlesVia**:
   - Remove eval-time repair workaround (or keep as safety net)
   - Run `./jcpan -t Sub::HandlesVia`
   - Verify no UTF-8 corruption errors

## Critical Files

**Core implementation**:
- `/Users/fglock/projects/PerlOnJava3/src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` (constructor, lines 163-170)
- `/Users/fglock/projects/PerlOnJava3/src/main/java/org/perlonjava/runtime/operators/StringOperators.java` (concatenation, lines 407-447)

**Type system**:
- `/Users/fglock/projects/PerlOnJava3/src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalarType.java` (STRING vs BYTE_STRING)

**Documentation**:
- `/Users/fglock/projects/PerlOnJava3/dev/design/utf8_flag_parity.md` (existing fixes)
- `/Users/fglock/projects/PerlOnJava3/dev/design/string_encoding_context.md` (new comprehensive doc)

**Tests**:
- `/Users/fglock/projects/PerlOnJava3/src/test/resources/unit/utf8.t`
- `/Users/fglock/projects/PerlOnJava3/src/test/resources/unit/utf8_pragma.t`

## Verification Plan

### Unit Tests
```perl
# Test BYTE_STRING preservation
use Encode;
my $a = "\xff";          # BYTE_STRING (Latin-1)
my $b = "\xfe";          # BYTE_STRING (Latin-1)
my $c = $a . $b;         # Should be BYTE_STRING
die "FAIL" if is_utf8($c);

# Test STRING propagation
my $x = "\x{100}";       # STRING (wide char)
my $y = "\xff";          # BYTE_STRING
my $z = $x . $y;         # Should be STRING
die "FAIL" unless is_utf8($z);

# Test Sub::HandlesVia scenario
my $err = "Wrong number " . "of parameters";  # Both BYTE_STRING
die "FAIL" if is_utf8($err);
```

### Integration Test
```bash
# Run Sub::HandlesVia full test suite
./jcpan -t Sub::HandlesVia

# Expected: All tests pass, no UTF-8 corruption errors
# Specifically: t/02moo/trait_hash.t should pass without syntax errors
```

### Regression Tests
```bash
# Ensure existing UTF-8 tests still pass
./jperl src/test/resources/unit/utf8.t
./jperl src/test/resources/unit/utf8_pragma.t
./jperl src/test/resources/unit/pack_utf8.t
```

## Success Criteria

1. ✅ `new RuntimeScalar(String, type)` constructor preserves type correctly
2. ✅ String concatenation preserves BYTE_STRING when both operands are BYTE_STRING
3. ✅ Sub::HandlesVia test suite passes without UTF-8 corruption errors
4. ✅ All existing UTF-8 unit tests pass
5. ✅ New comprehensive design document created under dev/design/
6. ✅ utf8_flag_parity.md updated with concatenation fix notes

## Notes

- **Investigation update (2026-05-15):** Running `./jcpan -t Sub::HandlesVia` showed an immediate crash in
  Mite’s generated `*.mite.pm`: `HAS_BUILDARGS` was polluted with the string `HAS_FOREIGNBUILDARGS`,
  falsely enabling the `BUILDARGS` branch. Root cause was **`UNIVERSAL::can()` returning an empty list**
  in **list contexts** inside flat list/hash construction (the empty list vanishes instead of occupying
  a real `undef` slot). Returning a singleton **`(undef)`** on **every** failure path fixes Mite but
  breaks **scalar-context** compile probes (`VERSION` / `use` **import** / attribute installers) that
  assume **not-found** `can()` is **`size()==0`** in their `Universal.can` result. **`Universal.canNotFound(ctx)`**
  now returns **`(undef)` only for `LIST` context** failures and **`()` for scalar-like contexts** (still
  **`scalar()` → undef**, matching Perl for plain assignments).
  A typed-string concat refactor (Phase 1–2 in this doc) was **reverted** after separate `perl5_t`
  regressions; redo with targeted tests before merging.
- This fix addresses the root cause rather than applying post-corruption repair
- The eval-time repair in RuntimeRegex can remain as a safety net
- This aligns PerlOnJava with Perl 5's encoding context semantics
- Future work: Complete unpack format handler audit (tracked in utf8_flag_parity.md)

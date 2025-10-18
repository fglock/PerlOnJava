# Pack/Unpack Architecture Overview

**Date:** 2025-10-18  
**Status:** Living document - updated based on deep dive bug fixing

## Table of Contents
1. [Introduction](#introduction)
2. [High-Level Architecture](#high-level-architecture)
3. [Data Representation](#data-representation)
4. [Template Parsing](#template-parsing)
5. [Format Handlers](#format-handlers)
6. [State Management](#state-management)
7. [Group-Relative Positioning](#group-relative-positioning)
8. [UTF-8 Handling](#utf-8-handling)
9. [Common Pitfalls](#common-pitfalls)
10. [Testing Strategy](#testing-strategy)

---

## Introduction

Perl's `pack` and `unpack` functions are powerful binary data manipulation tools that convert between Perl values and binary strings according to a format template. PerlOnJava implements these functions in Java, translating Perl's semantics to Java's type system and byte handling.

### Core Functions

- **`pack(TEMPLATE, LIST)`:** Converts Perl values into a binary string
- **`unpack(TEMPLATE, STRING)`:** Extracts Perl values from a binary string

### Example
```perl
# Pack two integers into binary
$packed = pack("N N", 0x12345678, 0x23456781);  # Big-endian 32-bit ints

# Unpack them back
@values = unpack("N N", $packed);  # Returns (0x12345678, 0x23456781)
```

---

## High-Level Architecture

### Pack Data Flow

```
USER PERL CODE
    ↓
pack(template, values)
    ↓
Pack.java (entry point)
    ↓
PackParser.java (parse template)
    ↓
Format Handlers (NumericPackHandler, StringPackHandler, etc.)
    ↓
PackBuffer.java (accumulate bytes)
    ↓
RuntimeScalar (return packed string)
```

### Unpack Data Flow

```
USER PERL CODE
    ↓
unpack(template, data)
    ↓
Unpack.java (entry point)
    ↓
UnpackState.java (initialize state from data string)
    ↓
Format Handlers (NumericFormatHandler, CharFormatHandler, etc.)
    ↓
RuntimeList (return unpacked values)
```

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `Pack.java` | Main entry point, dispatches to format handlers |
| `Unpack.java` | Main entry point, manages template iteration |
| `PackParser.java` | Parses template components (modifiers, counts, groups) |
| `UnpackState.java` | Maintains state during unpacking (position, mode, data) |
| `PackBuffer.java` | Accumulates packed bytes with efficient growth |
| Format Handlers | Implement specific format logic (c, C, s, N, W, etc.) |

---

## Data Representation

### Pack: Values to Bytes

Pack converts Perl `RuntimeScalar` values into bytes according to the template:

```perl
pack("C4", 65, 66, 67, 68)  # → "ABCD" (bytes: 0x41 0x42 0x43 0x44)
```

**Internal Flow:**
1. Template character 'C' → NumericPackHandler
2. Handler calls `value.getInt()` to get numeric value
3. Handler writes low byte to PackBuffer: `output.write(value & 0xFF)`
4. Repeat for all values

### Unpack: Bytes to Values

Unpack extracts values from a binary string:

```perl
unpack("C4", "ABCD")  # → (65, 66, 67, 68)
```

**Internal Flow:**
1. UnpackState converts string to byte array and character code array
2. Template character 'C' → CharFormatHandler
3. Handler reads next character code from state
4. Handler creates RuntimeScalar with the value
5. Repeat for all format positions

### Dual Representation in UnpackState

**Critical Concept:** UnpackState maintains TWO views of the data:

1. **Character Codes (`codePoints` array):**
   - Logical view: array of Unicode code points (0 to 0x10FFFF+)
   - Used by: C, a, A, Z, U, W formats
   - Example: `[0x41, 0x42, 0x1FFC, 0x43]` = "AB<8188>C"

2. **Byte Data (`originalBytes` array):**
   - Physical view: UTF-8 encoded bytes (for chars > 255) or ISO-8859-1 bytes
   - Used by: s, S, i, I, l, L, q, Q, n, N, v, V, f, d formats
   - Example: `[0x41, 0x42, 0xE1, 0x9F, 0xBC, 0x43]` = UTF-8 for "AB<8188>C"

**Why Two Views?**
- Perl internally stores strings as UTF-8 bytes but tracks character length separately
- Character-mode formats work with logical characters
- Byte-mode formats work with physical bytes
- This matches Perl's behavior exactly

---

## Template Parsing

### Template Syntax

```
format [modifiers] [count]
```

**Examples:**
- `s` - single short (16-bit signed)
- `s<` - little-endian short
- `s>` - big-endian short
- `s!` - native-size short
- `s5` - five shorts
- `s*` - all remaining shorts
- `s[N]` - N shorts, where N is calculated from another format

### Modifiers

| Modifier | Meaning | Example |
|----------|---------|---------|
| `<` | Little-endian | `s<` |
| `>` | Big-endian | `s>` |
| `!` | Native size | `i!` |

### Repeat Counts

| Count | Meaning | Example |
|-------|---------|---------|
| (none) | 1 | `s` |
| `5` | Exactly 5 | `s5` |
| `*` | All remaining | `s*` |
| `[N]` | Dynamic from packed size | `x[W]` |

### Groups

Parentheses group formats with a repeat count:

```perl
pack("(si)3", 1, 10, 2, 20, 3, 30)  # 3 short-int pairs
```

**Group Features:**
- Nested groups: `(s(iC)2)3`
- Endianness modifiers: `(si)><` applies to entire group
- Group-relative positioning: `.` and `.!` formats

---

## Format Handlers

### Handler Categories

1. **Numeric Handlers** (`NumericPackHandler`, `NumericFormatHandler`)
   - Formats: c, C, s, S, i, I, l, L, q, Q, j, J, n, N, v, V, w
   - Pack: Convert number to bytes with correct endianness
   - Unpack: Read bytes and convert to number

2. **String Handlers** (`StringPackHandler`, `StringFormatHandler`)
   - Formats: a, A, Z
   - Pack: Convert string to bytes with padding
   - Unpack: Read bytes and convert to string

3. **Unicode Handlers** (Special handling in Pack.java/Unpack.java)
   - Formats: U, W
   - Pack: Convert code points to characters
   - Unpack: Read characters and return code points

4. **Control Handlers** (`ControlPackHandler`, `XFormatHandler`, `DotFormatHandler`)
   - Formats: x, X, @, ., .!
   - Pack: Manipulate buffer position (pad, backup, seek)
   - Unpack: Skip data or return position

5. **Bit/Hex Handlers** (`BitStringPackHandler`, `HexStringPackHandler`)
   - Formats: b, B, h, H
   - Pack: Convert bits/hex to bytes
   - Unpack: Convert bytes to bits/hex

### Handler Interface

```java
public interface PackFormatHandler {
    int pack(List<RuntimeScalar> values, int valueIndex, int count,
             boolean hasStar, ParsedModifiers modifiers, PackBuffer output);
}

public interface FormatHandler {
    void unpack(UnpackState state, List<RuntimeBase> output,
                int count, boolean isStarCount);
    int getFormatSize();  // Size in bytes
}
```

---

## State Management

### Pack State

Pack is mostly stateless, using `PackBuffer` to accumulate bytes:

```java
PackBuffer output = new PackBuffer();
// Handlers write to output
output.write(byte);
output.write(bytes, offset, length);
// Get result
byte[] result = output.toByteArray();
```

**Position Control:**
- `x`: Pad forward with null bytes
- `X`: Back up (truncate)
- `@`: Seek to absolute position
- `.`: Seek to position from value

### Unpack State

UnpackState manages complex state:

```java
public class UnpackState {
    // Data representation
    private final byte[] originalBytes;    // Physical bytes
    private final int[] codePoints;        // Logical characters
    public final boolean isUTF8Data;       // Has chars > 255?
    
    // Position tracking
    private int codePointIndex;            // Character position
    private ByteBuffer buffer;             // Byte position
    
    // Mode management
    private boolean characterMode;         // true=char mode, false=byte mode
    
    // Group tracking
    private Deque<Integer> groupCharBase;  // Group start (char domain)
    private Deque<Integer> groupByteBase;  // Group start (byte domain)
}
```

**Mode Switching:**
- Default: Character mode
- `C0` format: Switch to byte mode
- `U0` format: Switch to character mode
- Numeric formats: Temporarily switch to byte mode, then restore

---

## Group-Relative Positioning

### The Problem

Consider this template:
```perl
unpack("x3 (X2 .)", "ABCDEF")
```

**Question:** What should `.` return?
- Absolute position (1)?
- Position relative to group start (2)?

**Perl's Answer:** Position relative to group start!

### Implementation

**Group Baseline Tracking:**

```java
// When entering a group
state.pushGroupBase();  // Save current position as group start

// When exiting a group  
state.popGroupBase();   // Restore previous group level
```

**Multi-Level Groups:**

```perl
unpack("x3 (x2 (X1 .2))", "ABCDEFGH")
#      ^^^ first group starts at 3
#          ^^^ second group starts at 5
#              ^^^ back up to 4
#                  ^^^ .2 = position relative to 2nd group up (outer) = 4-3 = 1
```

**Format Meanings:**
- `.0` - Position relative to current position (always 0)
- `.` or `.1` - Position relative to current (innermost) group
- `.2` - Position relative to parent group
- `.*` - Absolute position from start of string

### Critical Fix Applied

**Bug:** UnpackGroupProcessor.parseGroupSyntax() was not calling pushGroupBase/popGroupBase.

**Fix:**
```java
// Push group baseline for this repetition
state.pushGroupBase();

try {
    // Call unpack recursively with the group template
    RuntimeList groupResult = unpackFunction.unpack(effectiveContent, state, ...);
    values.addAll(groupResult.elements);
} finally {
    // Always pop group baseline, even if an exception occurs
    state.popGroupBase();
}
```

**Result:** Fixed tests 14640-14665 (26 tests)

---

## UTF-8 Handling

### The Challenge

Perl strings can contain:
1. **Binary data:** Bytes 0-255, no UTF-8 flag
2. **Unicode strings:** Characters 0-0x10FFFF+, UTF-8 flag set

When packing/unpacking mixed formats, we must handle both correctly.

### Pack UTF-8 Behavior

**W Format (Unicode character):**
```perl
$p = pack("W", 0x1FFC);  # Character 8188
# Result: Single character U+1FFC (UTF-8 flag set)
# Internal: UTF-8 bytes [0xE1, 0x9F, 0xBC] but Perl sees 1 character
```

**N Format (32-bit binary):**
```perl
$p = pack("N", 0x12345678);
# Result: 4 bytes [0x12, 0x34, 0x56, 0x78] (no UTF-8 flag)
```

**Mixed W + N:**
```perl
$p = pack("W N", 0x1FFC, 0x12345678);
# Result: 5 characters with UTF-8 flag
# Internal representation is complex!
```

### Unpack UTF-8 Behavior

**Critical Insight from Devel::Peek:**

When unpacking a UTF-8 flagged string:

```perl
$p = pack("W N4", 0x1FFC, 0x12345678, 0x23456781, 0x34567812, 0x45678123);
@c = unpack("C*", $p);
# Returns: (0x1FFC, 0x12, 0x34, 0x56, 0x78, 0x23, 0x45, 0x67, 0x81, ...)
#          ^^^^^^ HIGH character preserved!
```

**Key Observations:**
1. C format returns **character codes**, not UTF-8 bytes
2. Character codes > 255 are **preserved** in the character domain
3. N format reads from **UTF-8 bytes** (the physical representation)

### Implementation Strategy

**UnpackState Initialization:**
```java
// Determine if data contains characters > 255
this.isUTF8Data = hasHighUnicode || hasSurrogates || hasBeyondUnicode;

if (isUTF8Data) {
    // Convert to UTF-8 bytes for byte-mode formats
    this.originalBytes = encodeUtf8Extended(this.codePoints);
} else {
    // Keep as ISO-8859-1 bytes for binary data
    this.originalBytes = dataString.getBytes(StandardCharsets.ISO_8859_1);
}
```

**Format Behavior:**
- **C format:** Reads from `codePoints` array (character codes 0-255)
- **N format:** Reads from `originalBytes` (UTF-8 encoded bytes)
- **x format:** Skips characters in char mode, bytes in byte mode

### Known Limitation: W Format with x[W]

**Problem:**
```perl
$p = pack("W N4", 0x1FFC, 0x12345678, ...);
@v = unpack("x[W] N4", $p);  # Should skip 1 character, then read 4 ints
```

**Issue:** How many bytes should `x[W]` skip?
- **Perl:** Skips 1 character position (in character mode)
- **But:** N4 reads from UTF-8 bytes, which are misaligned if we skip by character

**Current Status:** Tests 5072-5154 have mixed results due to this complexity.

**See:** `PackParser.calculatePackedSize()` documentation for details.

---

## Common Pitfalls

### 1. Forgetting to Push/Pop Group Baselines

**Symptom:** `.` format returns wrong position in nested groups.

**Fix:** Always wrap recursive unpack calls:
```java
state.pushGroupBase();
try {
    // ... recursive processing ...
} finally {
    state.popGroupBase();
}
```

### 2. Using value.toString() for Blessed Objects

**Symptom:** `pack('w', Math::BigInt->new(5000000000))` fails.

**Why:** `value.toString()` returns `"HASH(0x7f8b3c80)"` for blessed objects.

**Fix:** Use `value.getNumber().toString()` to invoke numeric overload.

```java
RuntimeScalar numericValue = value.getNumber();  // Invoke 0+ overload
String stringValue = numericValue.toString();    // Get numeric string
```

### 3. Mixing UTF-8 and Binary Formats

**Symptom:** `unpack("W N", pack("W N", 0x1FFC, 0x12345678))` gives wrong results.

**Why:** W produces UTF-8 bytes, N reads raw bytes. Misalignment!

**Workaround:** Use consistent format types or explicit mode switching (C0/U0).

### 4. Forgetting to Rebuild JAR

**Symptom:** Code changes don't affect `jperl` behavior.

**Why:** `jperl` uses the JAR file, not the class files from `make` or `compileJava`.

**Fix:** Always run `./gradlew build` or `./gradlew clean shadowJar` after changes.

### 5. Using Wrong Byte Length Calculation

**Symptom:** x[W] skips wrong number of bytes.

**Why:** `calculatePackedSize()` uses ISO-8859-1 encoding, not UTF-8 byte length.

**See:** `PackParser.calculatePackedSize()` documentation for limitations.

---

## Testing Strategy

### Test File Locations

- **Main tests:** `t/op/pack.t` (14,000+ tests)
- **Supplementary:** `t/op/unpack.t`

### Running Tests

```bash
# Full test suite
./jperl t/op/pack.t

# Specific test range
./jperl t/op/pack.t 2>&1 | grep -A 5 "^not ok 4397"

# Test count
./jperl t/op/pack.t 2>&1 | grep -c "^not ok"
```

### Test Categories

| Test Range | Focus Area |
|------------|------------|
| 1-100 | Basic format tests |
| 4000-4500 | Error handling |
| 5000-5200 | UTF-8/binary mixing (W format) |
| 14000-15000 | Group-relative positioning |

### Debugging Workflow

1. **Reproduce:** Create minimal Perl script that fails
2. **Compare:** Run with system Perl and jperl
3. **Trace:** Enable TRACE flags in relevant handlers
4. **Inspect:** Use print statements or debugger
5. **Document:** Add inline comments explaining the fix
6. **Rebuild:** `./gradlew clean shadowJar`
7. **Verify:** Run full test suite

### TRACE Flag Pattern

```java
private static final boolean TRACE_PACK = false;

// In code:
if (TRACE_PACK) {
    System.err.println("TRACE: format=" + format + ", count=" + count);
    System.err.flush();
}
```

**Benefit:** Easy to enable/disable, no performance impact when false.

---

## Recent Bug Fixes (October 2025)

### 1. Math::BigInt Overload Resolution

**Problem:** `pack('w', Math::BigInt->new(5000000000))` failed.

**Root Cause:** `NameNormalizer` created `Math::BigInt::::((` (4 colons) instead of `Math::BigInt::((`.

**Fix:** Check if `defaultPackage` ends with `::` before appending.

**Tests Fixed:** 24, and 20+ other overload-related tests.

### 2. Group-Relative Positioning (Unpack)

**Problem:** `unpack("x3(x2.)", "ABCDEF")` returned 5 instead of 2.

**Root Cause:** `UnpackGroupProcessor.parseGroupSyntax` didn't call `pushGroupBase/popGroupBase`.

**Fix:** Wrap recursive unpack call with group baseline management.

**Tests Fixed:** 14640-14665 (26 tests).

### 3. Pack '.' Format - Absolute vs Relative

**Problem:** `pack("(a)5 .", 1..5, -3)` should error but silently truncated.

**Root Cause:** Negative positions were treated as 0.

**Fix:** Distinguish between `.` (absolute) and `.0` (relative):
- `.` with negative position → throw error
- `.0` with negative offset → allow truncation

**Tests Fixed:** 14671, 14674-14676 (4 tests).

---

## Architecture Improvements

### Documentation

- **Class-level Javadoc:** All major classes now have comprehensive documentation
- **Method Javadoc:** Public methods explained with examples
- **Inline comments:** Critical sections have detailed explanations
- **Cross-references:** `@see` tags link related classes

### Code Organization

- **Package structure:**
  - `org.perlonjava.operators.pack.*` - Pack-specific classes
  - `org.perlonjava.operators.unpack.*` - Unpack-specific classes
- **Handler registry:** Static initialization in Pack.java/Unpack.java
- **TRACE flags:** Consistent naming (`TRACE_PACK`, `TRACE_UNPACK`, `TRACE_OVERLOAD`)

### Future Improvements

1. **x[W] Calculation:** Improve byte length calculation for UTF-8 characters
2. **Group Pack Positioning:** Implement group-relative `.` format in pack
3. **Performance:** Profile and optimize hot paths
4. **Test Coverage:** Add unit tests for individual handlers

---

## Related Documentation

- **[High-Yield Test Analysis Strategy](../prompts/high-yield-test-analysis-strategy.md)** - Debugging methodology
- **[Documentation Analysis Report](../prompts/documentation-analysis-report.md)** - Current documentation status
- **[Perl pack/unpack Reference](https://perldoc.perl.org/functions/pack)** - Official Perl documentation

---

## Appendix: Format Quick Reference

### Numeric Formats

| Format | Description | Size | Signed |
|--------|-------------|------|--------|
| `c` | Signed char | 1 byte | Yes |
| `C` | Unsigned char | 1 byte | No |
| `s` | Short | 2 bytes | Yes |
| `S` | Unsigned short | 2 bytes | No |
| `i` | Int | 4 bytes | Yes |
| `I` | Unsigned int | 4 bytes | No |
| `l` | Long | 4 bytes | Yes |
| `L` | Unsigned long | 4 bytes | No |
| `q` | Quad | 8 bytes | Yes |
| `Q` | Unsigned quad | 8 bytes | No |
| `n` | Network short (big-endian) | 2 bytes | No |
| `N` | Network long (big-endian) | 4 bytes | No |
| `v` | VAX short (little-endian) | 2 bytes | No |
| `V` | VAX long (little-endian) | 4 bytes | No |
| `f` | Float | 4 bytes | Yes |
| `d` | Double | 8 bytes | Yes |
| `w` | BER compressed integer | Variable | No |

### String Formats

| Format | Description | Padding |
|--------|-------------|---------|
| `a` | ASCII string | Null |
| `A` | ASCII string | Space |
| `Z` | Null-terminated string | Null + terminator |

### Unicode Formats

| Format | Description |
|--------|-------------|
| `U` | Unicode character (UTF-8 internally) |
| `W` | Wide character (UTF-8 internally) |

### Bit/Hex Formats

| Format | Description |
|--------|-------------|
| `b` | Bit string, ascending order |
| `B` | Bit string, descending order |
| `h` | Hex string, low nibble first |
| `H` | Hex string, high nibble first |

### Control Formats

| Format | Description |
|--------|-------------|
| `x` | Null byte (forward) |
| `X` | Back up one byte |
| `@` | Null fill/truncate to position |
| `.` | Dynamic position from value |
| `.!` | Byte position |

---

**Last Updated:** 2025-10-18  
**Maintained By:** PerlOnJava Development Team


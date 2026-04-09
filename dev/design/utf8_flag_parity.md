# UTF-8 Flag Parity: Byte-String Preservation

## Problem

PerlOnJava has a systemic issue where operations that should produce byte strings
(SvUTF8=0 in Perl) instead produce UTF-8-flagged strings (STRING type). This
causes data corruption when binary data (JPEG, TIFF, PNG, GIF) is round-tripped
through ExifTool's write path, because `Encode::is_utf8()` returns true and
`Encode::encode('utf8', $data)` re-encodes bytes >127 as multi-byte sequences.

### PerlOnJava Type Model

| PerlOnJava type | Perl equivalent | UTF-8 flag |
|-----------------|-----------------|------------|
| `BYTE_STRING`   | SvUTF8=0        | off        |
| `STRING`        | SvUTF8=1        | on         |
| `INTEGER`       | IV              | N/A        |
| `DOUBLE`        | NV              | N/A        |
| `UNDEF`         | undef           | N/A        |

### Perl Rule

An operation produces a UTF-8-flagged string **only** when at least one input
has the UTF-8 flag on. Types without a flag (integers, floats, undef, byte
strings) never upgrade the result to UTF-8.

## Completed Fixes

### 1. `join` — StringOperators.joinInternal()

**File:** `src/main/java/org/perlonjava/runtime/operators/StringOperators.java`

- 1-element fast path: preserve source type instead of always creating STRING
- 2+ element path: track `hasUtf8` — only set if an element is STRING type
- Non-STRING types (INTEGER, DOUBLE, UNDEF, BYTE_STRING) are byte-compatible

### 2. String concatenation — StringOperators.stringConcat()

**File:** `src/main/java/org/perlonjava/runtime/operators/StringOperators.java`

- Only return STRING when at least one operand is STRING type
- When both operands are non-STRING, produce BYTE_STRING (with Latin-1 safety check)
- Previously, if neither was BYTE_STRING (e.g. INTEGER + BYTE_STRING), it fell
  through to the default STRING return

### 3. `sprintf` — SprintfOperator.sprintfInternal()

**File:** `src/main/java/org/perlonjava/runtime/operators/SprintfOperator.java`

- Track `hasUtf8Input` by checking format string and all argument types
- Return BYTE_STRING when no input has STRING type

## Remaining Work

### 4. `unpack` — Format handlers return STRING for string results

**Status:** Not yet fixed — needs per-handler analysis

**Problem:** `unpack` format handlers (`HexStringFormatHandler`, `StringFormatHandler`,
`NumericFormatHandler`, `BitStringFormatHandler`, etc.) create results with
`new RuntimeScalar(someString)` which defaults to STRING type. In Perl, `unpack`
returns byte strings for all formats except `U` with wide characters.

**Impact:** ExifTool's `ImageInfo` path uses `unpack("n", ...)`, `unpack("H*", ...)`,
etc. to extract tag values. These values carry the UTF-8 flag, and when
round-tripped through SetNewValue + WriteInfo, the flag propagates to the
output buffer.

**Approach — per-handler fixes:**

Each handler that produces string results via `new RuntimeScalar(String)` needs
to set `type = BYTE_STRING` on the result. This is safe because:

- Numeric formats (n, N, v, V, s, S, i, I, l, L, q, Q): return integers, already OK
- String formats (a, A, Z): should return BYTE_STRING
- Hex/bit formats (H, h, B, b): produce ASCII hex/bit strings, should be BYTE_STRING
- `U` format: returns code points — may legitimately need STRING for chars > 0xFF
- `C` format: returns byte values as integers, already OK

**Files to audit:**
- `src/main/java/org/perlonjava/runtime/operators/unpack/HexStringFormatHandler.java`
- `src/main/java/org/perlonjava/runtime/operators/unpack/StringFormatHandler.java`
- `src/main/java/org/perlonjava/runtime/operators/unpack/BitStringFormatHandler.java`
- `src/main/java/org/perlonjava/runtime/operators/unpack/PointerFormatHandler.java`

**NOT a blanket post-process:** A post-processing step on all unpack results
was considered but rejected as dangerous — it could break `unpack("U", $wide_char)`
which legitimately produces UTF-8 strings.

### 5. Other potential sources

These operations may also need auditing for byte-string preservation:

| Operation | Risk | Notes |
|-----------|------|-------|
| `chr()` | Low | Likely OK — returns BYTE_STRING for 0-255 |
| `substr()` | Medium | Result should inherit source type |
| `lc/uc/ucfirst/lcfirst` | Medium | Should inherit source type |
| `reverse()` | Low | Should inherit source type |
| Hash/array stringification | Low | Produces addresses, should be byte |

### 6. GPX/Geotag parsing (separate issue)

**Status:** Not yet investigated

ExifTool's Geotag.pm reads GPX files using `$/ = '>'` and regex with `\3`
backreference. 5 test failures in Geotag.t and Geolocation.t. This is a
separate issue from UTF-8 flag handling — likely I/O or regex related.

## Verification

```bash
# Quick sanity check
./jperl -e 'use Encode; print Encode::is_utf8(join("", "\xff")) ? "BAD" : "OK", "\n"'
./jperl -e 'use Encode; print Encode::is_utf8(sprintf "%d", 42) ? "BAD" : "OK", "\n"'
./jperl -e 'use Encode; print Encode::is_utf8("" . 42) ? "BAD" : "OK", "\n"'
./jperl -e 'use Encode; print Encode::is_utf8(unpack("H*", "AB")) ? "BAD" : "OK", "\n"'

# ExifTool test suite
cd /path/to/Image-ExifTool-13.55-0
../jperl -Ilib t/IPTC.t    # Test 4 should pass after unpack fix
../jperl -Ilib t/Writer.t  # Multiple write tests
../jperl -Ilib t/GIF.t     # GIF header byte integrity
```

## Progress Tracking

### Current Status: Fixes 1-3 completed, Fix 4 pending

| Fix | Status | Impact |
|-----|--------|--------|
| join byte-string | Done | High — ExifTool Write path |
| stringConcat byte-string | Done | High — all concat ops |
| sprintf byte-string | Done | Medium — tag value formatting |
| unpack per-handler | Pending | High — ExifTool ImageInfo path |
| GPX/Geotag parsing | Pending | 5 test failures |

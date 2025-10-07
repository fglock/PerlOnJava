# W Format Fix Implementation Plan

## Problem Summary
W format mixed with binary formats fails because:
- packW writes UTF-8 bytes (e1 bf bc for U+1FFC)
- When decoded as UTF-8, binary byte 0x81 becomes U+FFFD (replacement char)
- Need to track character codes vs raw bytes separately

## Solution: PackBuffer Class

Created: `/Users/fglock/projects/PerlOnJava/src/main/java/org/perlonjava/operators/pack/PackBuffer.java`

This class stores both:
- Raw bytes (from N, V, s, etc.)
- Unicode character codes (from W, U)

## Changes Needed in Pack.java

Replace `ByteArrayOutputStream output` with `PackBuffer output`

**Line 204**: Change:
```java
ByteArrayOutputStream output = new ByteArrayOutputStream();
```
To:
```java
PackBuffer output = new PackBuffer();
```

**Line 361-371**: Change final conversion:
```java
// Convert the byte array to a string
byte[] bytes = output.toByteArray();

// Return UTF-8 decoded string only if we never used byte mode AND used U in character mode
if (!byteModeUsed && hasUnicodeInNormalMode) {
    // Pure character mode with U format - decode UTF-8
    return new RuntimeScalar(new String(bytes, StandardCharsets.UTF_8));
} else {
    // Mixed mode or byte mode - return as byte string
    return new RuntimeScalar(bytes);
}
```

To:
```java
// Convert buffer to string based on whether UTF-8 flag should be set
if (!byteModeUsed && hasUnicodeInNormalMode) {
    // UTF-8 flag set: interpret all values as Latin-1 characters
    // This matches Perl's utf8::upgrade behavior
    return new RuntimeScalar(output.toUpgradedString());
} else {
    // No UTF-8 flag: return as byte string
    return new RuntimeScalar(output.toByteArray());
}
```

## Changes Needed in PackHelper.java

**packW method (line 281)**: Change from writing UTF-8 bytes to writing character code:
```java
byte[] utf8Bytes = unicodeChar.getBytes(StandardCharsets.UTF_8);
output.write(utf8Bytes);
```

To:
```java
output.writeCharacter(codePoint);
```

**All other pack methods**: Change from `output.write(byte)` to `output.writeByte(byte)` or `output.writeBytes(bytes)`.

## Changes Needed in Other Files

Search for all `output.write()` calls in pack handlers and change to:
- `output.writeByte()` for single bytes
- `output.writeBytes()` for byte arrays
- `output.writeCharacter()` for W/U formats

## Testing

After implementation:
```bash
./jperl -e 'my $p = pack "W N", 8188, 0x23456781; 
for my $i (0..length($p)-1) { 
    printf "Char %d: U+%04X\n", $i, ord(substr($p, $i, 1)); 
}'
```

Expected output:
```
Char 0: U+1FFC
Char 1: U+0023
Char 2: U+0045
Char 3: U+0067
Char 4: U+0081
```

Current output has U+FFFD instead of U+0081 at position 4.

## Impact

Should fix 338 W format test failures (58% of remaining failures).

# UTF-16 Source Code Handling Fix

## Problem Summary
UTF-16 encoded Perl source files are not handled correctly by PerlOnJava. When a UTF-16 file contains Unicode characters (e.g., 'Ċ'), they are converted to UTF-8 byte sequences instead of being preserved as Unicode characters. This causes test failures in `t/comp/utf.t`.

## Root Cause Analysis
1. **FileUtils correctly reads UTF-16 files** - The BOM detection and charset decoding work properly
2. **Tokenizer preserves Unicode** - Tokens correctly show Unicode characters like 'Ċ'
3. **Parser treats source as octets by default** - Without `use utf8`, Perl treats source code as bytes, not Unicode
4. **UTF-16 files need implicit `use utf8`** - Since UTF-16 is inherently a Unicode encoding, these files should behave as if `use utf8` was present

### Key Finding
When source code lacks `use utf8`:
- Unicode character 'Ċ' (U+010A) becomes two bytes: "\304\212" (UTF-8 encoding)
- Parser shows: `StringNode: 'Ä\x8A'` (UTF-8 bytes interpreted as Latin-1)

With `use utf8`:
- Unicode character 'Ċ' is preserved as-is
- Parser shows: `StringNode: 'Ċ'`

## Completed Steps
1. ✅ **Fixed `ord()` function** - Changed from `charAt(0)` to `codePointAt(0)` to handle characters outside BMP
2. ✅ **Identified the issue** - Parser treats source as octets without `use utf8`
3. ✅ **Confirmed expected behavior** - UTF-16 files should have implicit `use utf8`

## Implementation Plan

### 1. Add Unicode Source Flag to CompilerOptions
```java:src/main/java/org/perlonjava/ArgumentParser.java
public static class CompilerOptions implements Cloneable {
    // ... existing fields ...
    public boolean isUnicodeSource = false; // true for UTF-16/UTF-32 sources
```

### 2. Modify FileUtils to Set the Flag
**Option A**: Return encoding information with content
```java:src/main/java/org/perlonjava/runtime/FileUtils.java
public static class FileContent {
    public final String content;
    public final boolean isUnicodeEncoded;
    
    public FileContent(String content, boolean isUnicodeEncoded) {
        this.content = content;
        this.isUnicodeEncoded = isUnicodeEncoded;
    }
}

public static FileContent readFileWithEncodingInfo(Path filePath) throws IOException {
    // ... existing logic ...
    boolean isUnicode = (charset == StandardCharsets.UTF_16LE || 
                        charset == StandardCharsets.UTF_16BE);
    return new FileContent(decodedString, isUnicode);
}
```

### 3. Update File Reading in Compiler
Wherever files are read for compilation (likely in PerlCompiler or similar), update to:
- Use the new FileUtils method
- Set `compilerOptions.isUnicodeSource` based on the encoding

### 4. Modify Parser to Check Unicode Flag
The parser needs to treat `isUnicodeSource` as equivalent to `use utf8`. This might involve:
- Setting the UTF8_PRAGMA_BIT_POSITION when isUnicodeSource is true
- Or checking both conditions wherever UTF8_PRAGMA_BIT_POSITION is checked

### 5. Handle `do` and `require`
Ensure that when files are loaded via `do` or `require`, the Unicode source flag is properly:
- Detected for each loaded file
- Applied to that file's compilation context
- Not inherited by the calling context

## Testing

### Primary Test
```bash
./jperl t/comp/utf.t
```
All 4216 tests should pass.

### Verification Tests
```perl
# UTF-16 file should work without explicit 'use utf8'
# Create test file with: echo -en '\xFF\xFE \x00\x27\x00\x0A\x01\x27\x00' > test.pl
my $result = do './test.pl';
print length($result);  # Should print 1, not 2
print ord($result);     # Should print 266 (0x10A), not 196
```

## Edge Cases to Consider
1. **UTF-16 without BOM** - Should still be detected via heuristics
2. **Mixed encodings** - Main file UTF-8, required file UTF-16
3. **Explicit `no utf8`** - Should this override the implicit Unicode mode?
4. **String operations** - Ensure length, substr, split, etc. work correctly
5. **Regular expressions** - Unicode properties should work in UTF-16 sources

## Future Enhancements
1. Support UTF-32 detection and handling
2. Add encoding detection for more formats
3. Improve error messages for encoding issues
4. Consider performance optimizations for the flag checking

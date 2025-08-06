Plan for fixing the UTF-16 source file handling issue:

## Already Completed:
1. **Fixed `ord()` function** - Changed from `charAt(0)` to `codePointAt(0)` to properly handle characters outside the BMP
2. **Identified possible root cause** - StringParser converts Unicode strings to UTF-8 bytes when `use utf8` is not present
3. **Confirmed the issue** - UTF-16 files are read correctly by FileUtils but then incorrectly converted to bytes

## Next Steps:

### 1. Add Unicode Source Flag to CompilerOptions
```java:src/main/java/org/perlonjava/ArgumentParser.java
public static class CompilerOptions implements Cloneable {
    // ... existing fields ...
    public boolean isUnicodeSource = false; // New field for UTF-16/UTF-32 sources
```

### 2. Modify FileUtils to Return Encoding Information
Two approaches:
- **Option A**: Create a result class:
```java
public static class FileContent {
    public String content;
    public boolean isUnicodeEncoded; // true for UTF-16/UTF-32
}
```
- **Option B**: Add a method that sets the flag directly in CompilerOptions

### 3. Update File Reading Logic
In FileUtils, ensure the `isUnicodeSource` flag is set when UTF-16/UTF-32 is detected.

### 4. Modify StringParser to Check Unicode Flag
```java:src/main/java/org/perlonjava/parser/StringParser.java
if (ctx.symbolTable.isStrictOptionEnabled(UTF8_PRAGMA_BIT_POSITION) || 
    ctx.compilerOptions.isUnicodeSource) {
    // Keep Unicode string as-is
    buffers.add(buffer.toString());
} else {
    // Convert to octets (existing logic)
}
```

### 5. Test with t/comp/utf.t
Run the full test suite to ensure all 4216 tests pass:
```bash
./jperl t/comp/utf.t
```

### 6. Additional Considerations
- **Check other string operations**: Ensure split, substr, length, etc. work correctly with Unicode sources
- **Verify `do` and `require`**: Make sure the flag is properly propagated when loading files
- **Test edge cases**: 
  - UTF-16 files without BOM
  - Mixed encodings (main file UTF-8, required file UTF-16)
  - Files with `no utf8` pragma in UTF-16 source

### 7. Update Documentation
Document that UTF-16/UTF-32 source files are implicitly treated as `use utf8` sources.

### 8. Consider Future Enhancements
- Support for UTF-32 detection
- Better error messages for encoding issues
- Performance impact of the additional flag check



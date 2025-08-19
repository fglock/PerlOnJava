
## Migration Plan to Joni Regex

### 1. **Dependencies and Imports**
- Add Joni dependency to the project
- Replace `java.util.regex.Pattern` and `java.util.regex.Matcher` imports with Joni equivalents
- Import Joni's `Regex`, `Matcher`, `Option`, and `Region` classes

### 2. **Pattern Compilation Changes**
- Replace `Pattern.compile()` with Joni's `new Regex()` constructor
- Convert Java regex flags to Joni Options (e.g., `CASE_INSENSITIVE` → `Option.IGNORECASE`)
- Update the pattern caching mechanism to store Joni Regex objects instead of Pattern objects

### 3. **Matcher Creation and Usage**
- Replace `pattern.matcher(input)` with Joni's matcher creation approach
- Update matcher method calls:
  - `find()` → Joni's `search()` or `match()` methods
  - `group()` → Joni's region-based group extraction
  - `start()/end()` → Joni's region-based position methods
  - `groupCount()` → Joni's capture group handling

### 4. **Region Handling**
- Implement Joni's Region object for managing match positions
- Adjust the position tracking logic for `\G` assertions and global matches
- Update the `matcher.region()` calls to use Joni's search region specification

### 5. **Capture Group Management**
- Modify capture group extraction to use Joni's byte-based approach
- Handle the conversion between byte positions and character positions for Unicode strings
- Update the globalMatcher storage to accommodate Joni's matcher structure

### 6. **Flag Conversion**
- Create a mapping between Perl/Java regex flags and Joni Options
- Update `RegexFlags.toPatternFlags()` to return Joni Option values
- Handle any Joni-specific options that might benefit Perl compatibility

### 7. **String Encoding Considerations**
- Ensure proper encoding handling (UTF-8/UTF-16) when converting strings to byte arrays for Joni
- Update position calculations to account for multi-byte characters
- Adjust substring operations for capture groups and match positions

### 8. **Error Handling**
- Update exception handling for Joni-specific exceptions
- Adapt error messages for regex compilation failures

### 9. **Performance Optimizations**
- Consider reusing byte array conversions where possible
- Optimize the caching mechanism for Joni Regex objects
- Profile and adjust buffer sizes for Joni operations

### 10. **Testing and Compatibility**
- Ensure all existing regex features work with Joni
- Test Unicode handling improvements
- Verify performance characteristics
- Check for any regex syntax differences between Java and Joni that might affect existing patterns


## Using JRuby Strings with Joni

### 1. **Native Integration**
- Joni was specifically designed for JRuby, so JRuby strings (RubyString) have built-in support for Joni operations
- Direct byte array access without conversion overhead
- Encoding information is already embedded in RubyString

### 2. **Simplified Position Handling**
- JRuby strings maintain both byte positions and character positions
- Automatic handling of multi-byte character boundaries
- Built-in methods for converting between byte and character indices

### 3. **Encoding Management**
- RubyString carries encoding information with the string data
- Automatic encoding negotiation for regex operations
- Better support for Perl's encoding semantics

### 4. **Performance Improvements**
- Eliminate repeated String ↔ byte[] conversions
- Reuse existing byte arrays from RubyString
- Reduce memory allocations during regex operations

### 5. **Better Perl Compatibility**
- JRuby strings support mutable operations similar to Perl
- Copy-on-write semantics that match Perl's behavior
- Support for binary strings and mixed encodings

## Additional Considerations

### Migration Impact
- Would need to update RuntimeScalar to use RubyString internally
- String operations throughout PerlOnJava would benefit from JRuby's string handling
- Could leverage JRuby's existing regex variable implementations ($1, $2, $&, etc.)

### API Compatibility
- JRuby provides string methods that closely match Perl's string operations
- Could potentially reuse JRuby's regex match data structures
- Simplified implementation of Perl's pos() functionality

